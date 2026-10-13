(ns dk.simongray.webdav.conditions
  "ETags and conditional requests: the preconditions of RFC 9110, section
  13, and the If header of RFC 4918, section 10.4.

  An ETag is an entity tag as a header writes it, e.g. \"\\\"v1\\\"\" or
  W/\"v1\" for a weak one. A resource is a map with its :etag and
  :last-modified, or nil when nothing is there."
  (:require [clojure.string :as str]
            [dk.simongray.webdav.impl.dates :as dates]))

(def ^:no-doc entity-tag
  "The pattern of an entity tag, RFC 9110, section 8.8.3, leniently: any
  character but a quote between the quotes."
  #"(?:W/)?\"[^\"]*\"")

(defn etag
  "The entity tag of `x`: `x` itself when it's one, or else `x` as text
  between quotes, without the quotes, spaces and control characters that
  an entity tag can't hold, e.g. \"7\" for 7. It's nil for nil."
  [x]
  (when (some? x)
    (let [s (str x)]
      (if (re-matches entity-tag s)
        s
        (str "\"" (str/replace s #"[\"\x00-\x20\x7f]" "") "\"")))))

(defn weak?
  "Whether the entity tag `tag` is weak."
  [tag]
  (str/starts-with? tag "W/"))

(defn- opaque
  "The quoted part of the entity tag `tag`, without W/."
  [tag]
  (if (weak? tag) (subs tag 2) tag))

;; RFC 9110, section 8.8.3.2
(defn strong-match?
  "Whether the entity tags `a` and `b` match by the strong comparison:
  neither is weak and they're the same."
  [a b]
  (boolean (and a b (not (weak? a)) (not (weak? b)) (= a b))))

(defn weak-match?
  "Whether the entity tags `a` and `b` match by the weak comparison: the
  same apart from W/."
  [a b]
  (boolean (and a b (= (opaque a) (opaque b)))))

;; RFC 9110, sections 13.1.1 and 13.1.2
(defn parse-etags
  "The entity tags of the If-Match or If-None-Match header value `s`, as a
  vector, or :any for *. A value without an entity tag gives none."
  [s]
  (if (= "*" (str/trim (str s)))
    :any
    (vec (re-seq entity-tag (str s)))))

(defn- any-match?
  "Whether the If-Match or If-None-Match value `s` matches the `resource`
  by the comparison `match?`."
  [s match? {:keys [etag] :as resource}]
  (let [tags (parse-etags s)]
    (boolean (and resource
                  (or (= :any tags)
                      (some #(match? % etag) tags))))))

(defn- seconds
  "The instant `t` in whole seconds since the epoch, as HTTP-dates have
  it."
  [t]
  (quot (inst-ms t) 1000))

(defn- modified-since?
  "Whether the `resource` was modified after the HTTP-date `s`, or nil when
  either date is missing or `s` isn't a date."
  [s {:keys [last-modified] :as resource}]
  (when-let [date (some-> s dates/parse-http-date)]
    (when last-modified
      (> (seconds last-modified) (seconds date)))))

;; RFC 9110, section 13.2.2
(defn evaluate
  "The status that the preconditions in the lower-case `headers` of a
  request by `method` give for the `resource`, or nil when the request
  can go ahead: 412 when one fails, or 304 for a GET or a HEAD whose
  If-None-Match or If-Modified-Since fails.

  The preconditions go in the order of RFC 9110, section 13.2.2:
  If-Match, If-Unmodified-Since, If-None-Match and If-Modified-Since.
  If-Match uses the strong comparison and If-None-Match the weak one."
  [method headers resource]
  (let [if-match      (get headers "if-match")
        if-none-match (get headers "if-none-match")
        read?         (#{:get :head} method)]
    (cond
      (and if-match (not (any-match? if-match strong-match? resource)))
      412

      (and (not if-match)
           (modified-since? (get headers "if-unmodified-since") resource))
      412

      (and if-none-match (any-match? if-none-match weak-match? resource))
      (if read? 304 412)

      (and (not if-none-match)
           read?
           (false? (modified-since? (get headers "if-modified-since")
                                    resource)))
      304

      :else
      nil)))

;; RFC 4918, section 10.4.2: a Coded-URL or Resource-Tag, a parenthesis,
;; Not, an entity tag in brackets, or anything else, which is an error
(def ^:no-doc if-token
  "The pattern of a token of the If header."
  #"\s*(<[^>]*>|\(|\)|[Nn][Oo][Tt](?=[\s<\[])|\[(?:W/)?\"[^\"]*\"\]|\S)")

(defn- condition
  "The condition of the If header `token`, or nil when it isn't one."
  [token]
  (cond
    (str/starts-with? token "<") {:token (subs token 1 (dec (count token)))}
    (str/starts-with? token "[") {:etag (subs token 1 (dec (count token)))}
    :else                        nil))

(defn- read-if-token
  "The `state` of the If header's parser after the `token`, or nil when the
  token can't come there.

  The state has the :lists so far, the :href of the next one (nil for the
  Request-URI), whether a list is :open, whether the next condition is
  :not?, and whether the header is :tagged?."
  [{:keys [open tagged? not?] :as state} token]
  (cond
    (= "(" token)
    (when-not open
      (assoc state :open []))

    (= ")" token)
    (when (and (seq open) (not not?))
      (-> state
          (update :lists conj {:href (:href state) :conditions open})
          (dissoc :open)))

    (re-matches #"[Nn][Oo][Tt]" token)
    (when (and open (not not?))
      (assoc state :not? true))

    open
    (when-let [c (condition token)]
      (-> state
          (update :open conj (assoc c :not? (boolean not?)))
          (dissoc :not?)))

    ;; a Resource-Tag, which must come before every list or none
    (str/starts-with? token "<")
    (when (or tagged? (empty? (:lists state)))
      (assoc state :href (subs token 1 (dec (count token))) :tagged? true))

    :else
    nil))

(defn parse-if
  "The lists of the If header value `s`, as a vector of maps of its :href
  (nil for the Request-URI) and its :conditions. A condition is a map of
  a :token or an :etag, and :not?. It's nil when `s` isn't an If header.

      (parse-if \"(<urn:uuid:a> [\\\"x\\\"]) (Not <DAV:no-lock>)\")
      ;; => [{:href nil :conditions [{:token \"urn:uuid:a\" :not? false}
      ;;                             {:etag \"\\\"x\\\"\" :not? false}]}
      ;;     {:href nil :conditions [{:token \"DAV:no-lock\" :not? true}]}]"
  [s]
  (let [tokens (map second (re-seq if-token (str s)))
        state  (reduce (fn [state token]
                         (or (read-if-token state token) (reduced nil)))
                       {:lists []}
                       tokens)]
    (when (and state (not (:open state)) (seq (:lists state)))
      (:lists state))))

;; RFC 4918, sections 10.4.3 and 10.4.4
(defn- condition-true?
  "Whether the `condition` holds for the `state`, a map of the :etag and
  the lock :tokens of a resource."
  [{:keys [token etag not?] :as condition} state]
  (let [held (if token
               (contains? (set (:tokens state)) token)
               (weak-match? etag (:etag state)))]
    (if not? (not held) held)))

(defn if-true?
  "Whether the If header's `lists` hold, given `state-of`, a function of a
  list's :href that returns the :etag and the lock :tokens of its
  resource (empty for an unmapped URL). Each list holds when all its
  conditions do, and the header holds when any list does.

  ETags match by the weak comparison, which section 10.4.4 allows."
  [lists state-of]
  (boolean
   (some (fn [{:keys [href conditions]}]
           (let [state (state-of href)]
             (every? #(condition-true? % state) conditions)))
         lists)))

(defn- written-condition
  "The If header's text of the `condition`."
  [{:keys [token etag not?] :as condition}]
  (str (when not? "Not ")
       (if token (str "<" token ">") (str "[" etag "]"))))

(defn- written-list
  "The If header's text of the list of `conditions`."
  [{:keys [conditions]}]
  (str "(" (str/join " " (map written-condition conditions)) ")"))

(defn if-header
  "The If header value of the `lists`, as parse-if returns them."
  [lists]
  (str/join " "
            (for [group (partition-by :href lists)
                  :let  [href (:href (first group))]]
              (str (when href (str "<" href "> "))
                   (str/join " " (map written-list group))))))

(comment
  (evaluate :put {"if-match" "\"1\", \"2\""} {:etag "\"2\""})
  (evaluate :get {"if-none-match" "W/\"2\""} {:etag "\"2\""})
  (parse-if "</resource1> (<urn:uuid:181d4fae> [W/\"A weak ETag\"]) ([\"strong ETag\"])")
  (if-header (parse-if "(Not <DAV:no-lock>)"))
  #_.)
