(ns ^:no-doc dk.simongray.webdav.impl.copy
  "COPY and MOVE with the functions of a backend that has no :copy! or
  :move! of its own, by RFC 4918, sections 9.8 and 9.9."
  (:require [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webdav.impl.ring :as ring]))

(defn- refuse
  "Throw a refusal with the `status`, for the `message`."
  [status message]
  (throw (ex-info message {:dk.simongray.webdav/status status})))

;; RFC 4918, section 9.8.2: dead properties are copied too
(defn- copy-props!
  "Copy the dead properties of the resource at `from` to the one at `to`
  in the `backend`, when it keeps any."
  [{:keys [props patch-props!] :as backend} from to]
  (when (and props patch-props!)
    (async/then (props from)
                (fn [elements]
                  (let [elements (if (map? elements) (vals elements) elements)]
                    (when (seq elements)
                      (patch-props! to (mapv #(vector :set %) elements))))))))

(defn- copy-one!
  "Copy the resource at `from`, whose map is `resource`, to `to` in the
  `backend`, without its members."
  [{:keys [content put! mkcol!] :as backend} from to resource]
  (async/then
   (if (:collection? resource)
     (if mkcol!
       (mkcol! to)
       (refuse 403 "The backend can't make collections"))
     (if put!
       (async/then (content from)
                   #(put! to (ring/->bytes %)
                          {:content-type (:content-type resource)}))
       (refuse 403 "The backend can't write")))
   (fn [_] (copy-props! backend from to))))

(defn copy-tree!
  "Copy the resource at `from`, whose map is `resource`, to `to` in the
  `backend`, with its members when `depth` is :infinity. It returns the
  status of each destination path that failed, by path, and skips the
  members of a collection that failed, as section 9.8.3 asks."
  [{:keys [children] :as backend} from to resource depth]
  (async/then
   (ring/attempt #(copy-one! backend from to resource))
   (fn [result]
     (cond
       (::ring/refused result)
       {to (:status (::ring/refused result))}

       (and (:collection? resource) (= :infinity depth))
       (async/then (children from)
                   (fn [members]
                     (ring/reduce-then
                      (fn [failed {:keys [name] :as member}]
                        (async/then (copy-tree! backend (conj from name)
                                                (conj to name) member depth)
                                    #(merge failed %)))
                      {}
                      members)))

       :else
       {}))))

(defn move-tree!
  "Move the resource at `from`, whose map is `resource`, to `to` in the
  `backend` by copying it and its members, and deleting it once all of
  them are copied. It returns the status of each path that failed, as
  copy-tree! does, or that the delete left behind."
  [{:keys [delete!] :as backend} from to resource]
  (async/then (copy-tree! backend from to resource :infinity)
              (fn [failed]
                (if (empty? failed)
                  (async/then (delete! from) #(if (map? %) % {}))
                  failed))))
