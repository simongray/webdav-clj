(ns dk.simongray.webdav.xml
  "The XML bodies of WebDAV as Hiccup and as data: multistatus, propfind,
  propertyupdate and error, by RFC 4918, section 14.

  Names are keywords, as qname-hiccup reads them. A namespace with an
  alias in the `namespaces` table (URI to alias, over default-namespaces)
  gives keywords under its alias, e.g. :dav/getetag. Any other namespace
  gives keywords under its URI, so that no name is lost, e.g. (keyword
  \"http://example.com/ns/\" \"color\"), and a name in no namespace is a
  plain keyword. Writing a tree gives each URI a prefix of its own."
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [dk.simongray.qname-hiccup :as qh]))

(def default-namespaces
  "The namespaces that every table has, from URI to alias."
  {"DAV:" :dav})

(defn- uri->alias
  "The function that gives each namespace URI its keyword namespace by
  `namespaces`: the alias, or else the URI itself.

  A URI that's also the name of an alias, e.g. dav, would pass for that
  alias's namespace, so it throws. Namespaces in XML deprecates such
  relative URIs anyway."
  [namespaces]
  (let [table   (merge default-namespaces namespaces)
        aliases (set (map name (vals table)))]
    (fn [uri]
      (cond
        (contains? table uri)   (table uri)
        (contains? aliases uri) (throw (ex-info "A namespace URI is the name of an alias"
                                                {:type ::malformed :uri uri}))
        :else                   uri))))

;; Namespaces in XML 1.0, section 3: a prefix must be declared
(defn- malformed-namespace?
  "Whether a `problem` that qname-hiccup reports makes a document
  malformed."
  [{:keys [id] :as problem}]
  (= :xml/undeclared-prefix id))

(defn- without-xmlns
  "The `tree` without the :xmlns attributes that undeclare a default
  namespace, which the keywords make redundant."
  [tree]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (contains? x :xmlns))
                     (dissoc x :xmlns)
                     x))
                 tree))

(defn parse
  "The Hiccup of the XML text `s`, with names by `namespaces`.

  A document that isn't well-formed or namespace-well-formed throws
  ::malformed, and so does one that nests deeper than qname-hiccup allows.
  No external entity is ever read."
  ([s]
   (parse s nil))
  ([s namespaces]
   (let [tree (try
                (qh/parse s (uri->alias namespaces))
                (catch #?(:clj Exception :cljs :default) e
                  (throw (ex-info "The XML isn't well-formed"
                                  {:type ::malformed} e))))]
     (when (some malformed-namespace? (qh/report tree))
       (throw (ex-info "The XML isn't namespace-well-formed"
                       {:type ::malformed :report (qh/report tree)})))
     (without-xmlns tree))))

(defn- element?
  "Whether `x` is a Hiccup element: a vector with a keyword first."
  [x]
  (and (vector? x) (keyword? (first x))))

(defn emit
  "The XML text of the Hiccup `tree` on one line, with names by
  `namespaces`, so that a property's value reads back as it was.

  Each alias in use is declared on the root element, and each URI used as
  a keyword namespace gets a prefix of its own, e.g. ns1. A name in no
  namespace is written without a prefix."
  ([tree]
   (emit tree nil))
  ([tree namespaces]
   (qh/emit tree
            (set/map-invert (merge default-namespaces namespaces))
            {:indent false})))

(defn clark
  "The name `k` in Clark notation, e.g. {DAV:}getetag for :dav/getetag,
  by `namespaces`. A backend can keep property names as these strings,
  which, unlike keywords with a URI namespace, read back from EDN."
  ([k]
   (clark k nil))
  ([k namespaces]
   (let [alias->uri (set/map-invert (merge default-namespaces namespaces))
         ns         (namespace k)
         uri        (get alias->uri (some-> ns keyword) ns)]
     (str "{" uri "}" (name k)))))

(defn- children
  "The child elements of the `element`, without its text."
  [element]
  (filter vector? (qh/children element)))

(defn- child
  "The first child element of the `element` named `tag`."
  [element tag]
  (some #(when (= tag (qh/tag %)) %) (children element)))

(defn- text
  "The text of the child named `tag` of the `element`, trimmed, or nil."
  [element tag]
  (some-> (child element tag) qh/deep-text qh/trim not-empty))

;; RFC 4918, section 4.3
(defn value
  "The value of the property `element`: its text when it holds only text,
  its children as Hiccup when it holds an element, or nil when it's
  empty."
  [element]
  (let [content (qh/children element)]
    (cond
      (empty? content)         nil
      (every? string? content) (apply str content)
      :else                    (vec content))))

(defn property
  "The element of the property `name` with the `value`: text, an element,
  a sequence of children (text and elements), or nil for none."
  [name value]
  (cond
    (nil? value)        [name {}]
    (string? value)     [name {} value]
    (element? value)    [name {} value]
    (sequential? value) (into [name {}] value)
    :else               [name {} (str value)]))

(def reasons
  "The reason phrase of each status that WebDAV uses, by RFC 9110 and RFC
  4918, section 11."
  {200 "OK"                    201 "Created"
   204 "No Content"            207 "Multi-Status"
   304 "Not Modified"          400 "Bad Request"
   401 "Unauthorized"          403 "Forbidden"
   404 "Not Found"             405 "Method Not Allowed"
   409 "Conflict"              412 "Precondition Failed"
   413 "Content Too Large"     415 "Unsupported Media Type"
   422 "Unprocessable Content" 423 "Locked"
   424 "Failed Dependency"     500 "Internal Server Error"
   502 "Bad Gateway"           507 "Insufficient Storage"})

;; RFC 4918, section 14.28, the Status-Line of RFC 2616
(defn status-line
  "The status line of the `status`, e.g. HTTP/1.1 404 Not Found."
  [status]
  (str "HTTP/1.1 " status " " (get reasons status "Unknown")))

(defn- read-status
  "The status number of the `status-line`, or nil."
  [status-line]
  (some->> status-line (re-find #"^\s*\S+\s+(\d{3})") second parse-long))

;; RFC 4918, section 14.5
(defn error
  "The DAV:error element of the preconditions or postconditions `names`,
  e.g. [:dav/propfind-finite-depth]. An element instead of a name keeps
  its content."
  [names]
  (into [:dav/error {}] (map #(if (keyword? %) [% {}] %)) names))

(defn- propstat-element
  "The DAV:propstat element of the `propstat` map."
  [{:keys [status props description] :as propstat}]
  (cond-> [:dav/propstat {} (into [:dav/prop {}] props)
           [:dav/status {} (status-line status)]]
    (seq (:error propstat)) (conj (error (:error propstat)))
    description             (conj [:dav/responsedescription {} description])))

;; RFC 4918, section 14.24
(defn- response-element
  "The DAV:response element of the `response` map."
  [{:keys [href status propstats description location] :as response}]
  (cond-> [:dav/response {} [:dav/href {} href]]
    (seq propstats)         (into (map propstat-element) propstats)
    (empty? propstats)      (conj [:dav/status {} (status-line (or status 200))])
    (seq (:error response)) (conj (error (:error response)))
    description             (conj [:dav/responsedescription {} description])
    location                (conj [:dav/location {} [:dav/href {} location]])))

;; RFC 4918, section 14.16; RFC 6578, section 6.2, for the sync-token
(defn multistatus
  "The Hiccup of the multistatus `m`, a map of :responses and, optionally,
  :description and :sync-token.

  A response is a map of its :href and either a :status or :propstats,
  and optionally :error, :description and :location. A propstat is a map
  of :status, :props (property elements) and optionally :error and
  :description. An :error is a sequence of precondition names or
  elements."
  [{:keys [responses description sync-token] :as m}]
  (cond-> (into [:dav/multistatus {}] (map response-element) responses)
    sync-token  (conj [:dav/sync-token {} sync-token])
    description (conj [:dav/responsedescription {} description])))

(defn- read-propstat
  "The map of the DAV:propstat `element`."
  [element]
  (cond-> {:status (read-status (text element :dav/status))
           :props  (vec (some-> (child element :dav/prop) children))}
    (child element :dav/error)
    (assoc :error (vec (children (child element :dav/error))))

    (text element :dav/responsedescription)
    (assoc :description (text element :dav/responsedescription))))

(defn- read-response
  "The response maps of the DAV:response `element`, one for each of its
  hrefs."
  [element]
  (let [hrefs     (for [h (children element)
                        :when (= :dav/href (qh/tag h))]
                    (qh/trim (qh/deep-text h)))
        propstats (for [p (children element)
                        :when (= :dav/propstat (qh/tag p))]
                    (read-propstat p))
        base      (cond-> {}
                    (seq propstats)
                    (assoc :propstats (vec propstats))

                    (text element :dav/status)
                    (assoc :status (read-status (text element :dav/status)))

                    (child element :dav/error)
                    (assoc :error (vec (children (child element :dav/error))))

                    (text element :dav/responsedescription)
                    (assoc :description (text element :dav/responsedescription))

                    (child element :dav/location)
                    (assoc :location (text (child element :dav/location)
                                           :dav/href)))]
    (for [href hrefs]
      (assoc base :href href))))

(defn read-multistatus
  "The multistatus map of the Hiccup `tree`, as multistatus takes it, or
  nil when its root isn't DAV:multistatus. A response with several hrefs
  gives a map for each."
  [tree]
  (when (= :dav/multistatus (qh/tag tree))
    (cond-> {:responses (into [] (comp (filter #(= :dav/response (qh/tag %)))
                                       (mapcat read-response))
                              (children tree))}
      (text tree :dav/sync-token)
      (assoc :sync-token (text tree :dav/sync-token))

      (text tree :dav/responsedescription)
      (assoc :description (text tree :dav/responsedescription)))))

;; RFC 4918, section 14.20
(defn propfind
  "The Hiccup of a DAV:propfind for the `request`, a map of one of these:

  - :props, the names of the properties to get
  - :propname?, true for the names of all properties
  - :include, the names of live properties that allprop leaves out

  With neither :props nor :propname?, it asks for allprop."
  [{:keys [props propname? include] :as request}]
  (cond
    (seq props) [:dav/propfind {} (into [:dav/prop {}] (map #(vector % {})) props)]
    propname?   [:dav/propfind {} [:dav/propname {}]]
    :else       (cond-> [:dav/propfind {} [:dav/allprop {}]]
                  (seq include)
                  (conj (into [:dav/include {}] (map #(vector % {})) include)))))

;; RFC 4918, sections 9.1 and 17: an element that the server doesn't know
;; is ignored, e.g. litmus's <foobar/> beside <allprop/>
(defn read-propfind
  "The request of the DAV:propfind `tree`, as propfind takes it, with
  :allprop? true for allprop. It's nil when the tree isn't a propfind of
  prop, propname or allprop."
  [tree]
  (when (= :dav/propfind (qh/tag tree))
    (let [names #(mapv qh/tag (children %))]
      (cond
        (child tree :dav/prop)     {:props (names (child tree :dav/prop))}
        (child tree :dav/propname) {:propname? true}
        (child tree :dav/allprop)  (cond-> {:allprop? true}
                                     (child tree :dav/include)
                                     (assoc :include (names (child tree :dav/include))))
        :else                      nil))))

(defn- operation
  "The set or remove element of the PROPPATCH operation `op`."
  [[kind x v :as op]]
  (case kind
    :set    [:dav/set {} [:dav/prop {} (if (and (element? x) (nil? v))
                                         x
                                         (property x v))]]
    :remove [:dav/remove {} [:dav/prop {} [x {}]]]))

;; RFC 4918, section 14.19
(defn propertyupdate
  "The Hiccup of a DAV:propertyupdate for the `ops`, in order: [:set name
  value] or [:set element] to set a property, and [:remove name] to remove
  one."
  [ops]
  (into [:dav/propertyupdate {}] (map operation) ops))

(defn read-propertyupdate
  "The operations of the DAV:propertyupdate `tree`, in document order:
  [:set element] and [:remove name]. It's nil when the tree isn't a
  propertyupdate."
  [tree]
  (when (= :dav/propertyupdate (qh/tag tree))
    (vec (for [instruction (children tree)
               :let  [kind (case (qh/tag instruction)
                             :dav/set    :set
                             :dav/remove :remove
                             nil)]
               :when kind
               prop  (some-> (child instruction :dav/prop) children)]
           (if (= :set kind)
             [:set prop]
             [:remove (qh/tag prop)])))))

(comment
  (parse "<D:prop xmlns:D='DAV:'><x:a xmlns:x='http://x/'>1</x:a></D:prop>")
  (println (emit (multistatus {:responses [{:href   "/a"
                                            :propstats [{:status 200
                                                         :props [[(keyword "http://x/" "a") {} "1"]]}]}]})))
  (clark (keyword "http://x/" "a"))
  (read-propertyupdate (parse "<propertyupdate xmlns='DAV:'><set><prop><a xmlns=''>1</a></prop></set></propertyupdate>"))
  #_.)
