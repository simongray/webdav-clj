(ns dk.simongray.webdav.memory
  "A backend that keeps a share in an atom, for tests, examples and
  litmus. It holds every body in memory, so it's no backend for a share
  that others write to.

  The atom holds a map of :resources, from path to a map of the resource
  with its :body as bytes and its dead :props by name, and the last
  number that an ETag was made of as :etags."
  (:require [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.bytes :as bytes]
            [dk.simongray.webdav.impl.ring :as ring]
            [dk.simongray.webdav.paths :as paths]))

(defn- now
  "The instant now."
  []
  #?(:clj  (java.util.Date.)
     :cljs (js/Date.)))

(defn- resource
  "The resource map of the `node` of a share, without its body and its
  dead properties."
  [{:keys [body collection?] :as node}]
  (cond-> (dissoc node :body :props)
    (not collection?) (assoc :content-length (bytes/length body))))

(defn- refuse
  "Throw a refusal with the `status`, for the `message`."
  [status message]
  (throw (ex-info message {:dk.simongray.webdav/status status})))

(defn- written
  "The `state` with the document at `path` replaced by the bytes `body` of
  the `content-type`, or of its old one when that's nil, at the instant
  `t`. A conditional write with :if-etag in `opts` refuses a document
  whose ETag has changed since."
  [state path body {:keys [content-type if-etag] :as opts} t]
  (let [current (get-in state [:resources path])
        n       (inc (:etags state 0))]
    (when (and (contains? opts :if-etag) (not= if-etag (:etag current)))
      (refuse 412 "The document changed"))
    (-> state
        (assoc :etags n)
        (assoc-in [:resources path]
                  {:body          body
                   :content-type  (or content-type (:content-type current))
                   :etag          (str "\"" n "\"")
                   :created       (or (:created current) t)
                   :last-modified t
                   :props         (:props current {})}))))

(defn- without
  "The `resources` without the one at `path` and its members."
  [resources path]
  (into {} (remove #(paths/within? path (key %))) resources))

(defn- moved
  "The `resources` with the one at `from` and its members moved to `to`."
  [resources from to]
  (into (without resources from)
        (for [[p node] resources
              :when (paths/within? from p)]
          [(into (vec to) (drop (count from) p)) node])))

(defn- patched
  "The dead properties `props` after the PROPPATCH operations `ops`."
  [props ops]
  (reduce (fn [m [kind x]]
            (case kind
              :set    (assoc m (first x) x)
              :remove (dissoc m x)))
          props
          ops))

(defn backend
  "A backend over the atom `a`, or a new one, with a root collection, and
  the `opts` below:

  - :clock, a function of no arguments that returns the instant now

  It has every function of a backend but :copy!, so a COPY goes through
  the handler's own copying."
  ([]
   (backend (atom {})))
  ([a]
   (backend a {}))
  ([a {:keys [clock] :or {clock now} :as opts}]
   (swap! a (fn [state]
              (update state :resources
                      #(merge {[] {:collection? true :created (clock) :props {}}} %))))
   {:resource     (fn [path]
                    (some-> (get-in @a [:resources path]) resource))
    :children     (fn [path]
                    (->> (:resources @a)
                         (filter (fn [[p _]] (and (seq p) (= path (pop p)))))
                         (sort-by key)
                         (mapv (fn [[p node]] (assoc (resource node) :name (peek p))))))
    :content      (fn [path]
                    (get-in @a [:resources path :body]))
    :put!         (fn [path body opts]
                    ;; the whole body first, which can fail past a limit
                    (async/then (ring/read-all body)
                                (fn [b]
                                  (-> (swap! a written path b opts (clock))
                                      (get-in [:resources path])
                                      resource))))
    :mkcol!       (fn [path]
                    (let [t (clock)]
                      (swap! a assoc-in [:resources path]
                             {:collection? true :created t :last-modified t :props {}})
                      nil))
    :delete!      (fn [path]
                    (swap! a update :resources without path)
                    nil)
    :move!        (fn [from to]
                    (swap! a update :resources moved from to)
                    nil)
    :props        (fn [path]
                    (vals (get-in @a [:resources path :props])))
    ;; the resource may have gone since the handler found it
    :patch-props! (fn [path ops]
                    (swap! a (fn [state]
                               (cond-> state
                                 (get-in state [:resources path])
                                 (update-in [:resources path :props] patched ops))))
                    nil)}))

(comment
  (def share (atom {}))
  (def b (backend share))
  ((:put! b) ["a.txt"] (bytes/encode "hello") {:content-type "text/plain"})
  ((:children b) [])
  #_.)
