(ns dk.simongray.webdav.test-support
  "What the tests share: fixtures inlined from the classpath, a test of
  steps that give promises in ClojureScript, Ring requests, and the
  handler standing in for the HTTP client."
  #?(:cljs (:require-macros [dk.simongray.webdav.test-support]))
  (:require #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.test :refer [is #?(:cljs async)]]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webdav.memory :as memory]
            [dk.simongray.webdav.server :as server]
            [dk.simongray.whatwg-url :as url]))

#?(:clj
   (defmacro fixture
     "The classpath resource at `path`, a literal, inlined as a string when
     the test compiles, on both platforms."
     [path]
     (slurp (io/resource path))))

(defn run
  "Run the steps `fs` one after another as a test on either platform. Each
  step is a function of no arguments that gives a value or a promise.

  In ClojureScript, a deftest runs only the async test that it returns. So
  a deftest calls `run` once, as its last form, with all its steps."
  [& fs]
  #?(:clj  (doseq [f fs] (f))
     :cljs (async done
             (-> (reduce (fn [p f] (.then p (fn [_] (f)))) (js/Promise.resolve nil) fs)
                 (.catch (fn [e] (is false (str "rejected: " e))))
                 (.finally done)))))

(defn ring
  "A Ring request of `method` for the `uri`, with the `headers` and the
  `body` given."
  [method uri & {:keys [headers body]}]
  {:request-method method :uri uri :headers (or headers {}) :body body})

(defn text
  "The text of a response `body`: a string, bytes, nil or on the JVM a
  stream."
  [body]
  (cond
    (nil? body)    ""
    (string? body) body
    #?@(:clj [(instance? java.io.InputStream body) (slurp body)])
    :else          (bytes/decode body)))

(defn body-bytes
  "The bytes of a response `body`: a string, bytes, nil or on the JVM a
  stream."
  [body]
  (cond
    (nil? body)    (bytes/encode "")
    (string? body) (bytes/encode body)
    #?@(:clj [(instance? java.io.InputStream body) (.readAllBytes ^java.io.InputStream body)])
    :else          body))

(defn- matches?
  "Whether the `response` has what `expected` says: each key's value, the
  headers among its :headers, and a :body as text or a pattern."
  [response expected]
  (every? (fn [[k v]]
            (case k
              :headers (= v (select-keys (:headers response) (keys v)))
              :body    (if (string? v)
                         (= v (text (:body response)))
                         (boolean (re-find v (text (:body response)))))
              (= v (get response k))))
          expected))

(defn expect
  "A step that sends the Ring `request` to the `handler` and checks the
  response against `expected`: a map of what it has (see matches?) or a
  function of the response, which makes its own assertions. The request
  can be a function of no arguments that returns it when the step runs,
  e.g. with a lock token from an earlier step."
  [handler request expected]
  (fn []
    (let [request (if (fn? request) (request) request)]
      (async/then (handler request)
                  ;; a stream can be read once, so it's read here
                  (fn [{:keys [body] :as streamed}]
                    (let [response (cond-> streamed
                                     #?(:clj (instance? java.io.InputStream body) :cljs false)
                                     (assoc :body (body-bytes body)))]
                      (if (fn? expected)
                        (expected response)
                        (is (matches? response expected)
                            (str (name (:request-method request)) " " (:uri request)
                                 " gave " (pr-str (update response :body text)))))))))))

(defn share
  "A handler at /dav/ over a new memory backend in the atom `a`, with the
  `opts` of the handler."
  ([]
   (share (atom {})))
  ([a]
   (share a {}))
  ([a opts]
   (server/handler (memory/backend a) (merge {:root "/dav/"} opts))))

(defn through
  "The function that sends a client's request map to the Ring `handler`
  and gives its response as wary-fetch does, with lower-case headers and
  the body as text, or bytes for :as :bytes. A :range becomes a Range
  header."
  [handler]
  (fn [{:keys [method url as range] :as request}]
    (let [{:keys [headers body]} (http/revealed request)]
      (async/then (handler {:request-method method
                            :uri            (url/pathname url)
                            :headers        (cond-> (into {} (map (fn [[k v]] [(str/lower-case k) v])) headers)
                                              range (assoc "range" (str "bytes=" (first range) "-" (second range))))
                            :body           body})
                  (fn [response]
                    (-> response
                        (update :headers #(into {} (map (fn [[k v]] [(str/lower-case k) v])) %))
                        (update :body #(if (= :bytes as) (body-bytes %) (text %)))))))))
