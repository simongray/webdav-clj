(ns ^:no-doc dk.simongray.webdav.impl.ring
  "The Ring requests that the handler takes and the responses it gives, on
  both platforms."
  (:require [clojure.string :as str]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.bytes :as bytes])
  #?(:clj (:import [java.io ByteArrayInputStream FilterInputStream InputStream]
                   [java.util Arrays])))

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

#?(:clj
   (defn limited
     "The stream `in`, which throws a refusal of 413 once more than
     `max-bytes` have been read from it."
     ^InputStream [^InputStream in max-bytes]
     (let [total   (volatile! 0)
           counted (fn [n]
                     (when (and (pos? n) (> (vswap! total + n) max-bytes))
                       (throw (ex-info (str "The body holds more than " max-bytes " bytes")
                                       {:dk.simongray.webdav/status 413})))
                     n)]
       (proxy [FilterInputStream] [in]
         (read
           ([]
            (let [b (proxy-super read)]
              (counted (if (neg? b) 0 1))
              b))
           ([bs]
            (.read ^InputStream this ^bytes bs 0 (alength ^bytes bs)))
           ([bs off len]
            (counted (proxy-super read bs off len))))))))

#?(:clj
   (defn- bounded
     "The stream `in`, which ends after `n` bytes."
     ^InputStream [^InputStream in n]
     (let [left (volatile! n)]
       (proxy [FilterInputStream] [in]
         (read
           ([]
            (if (pos? @left)
              (let [b (proxy-super read)]
                (when-not (neg? b) (vswap! left dec))
                b)
              -1))
           ([bs]
            (.read ^InputStream this ^bytes bs 0 (alength ^bytes bs)))
           ([bs off len]
            (if (pos? @left)
              (let [k (proxy-super read bs off (int (min len @left)))]
                (when (pos? k) (vswap! left - k))
                k)
              -1)))))))

#?(:clj
   (defn- skip!
     "Skip `n` bytes of the stream `in`, or as many as it has."
     [^InputStream in n]
     (loop [left n]
       (when (pos? left)
         (let [k (.skip in left)]
           (cond
             (pos? k)              (recur (- left k))
             (not (neg? (.read in))) (recur (dec left))))))))

(defn put-body
  "The `content` of a document as :put! takes it: on the JVM a stream, and
  in ClojureScript bytes."
  [content]
  #?(:clj  (if (instance? InputStream content)
             content
             (ByteArrayInputStream. ^bytes (->bytes content)))
     :cljs (->bytes content)))

(defn request-body
  "The `body` of a Ring request as :put! takes it, or nil when it's known
  to hold more than `max-bytes` already. On the JVM a stream stays a
  stream, which throws a refusal of 413 once more than `max-bytes` are
  read from it, so that a backend can write it as it comes."
  [body max-bytes]
  #?(:clj  (if (instance? InputStream body)
             (limited body max-bytes)
             (some-> (body-bytes body max-bytes) put-body))
     :cljs (body-bytes body max-bytes)))

(defn slice
  "The bytes from `start` to `end`, both included, of the `content` of a
  document as a response body. A stream skips to `start`, e.g. a file's
  without reading what it skips, and ends after `end`."
  [content start end]
  #?(:clj  (if (instance? InputStream content)
             (bounded (doto ^InputStream content (skip! start)) (inc (- end start)))
             (Arrays/copyOfRange ^bytes (->bytes content) (int start) (int (inc end))))
     :cljs (.subarray (->bytes content) start (inc end))))

;; RFC 9110, sections 14.1.1 and 14.1.2
(defn byte-range
  "The first and the last byte that the Range header value `s` asks for of
  a document of `length` bytes, or :unsatisfiable when the document has
  none of them. It's nil, for the whole document, when there's no header,
  no length, or a header of anything but one range of bytes."
  [s length]
  (when-let [[_ a b] (some->> s str/trim
                              (re-matches #"(?i)bytes\s*=\s*(\d{0,18})\s*-\s*(\d{0,18})"))]
    (let [first-byte (some-> (not-empty a) parse-long)
          last-byte  (some-> (not-empty b) parse-long)]
      (when (and length
                 (or first-byte last-byte)
                 (not (and first-byte last-byte (> first-byte last-byte))))
        (let [start (or first-byte (max 0 (- length last-byte)))
              end   (if first-byte
                      (min (dec length) (or last-byte (dec length)))
                      (dec length))]
          (if (or (>= start length) (> start end))
            :unsatisfiable
            [start end]))))))

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
