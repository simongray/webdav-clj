(ns ^:no-doc dk.simongray.webdav.impl.locks
  "Write locks, by RFC 4918, sections 6, 7 and 9.10: which paths a lock
  covers, which locks conflict, and the XML of a lock.

  A lock is a map of its :token, the :path and :href of its root, its
  :depth (0 or :infinity), :scope (:exclusive or :shared), :owner (the
  DAV:owner element, if any), :timeout in seconds, the instant it
  :expires in epoch milliseconds, and the :principal that took it."
  (:require [clojure.string :as str]
            [dk.simongray.webdav.paths :as paths]))

;; RFC 4918, section 6.1: a lock of depth infinity on a collection covers
;; its members too
(defn covers?
  "Whether the `lock` covers the resource at `path`: its root, or one below
  it for a lock of depth infinity."
  [{:keys [depth] :as lock} path]
  (if (= :infinity depth)
    (paths/within? (:path lock) path)
    (= (:path lock) (vec path))))

(defn live
  "The `locks` that haven't expired at the instant `now`, in epoch
  milliseconds."
  [locks now]
  (remove #(< (:expires %) now) locks))

(defn covering
  "The `locks` that cover the resource at `path`."
  [locks path]
  (filter #(covers? % path) locks))

(defn rooted-within
  "The `locks` rooted at `path` or below it."
  [locks path]
  (filter #(paths/within? path (:path %)) locks))

;; RFC 4918, section 6.2 and 9.10.5: an exclusive lock conflicts with any
;; other lock of the resources it covers
(defn conflicts
  "The `locks` that conflict with the `lock`: those whose resources overlap
  with its own, where either is exclusive."
  [locks lock]
  (filter #(and (or (covers? % (:path lock)) (covers? lock (:path %)))
                (or (= :exclusive (:scope %)) (= :exclusive (:scope lock))))
          locks))

(defn token
  "A new lock token, a URN of a random UUID, which section 6.5 asks to be
  unique for all time."
  []
  (str "urn:uuid:" (random-uuid)))

;; RFC 4918, section 10.7
(defn timeout
  "The seconds of the lock that the Timeout header value `s` asks for, at
  most `max-seconds`, which is also what Infinite, no header and a header
  that can't be read get."
  [s max-seconds]
  (let [asked (some #(when-let [[_ n] (re-matches #"(?i)second-(\d{1,12})" %)]
                       (parse-long n))
                    (map str/trim (str/split (str s) #",")))]
    (min (or asked max-seconds) max-seconds)))

;; RFC 4918, section 10.5
(defn coded-url
  "The token in the Lock-Token header value `s`, between angle brackets, or
  nil."
  [s]
  (some->> s str/trim (re-matches #"<([^>]+)>") second))

;; RFC 4918, section 14.11
(defn read-lockinfo
  "The :scope and :owner of the DAV:lockinfo `tree` of a LOCK, or nil when
  it isn't a lockinfo of a write lock."
  [[tag _ & children :as tree]]
  (when (= :dav/lockinfo tag)
    (let [child  (fn [t] (some #(when (and (vector? %) (= t (first %))) %) children))
          inside #(some (fn [c] (when (vector? c) (first c))) (drop 2 %))
          scope  ({:dav/exclusive :exclusive :dav/shared :shared}
                  (some-> (child :dav/lockscope) inside))]
      (when (and scope (= :dav/write (some-> (child :dav/locktype) inside)))
        {:scope scope :owner (child :dav/owner)}))))

;; RFC 4918, section 14.1
(defn activelock
  "The DAV:activelock element of the `lock` at the instant `now`, in epoch
  milliseconds."
  [{:keys [scope depth owner expires href] :as lock} now]
  (-> [:dav/activelock {}
       [:dav/locktype {} [:dav/write {}]]
       [:dav/lockscope {} [(keyword "dav" (name scope)) {}]]
       [:dav/depth {} (if (= :infinity depth) "infinity" "0")]]
      (cond-> owner (conj owner))
      (conj [:dav/timeout {} (str "Second-" (max 0 (quot (- expires now) 1000)))]
            [:dav/locktoken {} [:dav/href {} (:token lock)]]
            [:dav/lockroot {} [:dav/href {} href]])))

(defn- child
  "The first child element of the Hiccup `element` named `tag`."
  [element tag]
  (some #(when (and (vector? %) (= tag (first %))) %) (drop 2 element)))

(defn- text
  "The trimmed text of the child named `tag` of the `element`, or of the
  href inside it."
  [element tag]
  (let [c (child element tag)]
    (some-> (or (child c :dav/href) c)
            (->> (drop 2) (filter string?) (apply str))
            str/trim
            not-empty)))

(defn read-activelock
  "The lock of the DAV:activelock `element`, as a client reads it: its
  :token, the :root href, :scope, :depth, :timeout in seconds (or
  :infinite) and :owner element."
  [element]
  (let [timeout (text element :dav/timeout)]
    (cond-> {:token (text element :dav/locktoken)
             :scope (some->> (child element :dav/lockscope) (drop 2) (some #(when (vector? %) %))
                             first name keyword)
             :depth (if (= "infinity" (some-> (text element :dav/depth) str/lower-case))
                      :infinity
                      0)}
      (text element :dav/lockroot)
      (assoc :root (text element :dav/lockroot))

      timeout
      (assoc :timeout (if-let [[_ n] (re-matches #"(?i)second-(\d+)" timeout)]
                        (parse-long n)
                        :infinite))

      (child element :dav/owner)
      (assoc :owner (child element :dav/owner)))))

;; RFC 4918, section 15.10
(def supportedlock
  "The value of DAV:supportedlock: exclusive and shared write locks."
  [[:dav/lockentry {}
    [:dav/lockscope {} [:dav/exclusive {}]]
    [:dav/locktype {} [:dav/write {}]]]
   [:dav/lockentry {}
    [:dav/lockscope {} [:dav/shared {}]]
    [:dav/locktype {} [:dav/write {}]]]])

(comment
  (covers? {:path ["a"] :depth :infinity} ["a" "b"])
  (timeout "Infinite, Second-4100000000" 3600)
  (read-lockinfo [:dav/lockinfo {} [:dav/lockscope {} [:dav/exclusive {}]]
                  [:dav/locktype {} [:dav/write {}]]
                  [:dav/owner {} "litmus test suite"]])
  #_.)
