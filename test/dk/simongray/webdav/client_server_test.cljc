(ns dk.simongray.webdav.client-server-test
  "The client against the handler, in-process on both platforms: a
  collection of documents that two devices keep in sync, as like-minded
  does, and the other methods."
  (:require #?(:cljs [dk.simongray.webdav.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.bytes :as bytes]
            [dk.simongray.webdav :as webdav]
            [dk.simongray.webdav.test-support :refer [run share through]]))

(defn- step
  "A step that sends the request of `method` to `url` with `opts` by
  `send`, and calls `check` with what the client reads of the answer."
  [send method url opts check]
  (fn []
    (async/then (webdav/call! (webdav/request method url opts) {:send send})
                check)))

(defn scenario
  "The steps of the client against a share at the URL `base`, e.g.
  http://localhost/dav/, through `send`."
  [send base]
  (let [notes   (str base "notes/")
        phone   (str notes "phone.edn")
        laptop  (str notes "laptop%20at%20home.edn")
        etag    (atom nil)]
    [(step send :propfind notes {:depth 1}
           #(is (= {:status 404} %) "a collection that isn't there yet"))
     (step send :mkcol notes {}
           #(is (= 201 (:status %))))
     (step send :put phone {:body "{:v 1}" :content-type "application/edn" :if-none-match "*"}
           #(do (is (= 201 (:status %)))
                (reset! etag (:etag %))))
     (step send :put phone {:body "{:v 1}" :if-none-match "*"}
           #(is (= 412 (:status %)) "another device was first"))
     (step send :put laptop {:body (bytes/of [0 255]) :if-none-match "*"}
           #(is (= 201 (:status %))))
     (fn []
       (async/then (webdav/call! (webdav/request :put phone {:body "{:v 2}" :if-match @etag}) {:send send})
                   (fn [{:keys [status] :as answer}]
                     (is (= 204 status))
                     (async/then (webdav/call! (webdav/request :put phone {:body "{:v 3}" :if-match @etag})
                                               {:send send})
                                 #(do (is (= 412 (:status %)) "the ETag is stale")
                                      (reset! etag (:etag answer)))))))
     (step send :propfind notes {:depth 1 :props [:dav/getetag :dav/resourcetype]}
           (fn [{:keys [resources]}]
             (is (= [{:name "laptop at home.edn"} {:name "phone.edn" :version @etag}]
                    (->> resources
                         (remove :collection?)
                         (sort-by :name)
                         (map (fn [{:keys [name etag]}]
                                (cond-> {:name name}
                                  (= "phone.edn" name) (assoc :version etag)))))))
             (is (= ["notes"] (map :name (filter :collection? resources))))))
     (step send :get phone {}
           #(is (= {:status 200 :body "{:v 2}" :content-type "application/edn"}
                   (select-keys % [:status :body :content-type]))
                "a PUT without a Content-Type keeps the old one"))
     (step send :get laptop {:as :bytes}
           #(is (= [0 255] (map (fn [b] (bit-and b 0xff)) (:body %)))))
     (step send :proppatch phone {:patch [[:set :dav/displayname "Phone"]]}
           #(is (nil? (:failed (first (:resources %))))))
     (step send :propfind phone {:props [:dav/displayname :dav/getcontentlength]}
           #(is (= {:dav/displayname "Phone" :dav/getcontentlength "6"}
                   (:props (first (:resources %))))))
     (step send :copy phone {:destination "copy.edn" :overwrite? false}
           #(is (= 201 (:status %))))
     (step send :copy phone {:destination "copy.edn" :overwrite? false}
           #(is (= 412 (:status %))))
     (step send :move (str notes "copy.edn") {:destination (str base "moved.edn")}
           #(is (= 201 (:status %))))
     (step send :delete (str base "moved.edn") {:if-match "\"stale\""}
           #(is (= 412 (:status %))))
     (step send :delete (str base "moved.edn") {}
           #(is (= 204 (:status %))))
     (step send :options base {}
           #(is (= ["1"] (:dav %))))
     (step send :propfind base {:depth :infinity}
           #(is (= {:status 403 :error [:dav/propfind-finite-depth]} %)))
     (step send :propfind base {:depth 1 :props [:dav/displayname] :minimal? true}
           #(is (= [{} {}] (map :props (:resources %))) "no 404 for a missing property"))]))

(deftest the-client-against-the-handler
  (apply run (scenario (through (share)) "http://localhost/dav/")))
