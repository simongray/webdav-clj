(ns dk.simongray.webdav.litmus
  "The handler over a memory backend on Jetty, for litmus to test.

      clojure -M:litmus         ; at http://localhost:8765/dav/
      clojure -M:litmus 9000    ; on another port"
  (:require [dk.simongray.webdav.memory :as memory]
            [dk.simongray.webdav.server :as server]
            [ring.adapter.jetty :as jetty]))

(defn handler
  "The handler over a new memory backend at /dav/, with locks."
  []
  (server/handler (memory/backend) {:root "/dav/" :locks (atom {})}))

(defn -main
  "Serve the handler on localhost at the port `port`, 8765 by default,
  until the process stops."
  [& [port]]
  (let [port (or (some-> port parse-long) 8765)]
    (println (str "Serving http://localhost:" port "/dav/"))
    (jetty/run-jetty (handler) {:host "127.0.0.1" :port port :join? true})))
