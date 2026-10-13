(ns ^:no-doc dk.simongray.webdav.impl.props
  "The properties of a resource for PROPFIND: the live properties of RFC
  4918, section 15, the live properties of the app, and the dead
  properties of the backend."
  (:require [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webdav.conditions :as conditions]
            [dk.simongray.webdav.impl.dates :as dates]
            [dk.simongray.webdav.impl.locks :as locks]
            [dk.simongray.webdav.impl.ring :as ring]
            [dk.simongray.webdav.xml :as xml]))

(def live-names
  "The live properties of RFC 4918 that the handler computes from a
  resource map, in the order that allprop lists them."
  [:dav/creationdate :dav/getcontentlanguage :dav/getcontentlength
   :dav/getcontenttype :dav/getetag :dav/getlastmodified :dav/resourcetype
   :dav/lockdiscovery :dav/supportedlock])

;; RFC 4918, sections 15.1 to 15.10
(defn live-value
  "The value of the live property `name` of the `resource`, or nil when it
  has none. DAV:resourcetype holds DAV:collection for a collection and an
  empty element for each name in its :resource-type. The locks are there
  when the handler takes them, which gives the resource its ::activelocks."
  [name {:keys [collection? etag last-modified created content-length
                content-type content-language resource-type]
         :as   resource}]
  (case name
    :dav/resourcetype       (vec (concat (when collection? [[:dav/collection {}]])
                                         (map #(vector % {}) resource-type)))
    :dav/getetag            (conditions/etag etag)
    :dav/getlastmodified    (some-> last-modified dates/http-date)
    :dav/creationdate       (some-> created dates/timestamp)
    :dav/getcontentlength   (when (and content-length (not collection?))
                              (str content-length))
    :dav/getcontenttype     content-type
    :dav/getcontentlanguage content-language
    :dav/lockdiscovery      (::activelocks resource)
    :dav/supportedlock      (when (contains? resource ::activelocks)
                              locks/supportedlock)
    nil))

(defn live
  "The elements of the live properties of RFC 4918 that the `resource`
  has, by name. DAV:resourcetype is always there."
  [resource]
  (into {}
        (keep (fn [name]
                (let [v (live-value name resource)]
                  (when (or (some? v) (= :dav/resourcetype name))
                    [name (xml/property name (not-empty v))]))))
        live-names))

(defn app
  "The elements of the app's live properties among `names` that the
  resource at `path` has, by name, given the `live-properties` of the
  handler: a map of name to a function of the `request`, the path and
  the `resource`."
  [live-properties names request path resource]
  (ring/reduce-then (fn [found name]
                      (async/then ((get live-properties name) request path resource)
                                  (fn [v]
                                    (cond-> found
                                      (some? v) (assoc name (xml/property name v))))))
                    {}
                    (filter #(contains? live-properties %) names)))

(defn dead
  "The elements of the dead properties of the resource at `path` by name,
  from the :props of the `backend`, or none when it has no :props."
  [{:keys [props] :as backend} path]
  (if props
    (async/then (props path)
                (fn [elements]
                  (into {} (map (juxt first identity))
                        (if (map? elements) (vals elements) elements))))
    {}))

(defn- named
  "The propstats of the properties `names` among the elements `found`:
  those with a value at 200, and the others at 404."
  [names found]
  (let [present (filterv #(contains? found %) names)
        missing (remove #(contains? found %) names)]
    (cond-> []
      (seq present) (conj {:status 200 :props (mapv found present)})
      (seq missing) (conj {:status 404 :props (mapv #(vector % {}) missing)}))))

(defn propstats
  "The propstats of the resource at `path` for the PROPFIND `request` of
  `ctx`, as xml/read-propfind returns it, given the `resource` map."
  [{:keys [backend opts] :as ctx} request path resource]
  (let [{:keys [props propname? include]} request
        live-properties                   (:live-properties opts)
        live                              (live resource)
        app-names                         (cond
                                            props     props
                                            propname? (keys live-properties)
                                            :else     include)
        dead?                             (or propname?
                                              (:allprop? request)
                                              (some #(not (or (contains? live %)
                                                              (contains? live-properties %)))
                                                    props))]
    (async/then
     (async/all [(app live-properties app-names (:request ctx) path resource)
                 (if dead? (dead backend path) {})])
     (fn [[app dead]]
       (let [found (merge dead app live)]
         (cond
           props
           (named props found)

           propname?
           [{:status 200 :props (mapv #(vector % {}) (keys found))}]

           ;; RFC 4918, section 9.1: allprop is the dead properties, the
           ;; live ones of RFC 4918 and those that :include names
           :else
           (named (distinct (concat (filter #(contains? live %) live-names)
                                    (remove #(contains? live %) (keys dead))
                                    include))
                  found)))))))
