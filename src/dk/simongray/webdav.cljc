(ns dk.simongray.webdav
  "A WebDAV client whose requests are plain maps, by RFC 4918, with the
  conditional requests of RFC 9110 and the Prefer header of RFC 8144.

  Make a request map with request, send it with wary-fetch or any HTTP
  client, and read the response with parse-response, or do both with
  call!. A response is data whatever its status, so nothing throws for a
  412 or a 404.

  Property names are keywords, e.g. :dav/getetag, by a :namespaces table
  of URI to alias over xml/default-namespaces (see the xml namespace).
  A resource map has the same keys as the resource maps of the server's
  backend."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.headers :as headers]
            [dk.simongray.webdav.conditions :as conditions]
            [dk.simongray.webdav.impl.dates :as dates]
            [dk.simongray.webdav.impl.locks :as locks]
            [dk.simongray.webdav.paths :as paths]
            [dk.simongray.webdav.xml :as xml]
            [dk.simongray.whatwg-url :as url]))

(defn- depth-header
  "The Depth header of the `depth`: 0, 1 or :infinity."
  [depth]
  (if (= :infinity depth) "infinity" (str depth)))

(defn- common-headers
  "The headers that any request can have, by `opts`."
  [{:keys [credentials headers depth if-match if-none-match minimal?]
    :as   opts}]
  (let [auth     (http/authorization credentials)
        if-lists (:if opts)]
    (cond-> {}
      auth          (assoc "Authorization" auth)
      (some? depth) (assoc "Depth" (depth-header depth))
      if-match      (assoc "If-Match" if-match)
      if-none-match (assoc "If-None-Match" if-none-match)
      if-lists      (assoc "If" (if (string? if-lists)
                                  if-lists
                                  (conditions/if-header if-lists)))
      minimal?      (assoc "Prefer" "return=minimal")
      :always       (merge headers))))

(defn- xml-body
  "The `request` with the XML of the Hiccup `tree` as its body, by the
  `namespaces`."
  [request tree namespaces]
  (-> request
      (assoc :body (xml/emit tree namespaces))
      (assoc-in [:headers "Content-Type"] "application/xml; charset=utf-8")))

;; RFC 4918, section 10.6: an Overwrite header that's left out is T
(defn- transfer-headers
  "The Destination and Overwrite headers of a COPY or MOVE of the resource
  at `url`, by `opts`."
  [url {:keys [destination overwrite?] :as opts}]
  (let [resolved (some->> destination (url/resolve url))]
    (when-not resolved
      (throw (ex-info "A COPY or MOVE needs a :destination that's a URL"
                      {:type ::invalid :destination destination})))
    (cond-> {"Destination" resolved}
      (false? overwrite?) (assoc "Overwrite" "F"))))

;; RFC 4918, sections 10.4 and 10.5
(defn- lock-token-headers
  "The header that submits the :lock-token of `opts` with the `method`:
  Lock-Token for an UNLOCK, and else an If header of that token alone,
  unless `opts` give an :if of their own."
  [method {:keys [lock-token] :as opts}]
  (cond
    (= :unlock method) {"Lock-Token" (str "<" lock-token ">")}
    (:if opts)         {}
    :else              {"If" (str "(<" lock-token ">)")}))

;; RFC 4918, section 14.11
(defn- lockinfo
  "The Hiccup of a DAV:lockinfo of a write lock of the `scope`, :exclusive
  or :shared, by the `owner`: text, an element or nil."
  [scope owner]
  (cond-> [:dav/lockinfo {}
           [:dav/lockscope {} [(keyword "dav" (name scope)) {}]]
           [:dav/locktype {} [:dav/write {}]]]
    owner (conj (xml/property :dav/owner owner))))

;; as fetch does, so that a password goes in :credentials, which stays out
;; of the URLs of resources and of error messages
(defn- check-url!
  "Throw unless the `url` is an http or https URL without credentials."
  [url]
  (cond
    (not (#{"http" "https"} (url/scheme url)))
    (throw (ex-info "A request goes to an http or https URL" {:type ::invalid :url url}))

    (or (seq (url/username url)) (seq (url/password url)))
    (throw (ex-info "A URL with credentials in it can't be requested; give them as :credentials"
                    {:type ::invalid}))))

;; RFC 4918, section 10.7
(defn- timeout-header
  "The Timeout header of the `timeout`, in seconds or :infinite."
  [timeout]
  (if (= :infinite timeout) "Infinite" (str "Second-" timeout)))

(defn request
  "The request map of the `method`, e.g. :propfind, to the `url`, with the
  `opts` below. Any method works, e.g. a :report of CalDAV.

  Every request takes these options:

  - :credentials, a :username and a :password, or a :token, as a map or a
    function of no arguments that returns one
  - :headers, more headers
  - :depth, 0, 1 or :infinity
  - :if-match and :if-none-match, an ETag or *
  - :if, an If header, as text or as conditions/parse-if returns it
  - :minimal?, true to prefer a minimal answer (RFC 8144)
  - :lock-token, the token of a lock that the request holds, which goes
    in an If header of its own
  - :namespaces, the aliases of namespaces in property names
  - :body, the body of a method without options of its own, e.g. :report
  - the options of wary-fetch's send!, e.g. :timeout-seconds

  Some methods take more:

  - :propfind, at :depth 0 unless given, takes :props, the names of the
    properties to get, or :propname? true for the names alone. Without
    them it asks for allprop and the names of :include.
  - :proppatch takes :patch, the operations in order: [:set name value]
    or [:set element] and [:remove name].
  - :put takes the :body, text or bytes, and its :content-type.
  - :copy and :move take the :destination, a URL or a path relative to
    `url`, and :overwrite? false to fail when it exists. A :copy takes
    :depth 0 for a collection alone.
  - :get takes :as :bytes for a body of bytes, and a :range of the first
    and the last byte to get (nil for the rest), as wary-fetch's send!
    takes it.
  - :lock takes the :scope (:exclusive or :shared) and the :owner (text
    or an element) of a new lock, and its :timeout in seconds or
    :infinite. Without a :scope it refreshes the lock of its
    :lock-token.
  - :unlock takes the :lock-token of the lock to remove.

      (request :propfind \"https://dav.example.org/notes/\"
               {:depth 1 :props [:dav/getetag :dav/resourcetype]})"
  ([method url]
   (request method url {}))
  ([method url {:keys [namespaces body as] :as opts}]
   (check-url! url)
   (let [method  (keyword (str/lower-case (name method)))
         opts    (cond-> opts
                   (and (= :propfind method) (nil? (:depth opts))) (assoc :depth 0))
         base    (cond-> (http/with-options {:method  method
                                             :url     url
                                             :headers (common-headers opts)}
                           opts)
                   (:lock-token opts) (-> (update :headers merge (lock-token-headers method opts))
                                          (assoc :lock-token (:lock-token opts)))
                   (seq namespaces)   (assoc :namespaces namespaces)
                   as                 (assoc :as as)
                   (:range opts)      (assoc :range (:range opts)))]
     (case method
       :propfind  (xml-body base (xml/propfind opts) namespaces)
       :proppatch (xml-body base (xml/propertyupdate (:patch opts)) namespaces)
       :put       (cond-> (assoc base :body (or body ""))
                    (:content-type opts)
                    (assoc-in [:headers "Content-Type"] (:content-type opts)))
       (:copy
        :move)    (update base :headers merge (transfer-headers url opts))
       :lock      (cond-> base
                    (:timeout opts) (assoc-in [:headers "Timeout"] (timeout-header (:timeout opts)))
                    (:scope opts)   (xml-body (lockinfo (:scope opts) (:owner opts)) namespaces))
       (cond-> base
         (some? body) (assoc :body body))))))

(defn- collection?
  "Whether the value of DAV:resourcetype names DAV:collection."
  [resourcetype]
  (boolean (some #(and (vector? %) (= :dav/collection (first %)))
                 (when (sequential? resourcetype) resourcetype))))

(defn- resource-types
  "The names in the value of DAV:resourcetype besides DAV:collection."
  [resourcetype]
  (when (sequential? resourcetype)
    (not-empty (into [] (comp (filter vector?)
                              (map first)
                              (remove #{:dav/collection}))
                     resourcetype))))

(defn- trimmed
  "The value `v` as trimmed text, or nil when it isn't text or is empty."
  [v]
  (when (string? v)
    (not-empty (str/trim v))))

(defn- success?
  "Whether the `status` is a success."
  [status]
  (and status (<= 200 status 299)))

;; RFC 4918, section 15.8
(defn- activelocks
  "The locks in the value of DAV:lockdiscovery, as maps of their :token,
  the :root href, :scope, :depth, :timeout in seconds (or :infinite) and
  :owner element."
  [lockdiscovery]
  (into []
        (comp (filter #(and (vector? %) (= :dav/activelock (first %))))
              (map locks/read-activelock))
        (when (sequential? lockdiscovery) lockdiscovery)))

;; RFC 4918, section 15: the live properties in a resource map's keys
(defn- with-live-keys
  "The resource map `m` with the keys of the live properties among its
  `props`, as a backend gives them. Without DAV:resourcetype, an href
  with a trailing slash is a collection."
  [m props]
  (let [resourcetype (get props :dav/resourcetype)]
    (cond-> (assoc m :collection? (if (contains? props :dav/resourcetype)
                                    (collection? resourcetype)
                                    (str/ends-with? (:href m) "/")))
      (resource-types resourcetype)
      (assoc :resource-type (resource-types resourcetype))

      (trimmed (:dav/getetag props))
      (assoc :etag (trimmed (:dav/getetag props)))

      (trimmed (:dav/getcontenttype props))
      (assoc :content-type (trimmed (:dav/getcontenttype props)))

      (some->> (:dav/getcontentlength props) trimmed (re-matches #"\d{1,18}"))
      (assoc :content-length (parse-long (trimmed (:dav/getcontentlength props))))

      (some-> (:dav/getlastmodified props) trimmed dates/parse-http-date)
      (assoc :last-modified (dates/parse-http-date (trimmed (:dav/getlastmodified props))))

      (some-> (:dav/creationdate props) trimmed dates/parse-timestamp)
      (assoc :created (dates/parse-timestamp (trimmed (:dav/creationdate props))))

      (contains? props :dav/lockdiscovery)
      (assoc :locks (activelocks (:dav/lockdiscovery props))))))

(defn resource
  "The resource map of a `response` of a multistatus, as xml/read-multistatus
  returns it, to a request of `url`. It has:

  - :href as the server wrote it, and :url, resolved against `url`, when
    it's on the same origin, since a request to it would carry the same
    credentials
  - :name, the decoded last segment, when it can name a file, so not e.g.
    ../x
  - :props, the values of the properties that came back with a success,
    by name, as xml/value gives them
  - :failed, the status of each property that didn't, by name, e.g. 404
    for one that isn't there
  - :status and :error, when the response has them
  - :collection?, :etag, :content-type, :content-length, :last-modified,
    :created and :resource-type, from the live properties
  - :locks, from DAV:lockdiscovery, as maps of each lock's :token, the
    :root href, :scope, :depth, :timeout in seconds (or :infinite) and
    :owner element"
  [url {:keys [href status propstats error] :as response}]
  (let [props  (into {} (for [{:keys [status props]} propstats
                              :when (success? status)
                              element                props]
                          [(first element) (xml/value element)]))
        failed (into {} (for [{:keys [status props]} propstats
                              :when (not (success? status))
                              element                props]
                          [(first element) status]))
        resolved (url/resolve url href)
        segment  (paths/name href)]
    (cond-> (with-live-keys (cond-> {:href href :props props}
                              (url/same-origin? url resolved) (assoc :url resolved)
                              (paths/segment? segment)        (assoc :name segment))
              props)
      status       (assoc :status status)
      (seq failed) (assoc :failed failed)
      (seq error)  (assoc :error (mapv first error)))))

(defn- xml-response?
  "Whether the `response` has an XML body, by its Content-Type."
  [{:keys [headers body] :as response}]
  (and (string? body)
       (re-find #"(?i)[/+]xml\b" (str (headers/header headers "content-type")))))

(defn- parse-xml
  "The Hiccup of the XML body of the `response` by `namespaces`, or nil
  when it isn't XML."
  [response namespaces]
  (when (xml-response? response)
    (try
      (xml/parse (:body response) namespaces)
      (catch #?(:clj Exception :cljs :default) _
        nil))))

(defn- error-names
  "The names of the preconditions in the DAV:error body of the
  `response`, or nil."
  [response namespaces]
  (let [tree (parse-xml response namespaces)]
    (when (= :dav/error (first tree))
      (not-empty (into [] (comp (filter vector?) (map first)) (drop 2 tree))))))

(defn- representation
  "The keys of the resource map that the headers of a GET or a HEAD
  `response` give."
  [{:keys [headers] :as response}]
  (let [length (some->> (headers/header headers "content-length")
                        (re-matches #"\d{1,18}"))
        range  (some-> (headers/header headers "content-range") headers/content-range)]
    (cond-> {}
      (headers/header headers "content-type")
      (assoc :content-type (headers/header headers "content-type"))

      length
      (assoc :content-length (parse-long length))

      range
      (assoc :content-range range)

      (some-> (headers/header headers "last-modified") dates/parse-http-date)
      (assoc :last-modified (dates/parse-http-date (headers/header headers "last-modified"))))))

;; RFC 4918, sections 9.10.6 and 10.5
;; a refresh has no Lock-Token header, and the lock it refreshed can share
;; the DAV:lockdiscovery with the locks of others
(defn- taken-lock
  "The lock that the `response` to a LOCK took or refreshed, read by
  `namespaces`: the one of its Lock-Token header, or else of the
  `lock-token` that the request refreshed, or else the first in its
  DAV:lockdiscovery."
  [{:keys [headers] :as response} lock-token namespaces]
  (let [tree       (parse-xml response namespaces)
        discovered (activelocks (some #(when (and (vector? %) (= :dav/lockdiscovery (first %)))
                                         (xml/value %))
                                      (drop 2 tree)))
        token      (or (locks/coded-url (headers/header headers "lock-token")) lock-token)]
    (if token
      (some #(when (= token (:token %)) %) discovered)
      (first discovered))))

;; RFC 4918, section 10.1
(defn- capabilities
  "The :dav classes and the :allow methods of the answer to OPTIONS."
  [{:keys [headers] :as response}]
  (let [tokens #(some->> (headers/joined headers %)
                         (re-seq #"[^,\s]+"))]
    {:dav   (vec (tokens "dav"))
     :allow (into #{} (map (comp keyword str/lower-case)) (tokens "allow"))}))

(defn parse-response
  "What the `response` (a map of :status, :headers and :body, as
  wary-fetch gives it) to the `request` says.

  It always has the :status, and the :etag when the response has one.
  Then by the status and the method:

  - 207: the :resources, as resource returns them, e.g. of a PROPFIND or
    of the members that a COPY or a DELETE couldn't change, or :malformed?
    true when the body can't be read
  - a success of OPTIONS: the :dav classes and the :allow methods
  - a success of LOCK: the :lock that it took or refreshed, as resource
    gives its :locks
  - a success of GET or HEAD: the :content-type, :content-length and
    :last-modified, and the :body of a GET, with the :content-range of a
    part (a map of :start, :end and :total)
  - an error with a DAV:error body: the precondition names as :error,
    e.g. [:dav/propfind-finite-depth]"
  [{:keys [method url namespaces] :as request} {:keys [status headers] :as response}]
  (let [method (some-> method name str/lower-case keyword)
        etag   (headers/header headers "etag")
        base   (cond-> {:status status}
                 etag (assoc :etag etag))]
    (cond
      (= 207 status)
      (if-let [multistatus (some-> (parse-xml response namespaces) xml/read-multistatus)]
        (assoc base :resources (mapv #(resource url %) (:responses multistatus)))
        (assoc base :malformed? true))

      (and (success? status) (= :options method))
      (merge base (capabilities response))

      (and (success? status) (= :lock method))
      (assoc base :lock (taken-lock response (:lock-token request) namespaces))

      (and (success? status) (#{:get :head} method))
      (cond-> (merge base (representation response))
        (= :get method) (assoc :body (:body response)))

      (error-names response namespaces)
      (assoc base :error (error-names response namespaces))

      :else
      base)))

(defn call!
  "Send the `request` and return what parse-response says of the response
  (or in ClojureScript a promise of it), with the `opts` below:

  - :send, a function that sends a request map, in place of wary-fetch's
    send!

  A request that reaches no server throws as wary-fetch's send! does."
  ([request]
   (call! request {}))
  ([request {:keys [send] :or {send http/send!} :as opts}]
   (async/then (send request) #(parse-response request %))))

(comment
  (request :propfind "https://dav.example.org/notes/"
           {:depth 1 :props [:dav/getetag :dav/resourcetype]})
  (request :copy "https://dav.example.org/notes/a.edn"
           {:destination "b.edn" :overwrite? false})
  (call! (request :options "http://localhost:8765/dav/"))
  #_.)
