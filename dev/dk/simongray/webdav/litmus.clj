(ns dk.simongray.webdav.litmus
  "The handler with locks on Jetty, for litmus to test, over a memory
  backend or a new directory.

      clojure -M:litmus               ; at http://localhost:8765/dav/
      clojure -M:litmus 9000          ; on another port
      clojure -M:litmus 8765 files    ; over a new temporary directory"
  (:require [dk.simongray.webdav.files :as files]
            [dk.simongray.webdav.memory :as memory]
            [dk.simongray.webdav.server :as server]
            [ring.adapter.jetty :as jetty])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn handler
  "The handler at /dav/ with locks, over a new memory backend, or a new
  temporary directory when `files?`."
  ([]
   (handler false))
  ([files?]
   (server/handler (if files?
                     (files/backend (.toFile (Files/createTempDirectory "litmus" (make-array FileAttribute 0))))
                     (memory/backend))
                   {:root "/dav/" :locks (atom {})})))

(defn -main
  "Serve the handler on localhost at the port `port`, 8765 by default,
  over the `backend`, files or else memory, until the process stops."
  [& [port backend]]
  (let [port (or (some-> port parse-long) 8765)]
    (println (str "Serving http://localhost:" port "/dav/ over " (or backend "memory")))
    (jetty/run-jetty (handler (= "files" backend)) {:host "127.0.0.1" :port port :join? true})))
