(ns ^:no-doc dk.simongray.webdav.impl.ring
  "The Ring requests that the handler takes and the responses it gives, on
  both platforms."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.bytes :as bytes])
  #?(:clj (:import [java.io ByteArrayInputStream InputStream])))

(defn lower-case-headers
  "The `headers` of a Ring request with lower-case names, which a servlet
  gives but a request map written by hand may not."
  [headers]
  (persistent! (reduce-kv (fn [m k v] (assoc! m (str/lower-case (name k)) v))
                          (transient {})
                          (or headers {}))))

(defn content-length
  "The Content-Length among the lower-case `headers`, or nil."
  [headers]
  (some->> (get headers "content-length") str str/trim (re-matches #"\d{1,18}")
           parse-long))

(defn body-bytes
  "The bytes of the `body` of a Ring request, which is a string, bytes, a
  stream or nil, or nil when it holds more than `max-bytes`. In
  ClojureScript a stream gives nil too, so a server there reads the body
  first."
  [body max-bytes]
  ;; the most that an array holds on the JVM
  (let [max-bytes (min max-bytes (- 2147483647 9))
        b         (cond
                    (nil? body)    (bytes/encode "")
                    (string? body) (bytes/encode body)
                    :else          #?(:clj  (if (bytes? body)
                                              body
                                              (.readNBytes ^InputStream body
                                                           (int (inc max-bytes))))
                                      :cljs (when (instance? js/Uint8Array body)
                                              body)))]
    (when (and b (<= (bytes/length b) max-bytes))
      b)))

(defn ->bytes
  "The bytes of the `content` of a document as a backend gives it: bytes,
  a string (as UTF-8), nil, or on the JVM a stream, which is read and
  closed."
  [content]
  (cond
    (nil? content)    (bytes/encode "")
    (string? content) (bytes/encode content)
    #?@(:clj [(instance? InputStream content)
              (with-open [in ^InputStream content] (.readAllBytes in))])
    :else             content))

(defn body
  "The `content` of a document as a Ring response body. On the JVM, bytes
  become a stream, since not every adapter takes bytes."
  [content]
  #?(:clj  (if (bytes? content) (ByteArrayInputStream. ^bytes content) content)
     :cljs content))

(defn charset
  "The charset of the Content-Type `media-type`, or nil."
  [media-type]
  (some->> media-type (re-find #"(?i)charset\s*=\s*\"?([^\";\s]+)") second))

(defn text
  "The response of `status` with the plain text `s`, which a browser takes
  as it is rather than sniffing for another type."
  [status s]
  {:status  status
   :headers {"Content-Type"           "text/plain; charset=utf-8"
             "X-Content-Type-Options" "nosniff"}
   :body    s})

(defn status-of
  "The status that the exception `e` or one of its causes carries as
  :dk.simongray.webdav/status, with its :dk.simongray.webdav/error, or nil
  when none does."
  [e]
  (some (fn [e]
          (when-let [status (:dk.simongray.webdav/status (ex-data e))]
            {:status status
             :error  (:dk.simongray.webdav/error (ex-data e))}))
        (take-while some? (iterate ex-cause e))))

(defn attempt
  "The result of calling `f`, or a map of the :status and :error of the
  refusal that it throws (or in ClojureScript rejects with), under the
  key ::refused. Any other exception is thrown on."
  [f]
  #?(:clj  (try
             (async/then (f) identity)
             (catch Exception e
               (if-let [refusal (status-of e)]
                 {::refused refusal}
                 (throw e))))
     :cljs (-> (js/Promise.resolve nil)
               (.then (fn [_] (f)))
               (.catch (fn [e]
                         (if-let [refusal (status-of e)]
                           {::refused refusal}
                           (throw e)))))))

(defn reduce-then
  "The result of reducing `coll` with `f` from `init`, as reduce does, where
  `f` can return a value or (in ClojureScript) a promise, so the steps go
  one at a time."
  [f init coll]
  (reduce (fn [acc x] (async/then acc #(f % x))) init coll))

(defn handler
  "A Ring handler that answers by the function `f` of a request, which can
  return a promise in ClojureScript. It takes the arguments of a
  synchronous and of an asynchronous handler, and in ClojureScript it
  always returns a promise."
  [f]
  (fn
    ([request]
     #?(:clj  (f request)
        :cljs (.then (js/Promise.resolve nil) (fn [_] (f request)))))
    ([request respond raise]
     #?(:clj  (let [{:keys [answer error]} (try
                                             {:answer (f request)}
                                             (catch Throwable e
                                               {:error e}))]
                (if error
                  (raise error)
                  (respond answer)))
        :cljs (-> (js/Promise.resolve nil)
                  (.then (fn [_] (f request)))
                  (.then respond raise))))))
