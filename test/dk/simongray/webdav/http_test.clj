(ns dk.simongray.webdav.http-test
  "The client against the handler over HTTP on localhost, with the JDK's
  own server, which Java 11 has, and wary-fetch as the client."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest]]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.webdav.client-server-test :refer [scenario]]
            [dk.simongray.webdav.test-support :refer [run share]])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io InputStream]
           [java.net InetSocketAddress]))

(defn- ring-request
  "The Ring request of the `exchange`, with the raw path as its :uri."
  [^HttpExchange exchange]
  {:request-method (keyword (str/lower-case (.getRequestMethod exchange)))
   :uri            (.getRawPath (.getRequestURI exchange))
   :headers        (into {} (for [[k vs] (.getRequestHeaders exchange)]
                              [(str/lower-case k) (str/join "," vs)]))
   :body           (.getRequestBody exchange)})

(defn- body-bytes
  "The bytes of a Ring response `body`."
  ^bytes [body]
  (cond
    (nil? body)                 (byte-array 0)
    (string? body)              (.getBytes ^String body "UTF-8")
    (instance? InputStream body) (.readAllBytes ^InputStream body)
    :else                       body))

(defn- respond!
  "Send the Ring `response` over the `exchange`, without a body for a HEAD
  or a status that has none."
  [^HttpExchange exchange {:keys [status headers body]}]
  (doseq [[k v] headers]
    (.add (.getResponseHeaders exchange) k (str v)))
  (let [b      (body-bytes body)
        empty? (or (= "HEAD" (.getRequestMethod exchange)) (#{204 304} status))]
    (.sendResponseHeaders exchange status (if empty? -1 (if (zero? (alength b)) -1 (alength b))))
    (when-not (or empty? (zero? (alength b)))
      (with-open [out (.getResponseBody exchange)]
        (.write out b))))
  (.close exchange))

(defn- with-server
  "Call `f` with the URL of a server on localhost that answers by the Ring
  `handler`."
  [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/" (reify HttpHandler
                                 (handle [_ exchange]
                                   (respond! exchange (handler (ring-request exchange))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally
        (.stop server 0)))))

(deftest the-client-against-the-handler-over-http
  (with-server (share)
    (fn [origin]
      (apply run (scenario http/send! (str origin "/dav/"))))))
