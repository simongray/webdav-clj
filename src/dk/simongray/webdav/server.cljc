(ns dk.simongray.webdav.server
  "A Ring handler that serves a backend of your own over WebDAV: classes 1
  and 2 of RFC 4918, with the conditional requests of RFC 9110 and the
  Prefer header of RFC 8144.

  A backend is a map of functions. Each one takes a path, a vector of
  decoded segments below the root of the share (see the paths namespace),
  and can return a promise in ClojureScript:

  - :resource, the resource map of the path, or nil when nothing is there
  - :children, the resource maps of the members of a collection, each
    with its :name
  - :content, the body of a document, as a string, bytes or (on the JVM)
    an InputStream
  - :put!, which writes a document and returns its new resource map
  - :mkcol!, which makes a collection
  - :delete!, which deletes a resource and its members
  - :props, the elements of the dead properties of a resource
  - :patch-props!, which applies the operations of a PROPPATCH, all or
    none
  - :copy! and :move!, which also take the destination path, and :copy! a
    map of the :depth (0 or :infinity)

  Only :resource is required. Without :children the handler won't list a
  collection, and without :put!, :mkcol! or :delete! it refuses those
  methods. Without :copy! and :move! it copies with the other functions,
  and moves by copying and deleting.

  A :put! takes the body and a map of its :content-type. On the JVM the
  body is an InputStream, which throws a refusal of 413 once more than
  :max-bytes are read from it, so a backend can write it to a file as it
  comes, but should read it all before it replaces anything. In
  ClojureScript it's bytes. For a conditional request, the map also has
  the :if-etag, the :etag of the resource map that the handler checked
  (nil when nothing was there), so that a backend that can write
  conditionally refuses a document that changed since. A :patch-props!
  takes the operations in order: [:set element] and [:remove name].

  The functions that write return nil. When :delete!, :copy! or :move!
  can't change some members, they can return a map of those paths to a
  status each, and :patch-props! can return a map of the names it refused
  to a status each. To refuse a whole request, a function throws an
  ex-info whose data has the status as :dk.simongray.webdav/status. It can
  add the names of preconditions as :dk.simongray.webdav/error, which the
  handler writes as a DAV:error body.

  A resource map can have these keys:

  - :collection?, true for a collection
  - :etag, an entity tag or any value to quote as one, e.g. a version
  - :last-modified and :created, instants
  - :content-type, :content-length and :content-language
  - :resource-type, the names of more elements of DAV:resourcetype, e.g.
    [:cal/calendar]

  With :locks, an atom of the lock table, the handler takes LOCK and
  UNLOCK too (class 2). A write to what a lock covers then has to submit
  the lock's token in an If header, as the principal that took it. The
  table is a map of locks by token, which the handler keeps itself, and
  an expired lock simply stops counting. It lives in memory, since locks
  last minutes, so several processes that serve one share must share the
  atom."
  (:require [clojure.string :as str]
            [dk.simongray.qname-hiccup :as qh]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.bytes :as bytes]
            [dk.simongray.webdav.conditions :as conditions]
            [dk.simongray.webdav.impl.copy :as copy]
            [dk.simongray.webdav.impl.dates :as dates]
            [dk.simongray.webdav.impl.locks :as locks]
            [dk.simongray.webdav.impl.props :as props]
            [dk.simongray.webdav.impl.ring :as ring]
            [dk.simongray.webdav.paths :as paths]
            [dk.simongray.webdav.xml :as xml]
            [dk.simongray.whatwg-url :as url]))

(def default-options
  "The default of each option of the handler:

  - :root, the path that the share is served at, /
  - :hosts, the names of the hosts of the share besides those of the
    Host and X-Forwarded-Host headers, e.g. its public name behind a
    proxy that rewrites both; a COPY or a MOVE to any other host gets a
    502
  - :max-bytes, the most that the body of a PUT can hold, 1 GB
  - :max-xml-bytes, the most that the XML body of a PROPFIND or a
    PROPPATCH can hold, 1 MB
  - :propfind-infinity?, whether PROPFIND answers at depth infinity on a
    collection, false
  - :namespaces, the aliases of namespaces in property names, from URI to
    alias, over xml/default-namespaces
  - :live-properties, the live properties of your own, a map from a name
    to a function of the request, the path and the resource map, which
    returns the value or nil when the resource has none
  - :compliance, the classes that the DAV header names after 1, e.g.
    [\"calendar-access\"]
  - :methods, the methods of your own, a map from a method, e.g. :report,
    to a function of the context of the request (see handler)
  - :locks, an atom of the lock table for class 2, e.g. (atom {}), or nil
    for class 1 alone
  - :max-lock-seconds, the longest that a lock lasts before its client
    refreshes it, an hour, whatever the Timeout header asks for
  - :principal, a function of the request that returns who sends it, e.g.
    the user that your login found, since only the principal that took a
    lock can use it; by default nobody, so any client can
  - :clock, a function of no arguments that returns the instant now"
  {:root               "/"
   :hosts              []
   :max-bytes          (* 1024 1024 1024)
   :max-xml-bytes      (* 1024 1024)
   :propfind-infinity? false
   :namespaces         {}
   :live-properties    {}
   :compliance         []
   :methods            {}
   :locks              nil
   :max-lock-seconds   3600
   :principal          (constantly nil)
   :clock              #?(:clj #(java.util.Date.) :cljs #(js/Date.))})

(defn- unless
  "The response `x` (or a promise of it), or else what `f` returns, when
  `x` is nil."
  [x f]
  (async/then x (fn [response] (or response (f)))))

(defn- normalized
  "The `resource` map with its :etag as an entity tag."
  [resource]
  (cond-> resource
    (some? (:etag resource)) (update :etag conditions/etag)))

(defn- resource-at
  "The resource map at `path` in the `backend` of `ctx`, or nil, as the
  backend gives it."
  [{:keys [backend] :as ctx} path]
  (when path
    ((:resource backend) path)))

(defn- children-at
  "The resource maps of the members of the collection at `path` in the
  `backend` of `ctx`."
  [{:keys [backend] :as ctx} path]
  ((:children backend) path))

(defn- unlisted?
  "Whether the resource of `ctx` is a collection whose members the backend
  can't list, since it has no :children."
  [{:keys [backend resource] :as ctx}]
  (and (:collection? resource) (nil? (:children backend))))

(defn- xml-response
  "The response of `status` with the XML of the Hiccup `tree`, by the
  :namespaces of the `opts` of `ctx`."
  [{:keys [opts] :as ctx} status tree]
  {:status  status
   :headers {"Content-Type" "application/xml; charset=utf-8"}
   :body    (xml/emit tree (:namespaces opts))})

(defn- refusal
  "The answer to the request of `ctx` that a backend `refused`, of its
  status and with a DAV:error body of its precondition names, if any."
  [ctx {:keys [status error] :as refused}]
  (if (seq error)
    (xml-response ctx status (xml/error error))
    (ring/text status (get xml/reasons status "Refused"))))

(defn- capable?
  "Whether the `backend` can take the `method` at all."
  [{:keys [content put! mkcol! delete! copy! move!] :as backend} method]
  (case method
    :get    (some? content)
    :put    (some? put!)
    :delete (some? delete!)
    :mkcol  (some? mkcol!)
    :copy   (boolean (or copy! (and content put!)))
    :move   (boolean (or move! (and content put! delete!)))
    true))

;; RFC 9110, section 10.2.1: the methods that the target resource takes
(defn- allowed
  "The methods that the resource of `ctx` takes, as the text of an Allow
  header."
  [{:keys [backend opts resource] :as ctx}]
  (let [document? (and resource (not (:collection? resource)))]
    (->> (concat ["OPTIONS"]
                 (when document? [:get "HEAD"])
                 (when (or document? (not resource)) [:put])
                 (when resource [:delete])
                 ["PROPFIND" "PROPPATCH"]
                 (when-not resource [:mkcol])
                 (when resource [:copy :move])
                 (when (:locks opts) ["LOCK" "UNLOCK"])
                 (map (comp str/upper-case name) (keys (:methods opts))))
         (keep #(if (keyword? %)
                  (when (capable? backend %) (str/upper-case (name %)))
                  %))
         distinct
         (str/join ", "))))

(defn- not-allowed
  "The 405 answer to the request of `ctx`, with the methods it takes."
  [ctx]
  (assoc-in (ring/text 405 "Method Not Allowed") [:headers "Allow"] (allowed ctx)))

(defn- not-found
  "The 404 answer."
  []
  (ring/text 404 "Not Found"))

;; RFC 4918, section 10.2
(defn- depth
  "The Depth among the `headers`: 0, 1 or :infinity, the `default` when
  there's none, or :invalid."
  [headers default]
  (case (some-> (get headers "depth") str/trim str/lower-case)
    nil        default
    "0"        0
    "1"        1
    "infinity" :infinity
    :invalid))

;; RFC 7240, section 2, and RFC 8144
(defn- preferences
  "The preferences of the Prefer header among the `headers`, e.g.
  return=minimal, in lower case and without their parameters or the
  quotes of a value."
  [headers]
  (into #{}
        (comp (map #(-> (first (str/split % #";"))
                        (str/replace "\"" "")
                        str/trim
                        str/lower-case))
              (remove empty?))
        (str/split (str (get headers "prefer")) #",")))

(defn- read-xml
  "The XML body of the request of `ctx` as a map of its :tree, an empty map
  when it has no body, or a map of the :response that refuses it."
  [{:keys [request opts] :as ctx}]
  (let [{:keys [headers body]} request
        max-bytes              (:max-xml-bytes opts)]
    (if (some-> (ring/content-length headers) (> max-bytes))
      {:response (ring/text 413 "The XML body is too large")}
      (let [b (ring/body-bytes body max-bytes)]
        (cond
          (nil? b)                 {:response (ring/text 413 "The XML body is too large")}
          (zero? (bytes/length b)) {}
          :else
          (try
            {:tree (xml/parse (qh/decode b (ring/charset (get headers "content-type")))
                              (:namespaces opts))}
            (catch #?(:clj Exception :cljs :default) _
              {:response (ring/text 400 "The body isn't well-formed XML")})))))))

(defn- host-of
  "The host name in the Host header value `s`, without its port."
  [s]
  (some->> s (re-find #"[^,\s]+") (str "http://") url/parse url/hostname))

;; RFC 4918, section 10.3: a server that can't copy to another server
;; fails the request
(defn- same-host?
  "Whether the absolute `url` names the host that the request of `ctx`
  went to: that of its Host or X-Forwarded-Host header, or one of the
  :hosts of the handler. The scheme and the port don't count, since a
  proxy may change them."
  [{:keys [request opts] :as ctx} url]
  (let [headers (:headers request)
        hosts   (keep host-of (concat (map #(get headers %) ["host" "x-forwarded-host"])
                                      (:hosts opts)))]
    (or (empty? hosts)
        (boolean (some #{(url/hostname url)} hosts)))))

(defn- local-path
  "The path below the root of the URL or absolute path `s` of the request
  of `ctx`, e.g. a Destination. It's nil when `s` names another host or a
  path outside the share, and :invalid when it isn't a URL or has a
  segment that can't name a resource."
  [{:keys [request opts] :as ctx} s]
  (let [base     (str "http://localhost" (:uri request))
        absolute (url/parse (str s))]
    (cond
      (nil? (url/parse (str s) base))              :invalid
      (and absolute (not (same-host? ctx absolute))) nil
      :else                                        (paths/resolve (:root opts) (:uri request) s))))

;; RFC 4918, section 10.4.4: a lock token matches anywhere in the scope of
;; its lock, an unmapped URL included
(defn- if-state
  "The :etag of the `resource` at `path` and the lock :tokens that cover
  it, as the If header of a request in `ctx` compares them."
  [{:keys [locks] :as ctx} path resource]
  {:etag   (some-> resource normalized :etag)
   :tokens (when (vector? path) (map :token (locks/covering locks path)))})

(defn- if-states
  "The state of the resource of each href of the If header's `lists`, by
  href. An href outside the share has none."
  [ctx lists]
  (ring/reduce-then (fn [states href]
                      (let [path (local-path ctx href)]
                        (async/then (when (vector? path) (resource-at ctx path))
                                    #(assoc states href (if-state ctx path %)))))
                    {}
                    (distinct (keep :href lists))))

;; RFC 4918, section 10.4
(defn- if-failure
  "The response that the If header of the request of `ctx` gives when it
  fails, or nil when it holds or there's none. Its untagged lists apply
  to the `resource`."
  [{:keys [request path] :as ctx} resource]
  (when-let [header (get-in request [:headers "if"])]
    (if-let [lists (conditions/parse-if header)]
      (async/then (if-states ctx lists)
                  (fn [states]
                    (when-not (conditions/if-true?
                               lists
                               #(if % (get states %) (if-state ctx path resource)))
                      (ring/text 412 "Precondition Failed"))))
      (ring/text 400 "The If header can't be parsed"))))

;; RFC 4918, section 10.4.1: a token counts as submitted wherever it is in
;; the If header, whether its condition holds or not
(defn- submitted
  "The lock tokens that the If header of the `request` submits."
  [request]
  (set (keep :token (mapcat :conditions
                            (conditions/parse-if (get-in request [:headers "if"]))))))

(defn- mine?
  "Whether the request of `ctx` holds the `lock`: it submits the lock's
  token, and as the principal that took it, as section 6.4 asks."
  [{:keys [request opts submitted] :as ctx} lock]
  (and (contains? submitted (:token lock))
       (= ((:principal opts) request) (:principal lock))))

;; RFC 4918, sections 7 and 7.4: a lock protects the resources it covers,
;; and the membership of a collection it covers
(defn- lock-failure
  "The 423 when a lock covers one of the `paths` that the request of `ctx`
  changes and the request holds no lock that covers it, or nil."
  [{:keys [locks] :as ctx} paths]
  (let [blocked (for [path  (distinct (remove nil? paths))
                      :let  [covering (locks/covering locks path)]
                      :when (and (seq covering) (not-any? #(mine? ctx %) covering))]
                  (first covering))]
    (when (seq blocked)
      (xml-response ctx 423 (xml/error [(into [:dav/lock-token-submitted {}]
                                              (map #(vector :dav/href {} (:href %)))
                                              (distinct blocked))])))))

(defn- subtree
  "The `path` and the roots of the locks of `ctx` below it, all of which a
  request that deletes or replaces the resource at `path` changes."
  [{:keys [locks] :as ctx} path]
  (cons path (map :path (locks/rooted-within locks path))))

;; RFC 4918, section 9.6: a delete destroys the locks rooted on what it
;; deletes
(defn- release!
  "Remove the locks rooted at `path` or below it from the lock table of
  the handler in `ctx`, if it has one."
  [{:keys [opts] :as ctx} path]
  (when-let [table (:locks opts)]
    (swap! table (fn [m] (into {} (remove #(paths/within? path (:path (val %)))) m)))))

(defn- validators
  "The ETag and Last-Modified headers of the `resource`."
  [{:keys [etag last-modified] :as resource}]
  (cond-> {}
    (some? etag)  (assoc "ETag" (conditions/etag etag))
    last-modified (assoc "Last-Modified" (dates/http-date last-modified))))

;; RFC 9110, section 13.2.1: the preconditions count once the request would
;; otherwise succeed
(defn- precondition-failure
  "The response that the preconditions of the request of `ctx` give for
  the `resource`, or nil when they hold."
  [{:keys [request] :as ctx} resource]
  (case (conditions/evaluate (:request-method request) (:headers request)
                             (some-> resource normalized))
    304 {:status 304 :headers (validators resource) :body ""}
    412 (ring/text 412 "Precondition Failed")
    (if-failure ctx resource)))

(defn- conditional?
  "Whether the request of `ctx` has a precondition on its target's ETag."
  [{:keys [request] :as ctx}]
  (some #(contains? (:headers request) %)
        ["if-match" "if-none-match" "if-unmodified-since" "if"]))

;; RFC 4918, sections 9.3.1 and 9.7.1
(defn- parent-failure
  "The 409 when the collection that would hold the resource at the `path`
  of `ctx` isn't there, or nil."
  [{:keys [path] :as ctx}]
  (async/then (resource-at ctx (paths/parent path))
              (fn [parent]
                (when-not (:collection? parent)
                  (ring/text 409 "The parent collection isn't there")))))

(defn- multistatus-response
  "The 207 answer to the request of `ctx` with the multistatus `responses`
  and the `headers`."
  ([ctx responses]
   (multistatus-response ctx responses {}))
  ([ctx responses headers]
   (update (xml-response ctx 207 (xml/multistatus {:responses responses}))
           :headers merge headers)))

(defn- failures
  "The 207 answer for the paths that `failed`, a map of path to status, or
  nil when none did."
  [{:keys [opts] :as ctx} failed]
  (when (and (map? failed) (seq failed))
    (multistatus-response ctx (for [[path status] failed]
                                {:href   (paths/href (:root opts) path)
                                 :status status}))))

;; RFC 4918, section 10.1, and RFC 9110, section 9.3.7
(defn- options-response
  "The answer to OPTIONS for the resource of `ctx`."
  [{:keys [opts] :as ctx}]
  {:status  200
   :headers {"DAV"           (str/join ", " (concat ["1"]
                                                    (when (:locks opts) ["2"])
                                                    (:compliance opts)))
             "Allow"         (allowed ctx)
             "MS-Author-Via" "DAV"}
   :body    ""})

;; RFC 9110, section 14.3: a document of known length answers ranges
(defn- representation
  "The headers of a GET of the document `resource`."
  [{:keys [content-type content-language content-length] :as resource}]
  (cond-> (assoc (validators resource)
                 "Content-Type" (or content-type "application/octet-stream"))
    content-language (assoc "Content-Language" content-language)
    content-length   (assoc "Accept-Ranges" "bytes")))

;; RFC 9110, section 13.1.5
(defn- if-range-holds?
  "Whether the If-Range of the request of `ctx` holds for the `resource`,
  or it has none, so that its Range counts. It holds for the same strong
  ETag, or for the same time of the last change."
  [{:keys [request] :as ctx} {:keys [etag last-modified] :as resource}]
  (let [if-range (some-> (get-in request [:headers "if-range"]) str/trim)]
    (cond
      (nil? if-range)
      true

      (str/includes? if-range "\"")
      (conditions/strong-match? if-range (some-> etag conditions/etag))

      :else
      (boolean (when-let [date (dates/parse-http-date if-range)]
                 (and last-modified
                      (= (quot (inst-ms date) 1000) (quot (inst-ms last-modified) 1000))))))))

(defn- content-answer
  "The answer to the GET of `ctx` once it checks out: the content of the
  document, or the part of it that its Range asks for (RFC 9110, section
  14), or a 416 when the document has none of that part."
  [{:keys [request backend path resource] :as ctx}]
  (let [length (:content-length resource)
        range  (when (if-range-holds? ctx resource)
                 (ring/byte-range (get-in request [:headers "range"]) length))]
    (if (= :unsatisfiable range)
      {:status  416
       :headers {"Content-Range" (str "bytes */" length)}
       :body    ""}
      (async/then ((:content backend) path)
                  (fn [content]
                    (if-let [[start end] range]
                      {:status  206
                       :headers (assoc (representation resource)
                                       "Content-Range" (str "bytes " start "-" end "/" length))
                       :body    (ring/body (ring/slice content start end))}
                      {:status  200
                       :headers (representation resource)
                       :body    (ring/body content)}))))))

;; RFC 9110, sections 9.3.1 and 9.3.2; RFC 4918, section 9.4
(defn- get-response
  "The answer to GET or HEAD for the resource of `ctx`."
  [{:keys [request backend resource] :as ctx}]
  (cond
    (nil? resource)         (not-found)
    (:collection? resource) (not-allowed ctx)

    (and (= :get (:request-method request)) (not (capable? backend :get)))
    (not-allowed ctx)

    :else
    (unless (precondition-failure ctx resource)
            #(if (= :head (:request-method request))
               {:status  200
                :headers (cond-> (representation resource)
                           (:content-length resource)
                           (assoc "Content-Length" (str (:content-length resource))))
                :body    nil}
               (content-answer ctx)))))

;; a failed If header is a 412 before a lock without its token is a 423,
;; as litmus expects
(defn- checked
  "What `f`, a function of no arguments, answers once the preconditions of
  the request of `ctx` hold for the `resource`, and the request holds the
  locks of the `paths` that it changes. Else it's the answer that refuses
  the request."
  [ctx resource paths f]
  (unless (precondition-failure ctx resource)
          #(unless (lock-failure ctx paths) f)))

(defn- put-answer
  "The answer to the PUT of `ctx` once it checks out: the body written, or
  a 413 when it's larger than :max-bytes."
  [{:keys [request backend opts path resource] :as ctx}]
  (if-let [b (ring/request-body (:body request) (:max-bytes opts))]
    (async/then ((:put! backend)
                 path
                 b
                 (cond-> {:content-type (get-in request [:headers "content-type"])}
                   (conditional? ctx) (assoc :if-etag (:etag resource))))
                (fn [written]
                  {:status  (if resource 204 201)
                   :headers (if-let [etag (some-> written :etag conditions/etag)]
                              {"ETag" etag}
                              {})
                   :body    ""}))
    (ring/text 413 "The body is too large")))

;; RFC 9110, section 9.3.4; RFC 4918, section 9.7
(defn- put-response
  "The answer to PUT for the resource of `ctx`."
  [{:keys [request backend opts path resource] :as ctx}]
  (let [headers (:headers request)]
    (cond
      (not (capable? backend :put)) (not-allowed ctx)
      (:collection? resource)       (not-allowed ctx)

      (some-> (ring/content-length headers) (> (:max-bytes opts)))
      (ring/text 413 "The body is too large")

      ;; RFC 9110, section 14.5: a partial PUT would write the part as the
      ;; whole document
      (contains? headers "content-range")
      (ring/text 400 "A PUT of part of a document isn't supported")

      :else
      (unless (parent-failure ctx)
              #(checked ctx resource (cond-> [path] (nil? resource) (conj (paths/parent path)))
                        (fn [] (put-answer ctx)))))))

;; RFC 4918, section 9.6
(defn- delete-response
  "The answer to DELETE for the resource of `ctx`."
  [{:keys [request backend path resource] :as ctx}]
  (cond
    (not (capable? backend :delete)) (not-allowed ctx)
    (nil? resource)                  (not-found)
    (empty? path)                    (ring/text 403 "The root of the share stays")
    (and (:collection? resource)
         (not= :infinity (depth (:headers request) :infinity)))
    (ring/text 400 "A collection is deleted at depth infinity")
    :else
    (checked ctx resource (cons (paths/parent path) (subtree ctx path))
             #(async/then ((:delete! backend) path)
                          (fn [failed]
                            (or (failures ctx failed)
                                (do (release! ctx path)
                                    {:status 204 :headers {} :body ""})))))))

;; RFC 4918, section 9.3
(defn- mkcol-response
  "The answer to MKCOL for the resource of `ctx`."
  [{:keys [request backend path resource] :as ctx}]
  (cond
    (not (capable? backend :mkcol)) (not-allowed ctx)
    resource                        (not-allowed ctx)
    ;; a body of more than no bytes
    (nil? (ring/body-bytes (:body request) 0))
    (ring/text 415 "MKCOL takes no body")
    :else
    (unless (parent-failure ctx)
            #(checked ctx nil [path (paths/parent path)]
                      (fn []
                        (async/then ((:mkcol! backend) path)
                                    (constantly {:status 201 :headers {} :body ""})))))))

(defn- transfer!
  "Copy the `resource` at the path of `ctx` to the path `to` at `depth`,
  or move it when `move?`, with the backend's own functions where it has
  them."
  [{:keys [backend path] :as ctx} to resource depth move?]
  (let [{:keys [copy! move!]} backend]
    (cond
      (and move? move!) (move! path to)
      move?             (copy/move-tree! backend path to resource)
      copy!             (copy! path to {:depth depth})
      :else             (copy/copy-tree! backend path to resource depth))))

(defn- transfer-answer
  "The answer to a COPY, or a MOVE when `move?`, of the resource of `ctx`
  to the path `to` at `depth`, once the request and its preconditions
  check out. The `overwrite` header says whether the resource `existing`
  at `to` may go."
  [{:keys [backend path resource] :as ctx} to existing overwrite depth move?]
  (cond
    (and existing (= "F" overwrite))
    (ring/text 412 "The destination exists and Overwrite is F")

    (and existing (not (:delete! backend)))
    (ring/text 403 "The destination exists and can't be deleted")

    ;; RFC 4918, sections 9.8.4 and 9.9.3: an overwritten destination is
    ;; deleted first, so a collection keeps no members of its own. Section
    ;; 7.6: a lock doesn't follow its resource to the destination.
    :else
    (async/then (when existing ((:delete! backend) to))
                (fn [not-deleted]
                  (or (failures ctx not-deleted)
                      (do (when existing (release! ctx to))
                          (async/then (transfer! ctx to resource depth move?)
                                      (fn [failed]
                                        (or (failures ctx failed)
                                            (do (when move? (release! ctx path))
                                                {:status  (if existing 204 201)
                                                 :headers {}
                                                 :body    ""}))))))))))

(defn- transfer-to
  "The answer to a COPY, or a MOVE when `move?`, of the resource of `ctx`
  to the path `to` at `depth`, with the `overwrite` header, once the
  request itself checks out."
  [{:keys [path resource] :as ctx} to overwrite depth move?]
  (async/then
   (async/all [(resource-at ctx (paths/parent to)) (resource-at ctx to)])
   (fn [[parent existing]]
     (cond
       (not (:collection? parent))
       (ring/text 409 "The destination's parent collection isn't there")

       ;; RFC 4918, section 9.8.3: a collection copied into itself would
       ;; never end
       (paths/within? path to)
       (ring/text 403 "The destination is inside the source")

       :else
       (checked ctx resource (concat [(paths/parent to)]
                                     (subtree ctx to)
                                     (when move? (cons (paths/parent path) (subtree ctx path))))
                #(transfer-answer ctx to existing overwrite depth move?))))))

;; RFC 4918, sections 9.8, 9.9, 10.3 and 10.6
(defn- transfer-response
  "The answer to COPY, or to MOVE when `move?`, for the resource of
  `ctx`."
  [{:keys [request backend path resource] :as ctx} move?]
  (let [headers     (:headers request)
        destination (get headers "destination")
        to          (some->> destination (local-path ctx))
        overwrite   (some-> (get headers "overwrite") str/trim str/upper-case)
        depth       (depth headers :infinity)]
    (cond
      (not (capable? backend (if move? :move :copy))) (not-allowed ctx)
      (nil? resource)                                 (not-found)
      (nil? destination)                              (ring/text 400 "A Destination is missing")
      (= :invalid to)                                 (ring/text 400 "The Destination isn't a URL of a resource")
      (nil? to)                                       (ring/text 502 "The Destination is on another server or outside this share")
      (not (contains? #{nil "T" "F"} overwrite))      (ring/text 400 "Overwrite is T or F")
      (or (#{:invalid 1} depth)
          (and move? (:collection? resource) (not= :infinity depth)))
      (ring/text 400 "The Depth of a COPY is 0 or infinity, and of a MOVE infinity")

      ;; RFC 4918, sections 9.8.5 and 9.9.4: the same resource, or one that
      ;; holds the source
      (paths/within? to path)
      (ring/text 403 "The destination is the source or holds it")

      (and (= :infinity depth)
           (not (if move? (:move! backend) (:copy! backend)))
           (unlisted? ctx))
      (ring/text 403 "The collection can't be listed")

      :else
      (transfer-to ctx to overwrite depth move?))))

(defn- members
  "The paths and resource maps of the resource at `path` and of its
  members, down to `depth`."
  [ctx path resource depth]
  (if (and (:collection? resource) (not= 0 depth))
    (async/then
     (children-at ctx path)
     (fn [children]
       (ring/reduce-then (fn [found {:keys [name] :as child}]
                           (if (= :infinity depth)
                             (async/then (members ctx (conj path name) child depth)
                                         #(into found %))
                             (conj found [(conj path name) child])))
                         [[path resource]]
                         children)))
    [[path resource]]))

;; RFC 4918, section 15.8: DAV:lockdiscovery lists the locks that cover a
;; resource, those of the collections above it included
(defn- with-locks
  "The `resource` map of the resource at `path` with the active locks that
  cover it, when the handler of `ctx` takes locks."
  [{:keys [opts locks now] :as ctx} path resource]
  (cond-> resource
    (:locks opts) (assoc ::props/activelocks
                         (mapv #(locks/activelock % now) (locks/covering locks path)))))

(defn propstats
  "The propstats of the resource at `path`, whose map is `resource`, for
  the `propfind`, as xml/read-propfind returns it, in the context `ctx`
  of a request. A method of your own can use it, e.g. for a REPORT."
  [ctx propfind path resource]
  (props/propstats ctx propfind path (with-locks ctx path resource)))

;; RFC 8144, section 2.1
(defn- minimal
  "The `propstats` without those of status 404, or a propstat of no
  properties at 200 when none is left."
  [propstats]
  (or (not-empty (vec (remove #(= 404 (:status %)) propstats)))
      [{:status 200 :props []}]))

(defn- propfind-answer
  "The 207 answer to the `propfind` of the resource of `ctx`, at `depth`,
  with the `prefs` of the Prefer header."
  [{:keys [opts path resource] :as ctx} propfind depth prefs]
  (let [minimal? (contains? prefs "return=minimal")
        noroot?  (and (contains? prefs "depth-noroot") (not= 0 depth))
        applied  (cond-> []
                   minimal? (conj "return=minimal")
                   noroot?  (conj "depth-noroot"))]
    (async/then
     (members ctx path resource depth)
     (fn [found]
       (async/then
        (ring/reduce-then
         (fn [responses [p r]]
           (async/then (propstats ctx propfind p r)
                       (fn [ps]
                         (conj responses
                               {:href      (paths/href (:root opts) p (:collection? r))
                                :propstats (cond-> ps minimal? minimal)}))))
         []
         (cond->> found noroot? rest))
        (fn [responses]
          (multistatus-response ctx responses
                                (if (seq applied)
                                  {"Preference-Applied" (str/join ", " applied)}
                                  {}))))))))

;; RFC 4918, section 9.1
(defn- propfind-response
  "The answer to PROPFIND for the resource of `ctx`."
  [{:keys [request opts resource] :as ctx}]
  (let [headers (:headers request)
        depth   (depth headers :infinity)]
    (cond
      (nil? resource)
      (not-found)

      (= :invalid depth)
      (ring/text 400 "The Depth is 0, 1 or infinity")

      (and (= :infinity depth)
           (:collection? resource)
           (not (:propfind-infinity? opts)))
      (xml-response ctx 403 (xml/error [:dav/propfind-finite-depth]))

      (and (not= 0 depth) (unlisted? ctx))
      (ring/text 403 "The collection can't be listed")

      :else
      (unless (precondition-failure ctx resource)
              #(let [{:keys [tree response]} (read-xml ctx)
                     propfind                (if tree
                                               (xml/read-propfind tree)
                                               {:allprop? true})]
                 (cond
                   response        response
                   (nil? propfind) (ring/text 400 "The body isn't a DAV:propfind")
                   :else           (propfind-answer ctx propfind depth (preferences headers))))))))

(defn- protected?
  "Whether the property `name` is live, which PROPPATCH can't change."
  [{:keys [opts] :as ctx} name]
  (or (some #{name} props/live-names)
      (contains? (:live-properties opts) name)))

(defn- patch-answer
  "The answer to the PROPPATCH of `ctx`, which sets or removes the
  properties `names`. Those in the map `refused` get their status and the
  others 424, or all get 200 when none was refused. With return=minimal
  among the `prefs`, a success is a 204."
  [ctx names refused prefs]
  (if (and (empty? refused) (contains? prefs "return=minimal"))
    {:status 204 :headers {"Preference-Applied" "return=minimal"} :body ""}
    (multistatus-response
     ctx
     [{:href      (paths/href (get-in ctx [:opts :root]) (:path ctx)
                              (:collection? (:resource ctx)))
       :propstats (for [[status names] (group-by #(get refused % (if (seq refused) 424 200))
                                                 names)]
                    (cond-> {:status status :props (mapv #(vector % {}) names)}
                      (and (= 403 status) (some #(protected? ctx %) names))
                      (assoc :error [:dav/cannot-modify-protected-property])))}])))

(defn- operation-name
  "The name of the property that the PROPPATCH operation `op` sets or
  removes."
  [[kind x :as op]]
  (if (= :set kind) (first x) x))

(defn- patch
  "The answer to the PROPPATCH operations `ops` on the resource of `ctx`,
  with the `prefs` of the Prefer header. A protected property fails them
  all, as section 9.2 requires."
  [{:keys [backend path] :as ctx} ops prefs]
  (let [names     (distinct (map operation-name ops))
        protected (filter (partial protected? ctx) names)]
    (cond
      (seq protected)
      (patch-answer ctx names (zipmap protected (repeat 403)) prefs)

      (nil? (:patch-props! backend))
      (patch-answer ctx names (zipmap names (repeat 403)) prefs)

      :else
      (async/then ((:patch-props! backend) path ops)
                  (fn [refused]
                    (patch-answer ctx names (if (map? refused) refused {}) prefs))))))

;; RFC 4918, section 9.2
(defn- proppatch-response
  "The answer to PROPPATCH for the resource of `ctx`."
  [{:keys [request path resource] :as ctx}]
  (if (nil? resource)
    (not-found)
    (checked ctx resource [path]
             #(let [{:keys [tree response]} (read-xml ctx)
                    ops                     (some-> tree xml/read-propertyupdate)]
                (cond
                  response     response
                  (empty? ops) (ring/text 400 "The body isn't a DAV:propertyupdate")
                  :else        (patch ctx ops (preferences (:headers request))))))))

;; RFC 9110, sections 15.5.6 and 15.6.2
(defn- unknown-response
  "The answer to a method that the handler doesn't take for the request of
  `ctx`: 405 for a method of HTTP or of the WebDAV family, e.g. LOCK, and
  501 for any other."
  [{:keys [request] :as ctx}]
  (if (#{:post :patch :trace :connect :lock :unlock :report :search :acl
         :mkcalendar}
       (:request-method request))
    (not-allowed ctx)
    (ring/text 501 "Not Implemented")))

(defn- lock-answer
  "The answer of `status` to a LOCK of `ctx`, with the DAV:lockdiscovery
  of the `taken` locks and the `headers`."
  [{:keys [now] :as ctx} status taken headers]
  (-> (xml-response ctx status [:dav/prop {} (into [:dav/lockdiscovery {}]
                                                    (map #(locks/activelock % now))
                                                    taken)])
      (update :headers merge headers)))

(defn- new-lock
  "The lock that the LOCK of `ctx` asks for, by the `lockinfo` of its body
  and at `depth`. A document is locked at depth 0, whatever is asked."
  [{:keys [request opts path resource now] :as ctx} {:keys [scope owner] :as lockinfo} depth]
  (let [seconds (locks/timeout (get-in request [:headers "timeout"]) (:max-lock-seconds opts))]
    {:token     (locks/token)
     :path      path
     :href      (paths/href (:root opts) path (:collection? resource))
     :depth     (if (:collection? resource) depth 0)
     :scope     scope
     :owner     owner
     :timeout   seconds
     :expires   (+ now (* 1000 seconds))
     :principal ((:principal opts) request)}))

(defn- take-lock!
  "Add the `lock` to the lock table of the handler of `ctx` and drop the
  locks that have expired, unless a live lock conflicts with it. It
  returns the conflicting locks, or nil when it took the lock."
  [{:keys [opts now] :as ctx} lock]
  (let [conflicting (volatile! nil)]
    (swap! (:locks opts)
           (fn [table]
             (let [live  (into {} (remove #(< (:expires (val %)) now)) table)
                   found (seq (locks/conflicts (vals live) lock))]
               (vreset! conflicting found)
               (if found live (assoc live (:token lock) lock)))))
    @conflicting))

(defn- unlock!
  "Remove the lock of `token` from the lock table of the handler of
  `ctx`."
  [{:keys [opts] :as ctx} token]
  (swap! (:locks opts) dissoc token))

;; RFC 4918, sections 9.10.1, 9.10.4 and 9.10.6
(defn- lock-new
  "The answer to a LOCK of `ctx` that takes a new lock by the `lockinfo`
  at `depth`. An unmapped URL gets an empty document, as section 9.10.4
  asks."
  [{:keys [backend path resource] :as ctx} lockinfo depth]
  (let [lock   (new-lock ctx lockinfo depth)
        answer #(lock-answer ctx % [lock] {"Lock-Token" (str "<" (:token lock) ">")})]
    (if-let [conflicting (take-lock! ctx lock)]
      (xml-response ctx 423 (xml/error [(into [:dav/no-conflicting-lock {}]
                                              (map #(vector :dav/href {} (:href %)))
                                              conflicting)]))
      (if resource
        (answer 200)
        (async/then (ring/attempt #((:put! backend) path (ring/put-body "") {}))
                    (fn [result]
                      (if-let [refused (::ring/refused result)]
                        (do (unlock! ctx (:token lock))
                            (refusal ctx refused))
                        (answer 201))))))))

;; RFC 4918, section 9.10.2
(defn- lock-refresh
  "The answer to a LOCK of `ctx` without a body, which refreshes the locks
  that cover its resource and whose tokens its If header submits."
  [{:keys [request opts path locks now] :as ctx}]
  (let [seconds   (locks/timeout (get-in request [:headers "timeout"]) (:max-lock-seconds opts))
        refreshed (for [lock (locks/covering locks path)
                        :when (mine? ctx lock)]
                    (assoc lock :timeout seconds :expires (+ now (* 1000 seconds))))]
    (cond
      (not (contains? (:headers request) "if"))
      (ring/text 400 "A LOCK without a body refreshes the locks whose tokens its If header submits")

      (empty? refreshed)
      (xml-response ctx 412 (xml/error [:dav/lock-token-matches-request-uri]))

      :else
      (do (swap! (:locks opts)
                 (fn [table]
                   (reduce #(cond-> %1 (contains? %1 (:token %2)) (assoc (:token %2) %2))
                           table
                           refreshed)))
          (lock-answer ctx 200 refreshed {})))))

;; RFC 4918, section 9.10
(defn- lock-response
  "The answer to LOCK for the resource of `ctx`."
  [{:keys [request backend opts resource] :as ctx}]
  (let [depth (depth (:headers request) :infinity)]
    (cond
      (nil? (:locks opts))  (unknown-response ctx)
      (#{:invalid 1} depth) (ring/text 400 "The Depth of a LOCK is 0 or infinity")
      :else
      (let [{:keys [tree response]} (read-xml ctx)
            lockinfo                (some-> tree locks/read-lockinfo)]
        (cond
          response
          response

          (nil? tree)
          (unless (precondition-failure ctx resource) #(lock-refresh ctx))

          (nil? lockinfo)
          (ring/text 400 "The body isn't a DAV:lockinfo of a write lock")

          resource
          (unless (precondition-failure ctx resource) #(lock-new ctx lockinfo depth))

          (not (capable? backend :put))
          (not-allowed ctx)

          :else
          (unless (parent-failure ctx)
                  #(unless (precondition-failure ctx nil)
                           (fn [] (lock-new ctx lockinfo depth)))))))))

;; RFC 4918, section 9.11
(defn- unlock-response
  "The answer to UNLOCK for the resource of `ctx`."
  [{:keys [request opts path locks] :as ctx}]
  (let [token (locks/coded-url (get-in request [:headers "lock-token"]))
        lock  (some #(when (= token (:token %)) %) locks)]
    (cond
      (nil? (:locks opts))
      (unknown-response ctx)

      (nil? token)
      (ring/text 400 "An UNLOCK needs a Lock-Token header")

      (or (nil? lock) (not (locks/covers? lock path)))
      (xml-response ctx 409 (xml/error [:dav/lock-token-matches-request-uri]))

      ;; RFC 4918, section 6.4
      (not= ((:principal opts) request) (:principal lock))
      (ring/text 403 "Only the principal that took the lock can remove it")

      :else
      (do (unlock! ctx token)
          {:status 204 :headers {} :body ""}))))

(defn- respond
  "The answer to the request of `ctx` by its method."
  [{:keys [request opts] :as ctx}]
  (let [method (:request-method request)]
    (if-let [f (get (:methods opts) method)]
      (f ctx)
      (case method
        :options   (options-response ctx)
        (:get
         :head)    (get-response ctx)
        :put       (put-response ctx)
        :delete    (delete-response ctx)
        :mkcol     (mkcol-response ctx)
        :copy      (transfer-response ctx false)
        :move      (transfer-response ctx true)
        :propfind  (propfind-response ctx)
        :proppatch (proppatch-response ctx)
        :lock      (lock-response ctx)
        :unlock    (unlock-response ctx)
        (unknown-response ctx)))))

(defn- answer
  "The answer to the Ring `request` from the `backend`, by the handler's
  `opts`."
  [backend opts request]
  (let [request (update request :headers ring/lower-case-headers)
        path    (paths/parse (:root opts) (:uri request))
        now     (inst-ms ((:clock opts)))
        ctx     {:request   request
                 :backend   backend
                 :opts      opts
                 :path      path
                 :now       now
                 :locks     (some-> (:locks opts) deref vals (locks/live now))
                 :submitted (submitted request)}]
    (cond
      (nil? path)       (not-found)
      (= :invalid path) (ring/text 400 "The path has a segment that can't name a resource")
      :else
      (async/then (ring/attempt #(async/then (resource-at ctx path)
                                             (fn [resource]
                                               (respond (assoc ctx :resource resource)))))
                  (fn [result]
                    (if-let [refused (::ring/refused result)]
                      (refusal ctx refused)
                      result))))))

(defn handler
  "A Ring handler that serves the `backend` over WebDAV with the `opts` of
  default-options. The namespace's docstring describes a backend.

  Mount it for the :root and every path below it. It checks no
  credentials, so mount it behind your login. It takes the arguments of a
  synchronous and of an asynchronous handler, and in ClojureScript it
  returns a promise of the response, whose body must be a string or bytes.

  A function of :methods gets the context of the request: a map of the
  :request (with lower-case headers), the :path, the :resource map (nil
  when nothing is there), the :backend and the :opts. A live property can
  e.g. give the principal of the user that your login put in the request,
  for RFC 5397:

      (server/handler backend
                      {:root            \"/dav/\"
                       :live-properties {:dav/current-user-principal
                                         (fn [request _ _]
                                           [:dav/href {} (principal-href request)])}})"
  ([backend]
   (handler backend {}))
  ([backend opts]
   (let [opts (-> (merge default-options opts)
                  (update :root paths/root))]
     (ring/handler #(answer backend opts %)))))
