(ns dk.simongray.webdav.record
  "Record the answers of Nextcloud and Apache's mod_dav to a few requests
  into test/resources/webdav, for the tests to read.

  dev/record.sh starts both servers in containers and runs -main, which
  takes their URLs and Nextcloud's credentials from the environment. The
  tests check what doesn't change from one recording to the next, e.g.
  names and sizes rather than ETags."
  (:require [clojure.java.io :as io]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.webdav :as webdav]))

(def owncloud
  "The alias of ownCloud's namespace, which Nextcloud writes."
  {"http://owncloud.org/ns" :oc})

(defn- send!
  "The response to the request of `method` to `url` with the `opts` of
  webdav/request."
  [method url opts]
  (http/send! (webdav/request method url opts)))

(defn- record!
  "Write the body of the `response` to the fixture `file`, once it has the
  `status` that it should have."
  [file status response]
  (when-not (= status (:status response))
    (throw (ex-info (str file " got " (:status response) ", not " status)
                    {:response response})))
  (spit (io/file "test/resources/webdav" file) (:body response))
  (println "Recorded" file))

(defn nextcloud!
  "Record the answers of the Nextcloud at `url` to the user `username`
  with the `password`: a listing, a PROPPATCH that it refuses, and a PUT
  whose precondition fails."
  [url username password]
  (let [opts  {:credentials {:username username :password password}}
        notes (str url "/remote.php/dav/files/" username "/notes/")
        phone (str notes "phone.edn")]
    (send! :delete notes opts)
    (send! :mkcol notes opts)
    (send! :put phone (assoc opts :body "{:v 1}" :content-type "application/edn"))
    (send! :put (str notes "laptop%20at%20home.edn") (assoc opts :body "{:v 22}"))
    (send! :mkcol (str notes "Archive/") opts)
    (record! "nextcloud-propfind.xml" 207
             (send! :propfind notes
                    (assoc opts
                           :depth 1
                           :namespaces owncloud
                           :props [:dav/getlastmodified :dav/getcontentlength
                                   :dav/resourcetype :dav/getetag
                                   :dav/getcontenttype :oc/fileid
                                   :dav/quota-used-bytes
                                   :dav/quota-available-bytes])))
    (record! "nextcloud-proppatch.xml" 207
             (send! :proppatch phone (assoc opts
                                            :namespaces owncloud
                                            :patch [[:set :dav/getetag "\"x\""]
                                                    [:set :oc/favorite "1"]])))
    (record! "nextcloud-412.xml" 412
             (send! :put phone (assoc opts :body "{:v 2}" :if-match "\"stale\"")))))

(defn apache!
  "Record the answers of the mod_dav share at `url`: an allprop listing, a
  PROPPATCH of a protected and a dead property, a LOCK, and a PROPFIND at
  depth infinity, which it refuses by default."
  [url]
  (let [share (str url "/dav/")
        notes (str share "notes/")]
    (send! :delete notes {})
    (send! :mkcol notes {})
    (send! :put (str notes "report%20one.txt") {:body "Twelve bytes" :content-type "text/plain"})
    (send! :mkcol (str notes "sub/") {})
    (record! "apache-propfind.xml" 207
             (send! :propfind notes {:depth 1}))
    (record! "apache-proppatch.xml" 207
             (send! :proppatch (str notes "report%20one.txt")
                    {:patch [[:set :dav/getetag "\"x\""]
                             [:set (keyword "http://example.com/ns/" "color") "blue"]]}))
    (record! "apache-lock.xml" 200
             (send! :lock (str notes "report%20one.txt")
                    {:scope :exclusive :owner "webdav-clj" :timeout 600}))
    (record! "apache-403.xml" 403
             (send! :propfind notes {:depth :infinity}))))

(defn -main
  "Record the answers of both servers, at the URLs and with the credentials
  in the environment that dev/record.sh sets."
  [& _]
  (nextcloud! (System/getenv "NEXTCLOUD_URL")
              (System/getenv "NEXTCLOUD_USER")
              (System/getenv "NEXTCLOUD_PASSWORD"))
  (apache! (System/getenv "APACHE_URL"))
  (shutdown-agents))
