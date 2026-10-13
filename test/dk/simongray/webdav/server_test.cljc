(ns dk.simongray.webdav.server-test
  "The handler over a memory backend, method by method, on both
  platforms."
  (:require #?(:cljs [dk.simongray.webdav.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.wary-fetch.bytes :as bytes]
            [dk.simongray.webdav.memory :as memory]
            [dk.simongray.webdav.server :as server]
            [dk.simongray.webdav.test-support :refer [body-bytes expect ring run share text]]
            [dk.simongray.webdav.xml :as xml]))

(def litmus
  "The namespace of litmus's properties."
  "http://example.com/neon/litmus/")

(defn- propfind-body
  "The XML of a PROPFIND of the properties `names`."
  [& names]
  (xml/emit (xml/propfind {:props names})))

(defn- proppatch-body
  "The XML of a PROPPATCH of the operations `ops`."
  [ops]
  (xml/emit (xml/propertyupdate ops)))

(def caldav
  "The alias of CalDAV's namespace."
  {"urn:ietf:params:xml:ns:caldav" :cal})

(defn- multistatus
  "The multistatus of the 207 `response`, with its responses by href."
  [response]
  (into {} (map (juxt :href identity))
        (:responses (xml/read-multistatus (xml/parse (text (:body response)) caldav)))))

(defn- props
  "The elements of the properties of the `href` in the 207 `response` at
  `status`, by name."
  [response href status]
  (into {} (for [{:keys [props] :as propstat} (:propstats (get (multistatus response) href))
                 :when                        (= status (:status propstat))
                 element                      props]
             [(first element) element])))

(deftest options
  (let [h (share)]
    (run (expect h (ring :options "/dav/")
                 {:status  200
                  :headers {"DAV"           "1"
                            "Allow"         "OPTIONS, GET, HEAD, DELETE, PROPFIND, PROPPATCH, COPY, MOVE"
                            "MS-Author-Via" "DAV"}})
         (expect h (ring :options "/dav/new.txt")
                 {:headers {"Allow" "OPTIONS, PUT, PROPFIND, PROPPATCH, MKCOL"}})
         (expect (share (atom {}) {:compliance ["calendar-access"] :methods {:report (constantly nil)}})
                 (ring :options "/dav/")
                 {:headers {"DAV"   "1, calendar-access"
                            "Allow" "OPTIONS, GET, HEAD, DELETE, PROPFIND, PROPPATCH, COPY, MOVE, REPORT"}}))))

(deftest paths
  (let [h (share)]
    (run (expect h (ring :propfind "/dav" :headers {"Depth" "0"}) {:status 207})
         (expect h (ring :get "/elsewhere") {:status 404})
         (expect h (ring :put "/dav/%2e%2e" :body "x") {:status 400})
         (expect h (ring :put "/dav/x%00.edn" :body "x") {:status 400})
         (expect h (ring :put "/dav/a%2Fb" :body "x") {:status 400}))))

(deftest above-the-root
  (let [h (server/handler (memory/backend) {:root "/a%20b/dav/" :locks (atom {}) :compliance ["x"]})]
    (run (expect h (ring :options "/")
                 {:status 200 :headers {"DAV" "1, 2, x" "Allow" "OPTIONS, PROPFIND"}})
         (expect h (ring :propfind "/" :headers {"Depth" "1"})
                 #(do (is (= 207 (:status %)))
                      (is (= #{"/" "/a%20b/"} (set (keys (multistatus %)))))
                      (is (= [:dav/resourcetype {} [:dav/collection {}]]
                             (:dav/resourcetype (props % "/a%20b/" 200))))))
         (expect h (ring :propfind "/a%20b" :headers {"Depth" "1"})
                 #(is (= #{"/a%20b/" "/a%20b/dav/"} (set (keys (multistatus %))))))
         (expect h (ring :propfind "/a%20b/" :headers {"Depth" "0"} :body (propfind-body :dav/getetag))
                 #(is (= {:dav/getetag [:dav/getetag {}]} (props % "/a%20b/" 404))))
         (expect h (ring :propfind "/" :headers {"Depth" "infinity"}) {:status 403})
         (expect h (ring :get "/") {:status 404})
         (expect h (ring :proppatch "/a%20b/") {:status 404})
         (expect h (ring :options "/b/") {:status 404})
         (expect h (ring :propfind "/a%20b/dav/" :headers {"Depth" "0"}) {:status 207}))))

(deftest putting-and-getting
  (let [a (atom {})
        h (share a)]
    (run (expect h (ring :put "/dav/a%20b.txt" :body "hello" :headers {"Content-Type" "text/plain"})
                 {:status 201 :headers {"ETag" "\"1\""}})
         (expect h (ring :get "/dav/a%20b.txt")
                 {:status  200
                  :headers {"ETag" "\"1\"" "Content-Type" "text/plain"}
                  :body    "hello"})
         (expect h (ring :head "/dav/a%20b.txt")
                 {:status 200 :headers {"Content-Length" "5"} :body ""})
         (expect h (ring :put "/dav/a%20b.txt" :body "again")
                 {:status 204 :headers {"ETag" "\"2\""}})
         (expect h (ring :get "/dav/a%20b.txt")
                 {:headers {"Content-Type" "text/plain"} :body "again"})
         (expect h (ring :put "/dav/b.bin" :body "b") {:status 201})
         (expect h (ring :get "/dav/b.bin")
                 {:headers {"Content-Type" "application/octet-stream"}})
         (expect h (ring :get "/dav/nothing.txt") {:status 404})
         (expect h (ring :put "/dav/") {:status 405})
         (expect h (ring :put "/dav/no/parent.txt" :body "x") {:status 409})
         (expect h (ring :put "/dav/a%20b.txt/c" :body "x") {:status 409})
         (fn []
           (is (= #{[] ["a b.txt"] ["b.bin"]} (set (keys (:resources @a)))))))))

(deftest pages-of-collections
  (let [h (share)]
    (run (expect h (ring :mkcol "/dav/c%20d/") {:status 201})
         (expect h (ring :put "/dav/c%20d/%3Cb%3E'.txt" :body "b") {:status 201})
         (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :get "/dav/")
                 #(let [page (text (:body %))]
                    (is (= 200 (:status %)))
                    (is (= "text/html; charset=utf-8" (get-in % [:headers "Content-Type"])))
                    (is (str/includes? page "<title>/dav/</title>"))
                    (is (str/includes? page (str "<li><a href=\"/dav/a.txt\">a.txt</a></li>\n"
                                                 "<li><a href=\"/dav/c%20d/\">c d/</a></li>")))
                    (is (not (str/includes? page "../")) "the root has no parent")))
         (expect h (ring :get "/dav/c%20d")
                 {:body #"<title>/dav/c d/</title>[\s\S]*<a href=\"/dav/\">../</a>"})
         (expect h (ring :get "/dav/c%20d/")
                 {:body #"<a href=\"/dav/c%20d/%3Cb%3E&#39;.txt\">&lt;b&gt;&#39;.txt</a>"})
         (expect h (ring :head "/dav/")
                 {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body ""})
         (expect h (ring :get "/dav/" :headers {"If-None-Match" "*"}) {:status 304})
         (expect h (ring :get "/dav/" :headers {"If-Match" "*"}) {:status 200})
         (expect h (ring :options "/dav/c%20d/")
                 {:headers {"Allow" "OPTIONS, GET, HEAD, DELETE, PROPFIND, PROPPATCH, COPY, MOVE"}}))))

(deftest bytes-and-limits
  (let [h (share (atom {}) {:max-bytes 8})]
    (run (expect h (ring :put "/dav/b.bin" :body (bytes/of [0 255 1])) {:status 201})
         (expect h (ring :get "/dav/b.bin")
                 #(is (= [0 255 1] (map (fn [b] (bit-and b 0xff)) (body-bytes (:body %))))))
         (expect h (ring :put "/dav/c.txt" :body "123456789") {:status 413})
         (expect h (ring :put "/dav/c.txt" :body "1" :headers {"Content-Length" "9"}) {:status 413})
         (expect h (ring :put "/dav/c.txt" :body "1" :headers {"Content-Range" "bytes 0-0/9"})
                 {:status 400})
         (expect h (ring :get "/dav/c.txt") {:status 404}))))

(deftest ranges
  (let [h (share)]
    (run (expect h (ring :put "/dav/a.txt" :body "0123456789") {:status 201})
         (expect h (ring :head "/dav/a.txt") {:headers {"Accept-Ranges" "bytes"}})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=2-5"})
                 {:status 206 :headers {"Content-Range" "bytes 2-5/10"} :body "2345"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=-3"})
                 {:status 206 :headers {"Content-Range" "bytes 7-9/10"} :body "789"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=8-"}) {:status 206 :body "89"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=10-"})
                 {:status 416 :headers {"Content-Range" "bytes */10"}})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=0-1,4-5"})
                 {:status 200 :body "0123456789"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=2-5" "If-Range" "\"1\""})
                 {:status 206 :body "2345"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=2-5" "If-Range" "\"2\""})
                 {:status 200 :body "0123456789"})
         (expect h (ring :get "/dav/a.txt" :headers {"Range" "bytes=2-5"
                                                     "If-Range" "Mon, 01 Jan 2001 00:00:00 GMT"})
                 {:status 200}))))

(defn- stream
  "A stream of the UTF-8 of the text `s`, in ClojureScript a ReadableStream
  of two chunks."
  [s]
  #?(:clj  (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
     :cljs (let [b (bytes/encode s)
                 n (quot (.-length b) 2)]
             (js/ReadableStream. #js {:start (fn [controller]
                                               (.enqueue controller (.subarray b 0 n))
                                               (.enqueue controller (.subarray b n))
                                               (.close controller))}))))

#?(:cljs
   (defn- iterable
     "An async iterable of the UTF-8 of the text `s`, as a Node stream is."
     [s]
     (let [chunks (atom [(bytes/encode s)])]
       (doto #js {}
         (unchecked-set js/Symbol.asyncIterator
                        (fn []
                          #js {:next (fn []
                                       (js/Promise.resolve
                                        (if-let [chunk (first @chunks)]
                                          (do (swap! chunks rest)
                                              #js {:done false :value chunk})
                                          #js {:done true})))}))))))

(deftest streamed-bodies
  (let [share-atom (atom {})
        h          (share share-atom {:max-bytes 8 :max-xml-bytes 200})]
    (run (expect h (ring :put "/dav/a.txt" :body (stream "12345678")) {:status 201})
         (expect h (ring :put "/dav/a.txt" :body (stream "123456789")) {:status 413})
         (expect h (ring :get "/dav/a.txt") {:body "12345678"})
         #?(:cljs (expect h (ring :put "/dav/b.txt" :body (iterable "1234")) {:status 201}))
         #?(:cljs (expect h (ring :put "/dav/b.txt" :body (iterable "123456789")) {:status 413}))
         #?(:cljs (expect h (ring :get "/dav/b.txt") {:body "1234"}))
         (expect h (ring :propfind "/dav/a.txt" :headers {"Depth" "0"}
                         :body (stream (propfind-body :dav/getcontentlength)))
                 #(is (= [:dav/getcontentlength {} "8"]
                         (:dav/getcontentlength (props % "/dav/a.txt" 200)))))
         (expect h (ring :propfind "/dav/a.txt" :headers {"Depth" "0"}
                         :body (stream (apply str (repeat 201 " "))))
                 {:status 413})
         (fn [] (is (= 1 (count (filter #(= ["a.txt"] %) (keys (:resources @share-atom))))))
                "a body past the limit changes nothing"))))

(deftest conditional-requests
  (let [h (share)]
    (run (expect h (ring :put "/dav/a.txt" :body "1") {:status 201})
         (expect h (ring :put "/dav/a.txt" :body "x" :headers {"If-Match" "\"stale\""}) {:status 412})
         (expect h (ring :put "/dav/a.txt" :body "x" :headers {"If-Match" "W/\"1\""}) {:status 412})
         (expect h (ring :put "/dav/a.txt" :body "x" :headers {"If-None-Match" "*"}) {:status 412})
         (expect h (ring :put "/dav/new.txt" :body "x" :headers {"If-Match" "*"}) {:status 412})
         (expect h (ring :get "/dav/a.txt" :headers {"If-None-Match" "\"1\""})
                 {:status 304 :headers {"ETag" "\"1\""}})
         (expect h (ring :get "/dav/missing.txt" :headers {"If-Match" "\"1\""}) {:status 404})
         (expect h (ring :put "/dav/a.txt" :body "2" :headers {"If-Match" "\"stale\", \"1\""}) {:status 204})
         (testing "the If header"
           (expect h (ring :put "/dav/a.txt" :body "3" :headers {"If" "([\"1\"])"}) {:status 412}))
         (expect h (ring :put "/dav/a.txt" :body "3" :headers {"If" "([\"2\"])"}) {:status 204})
         (expect h (ring :put "/dav/a.txt" :body "4" :headers {"If" "(<urn:uuid:x>) (Not <DAV:no-lock>)"})
                 {:status 204})
         (expect h (ring :put "/dav/a.txt" :body "5" :headers {"If" "(<urn:uuid:x>)"}) {:status 412})
         (expect h (ring :put "/dav/a.txt" :body "5" :headers {"If" "(garbage"}) {:status 400})
         (expect h (ring :put "/dav/b.txt" :body "x" :headers {"If" "</dav/a.txt> ([\"4\"])"})
                 {:status 201})
         (expect h (ring :put "/dav/b.txt" :body "x" :headers {"If" "<http://h/dav/a.txt> ([\"3\"])"})
                 {:status 412}))))

(deftest a-conditional-put-reaches-the-backend
  (let [seen    (atom [])
        backend (update (memory/backend) :put!
                        (fn [put!] (fn [path body opts] (swap! seen conj opts) (put! path body opts))))
        h       (server/handler backend {:root "/dav/"})]
    (run (expect h (ring :put "/dav/a.txt" :body "1" :headers {"If-None-Match" "*"}) {:status 201})
         (expect h (ring :put "/dav/a.txt" :body "2" :headers {"If-Match" "\"1\""}) {:status 204})
         (expect h (ring :put "/dav/a.txt" :body "3") {:status 204})
         (fn []
           (is (= [{:content-type nil :if-etag nil}
                   {:content-type nil :if-etag "\"1\""}
                   {:content-type nil}]
                  @seen))))))

(deftest deleting
  (let [h (share)]
    (run (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :put "/dav/c/a.txt" :body "a") {:status 201})
         (expect h (ring :delete "/dav/c/" :headers {"Depth" "0"}) {:status 400})
         (expect h (ring :delete "/dav/c/" :headers {"If-Match" "\"stale\""}) {:status 412})
         (expect h (ring :delete "/dav/c") {:status 204})
         (expect h (ring :get "/dav/c/a.txt") {:status 404})
         (expect h (ring :delete "/dav/c") {:status 404})
         (expect h (ring :delete "/dav/") {:status 403}))))

(deftest a-version-as-the-etag
  (let [seen    (atom [])
        backend {:resource (fn [path]
                             (if (empty? path) {:collection? true} {:etag 7}))
                 :content  (constantly "x")
                 :put!     (fn [_ _ opts] (swap! seen conj opts) {:etag 8})}
        h       (server/handler backend {:root "/dav/"})]
    (run (expect h (ring :get "/dav/a.txt") {:headers {"ETag" "\"7\""}})
         (expect h (ring :put "/dav/a.txt" :body "y" :headers {"If-Match" "\"7\""})
                 {:status 204 :headers {"ETag" "\"8\""}})
         (fn [] (is (= [{:content-type nil :if-etag 7}] @seen) "the backend's own version")))))

(deftest destinations
  (let [h (share (atom {}) {:hosts ["dav.example.org"]})]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :copy "/dav/a.txt" :headers {"Host"        "localhost:8080"
                                                      "Destination" "http://other.example/dav/b.txt"})
                 {:status 502})
         (expect h (ring :copy "/dav/a.txt" :headers {"Host"        "localhost:8080"
                                                      "Destination" "https://LOCALHOST/dav/b.txt"})
                 {:status 201})
         (expect h (ring :copy "/dav/a.txt" :headers {"Host"             "127.0.0.1:8080"
                                                      "X-Forwarded-Host" "public.example"
                                                      "Destination"      "https://public.example/dav/c.txt"})
                 {:status 201})
         (expect h (ring :copy "/dav/a.txt" :headers {"Host"        "127.0.0.1:8080"
                                                      "Destination" "https://dav.example.org/dav/d.txt"})
                 {:status 201})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "http://[::1/x"}) {:status 400})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "/dav/a.txt/b"}) {:status 409})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "/dav/missing/b.txt"
                                                      "If-Match"    "\"stale\""})
                 {:status 409}))))

(deftest deletes-that-partly-fail
  (let [backend (update (dissoc (memory/backend) :move!) :delete!
                        (fn [delete!]
                          (fn [path]
                            (if (#{["d"] ["src"]} path)
                              {(conj path "keep.txt") 423}
                              (delete! path)))))
        h       (server/handler backend {:root "/dav/"})]
    (run (expect h (ring :mkcol "/dav/d/") {:status 201})
         (expect h (ring :mkcol "/dav/src/") {:status 201})
         (expect h (ring :put "/dav/src/x.txt" :body "x") {:status 201})
         (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/d/"})
                 #(do (is (= 207 (:status %)))
                      (is (str/includes? (text (:body %)) "/dav/d/keep.txt"))))
         (expect h (ring :get "/dav/d/x.txt") {:status 404})
         (expect h (ring :move "/dav/src/" :headers {"Destination" "/dav/e/"})
                 {:status 207}))))

(deftest a-backend-without-content
  (let [h (server/handler (dissoc (memory/backend) :content :move!) {:root "/dav/"})]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :get "/dav/a.txt") {:status 405})
         (expect h (ring :head "/dav/a.txt") {:status 200})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "/dav/b.txt"}) {:status 405})
         (expect h (ring :move "/dav/a.txt" :headers {"Destination" "/dav/b.txt"}) {:status 405}))))

(deftest odd-requests
  (let [h (share (atom {}) {:max-bytes #?(:clj Long/MAX_VALUE :cljs js/Number.MAX_SAFE_INTEGER)})]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :propfind "/dav/a.txt" :body "<foo>" :headers {"If-Match" "\"stale\""})
                 {:status 412})
         (expect h (ring :proppatch "/dav/a.txt" :body "<foo>" :headers {"If-Match" "\"stale\""})
                 {:status 412})
         (expect h (ring :proppatch "/dav/a.txt"
                         :body (str "<propertyupdate xmlns='DAV:'><set><prop>"
                                    "<displayname xmlns='dav'>x</displayname>"
                                    "</prop></set></propertyupdate>"))
                 {:status 400})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "0" "Prefer" "return=\"minimal\""})
                 {:headers {"Preference-Applied" "return=minimal"}})
         (expect h (ring :foo "/dav/") {:status 501})
         (expect h (ring :lock "/dav/") {:status 405}))))

(deftest a-backend-that-cannot-list
  (let [h (server/handler (dissoc (memory/backend) :children :move!) {:root "/dav/"})]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "0"}) {:status 207})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"}) {:status 403})
         (expect h (ring :get "/dav/") {:status 405 :headers {"Allow" "OPTIONS, DELETE, PROPFIND, PROPPATCH, COPY, MOVE"}})
         (expect h (ring :propfind "/dav/a.txt" :headers {"Depth" "1"}) {:status 207})
         (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :copy "/dav/c/" :headers {"Destination" "/dav/d/"}) {:status 403})
         (expect h (ring :copy "/dav/c/" :headers {"Destination" "/dav/d/" "Depth" "0"}) {:status 201}))))

(deftest a-backend-that-cannot-write
  (let [h (server/handler (dissoc (memory/backend) :put! :delete! :mkcol! :move!) {:root "/dav/"})]
    (run (expect h (ring :put "/dav/a.txt" :body "x") {:status 405})
         (expect h (ring :mkcol "/dav/c") {:status 405 :headers {"Allow" "OPTIONS, PROPFIND, PROPPATCH"}})
         (expect h (ring :options "/dav/") {:headers {"Allow" "OPTIONS, GET, HEAD, PROPFIND, PROPPATCH"}})
         (expect h (ring :lock "/dav/") {:status 405}))))

(deftest making-collections
  (let [h (share)]
    (run (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :mkcol "/dav/c/") {:status 405})
         (expect h (ring :mkcol "/dav/x/y/") {:status 409})
         (expect h (ring :mkcol "/dav/b/" :body "afafafaf" :headers {"Content-Type" "xzy-foo/bar-512"})
                 {:status 415})
         (expect (share (atom {}) {:max-xml-bytes 4}) (ring :mkcol "/dav/b/" :body "afafafaf")
                 {:status 415})
         (expect h (ring :put "/dav/c/a.txt" :body "a") {:status 201})
         (expect h (ring :mkcol "/dav/c/a.txt") {:status 405}))))

(defn copying-and-moving
  "The steps that copy and move over the handler `h`."
  [h]
  [(expect h (ring :mkcol "/dav/src/") {:status 201})
   (expect h (ring :put "/dav/src/a.txt" :body "a") {:status 201})
   (expect h (ring :mkcol "/dav/src/sub/") {:status 201})
   (expect h (ring :put "/dav/src/sub/b.txt" :body "b") {:status 201})
   (expect h (ring :proppatch "/dav/src/a.txt" :body (proppatch-body [[:set (keyword litmus "p") "v"]]))
           {:status 207})
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "http://localhost/dav/a2.txt"})
           {:status 201})
   (expect h (ring :get "/dav/a2.txt") {:body "a"})
   (expect h (ring :propfind "/dav/a2.txt" :headers {"Depth" "0"} :body (propfind-body (keyword litmus "p")))
           #(is (= [(keyword litmus "p") {} "v"]
                   (get (props % "/dav/a2.txt" 200) (keyword litmus "p")))
                "the dead properties come along"))
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "/dav/a2.txt" "Overwrite" "F"})
           {:status 412})
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "/dav/a2.txt"}) {:status 204})
   (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/shallow/" "Depth" "0"}) {:status 201})
   (expect h (ring :get "/dav/shallow/a.txt") {:status 404})
   (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/deep/"}) {:status 201})
   (expect h (ring :get "/dav/deep/sub/b.txt") {:body "b"})
   (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/deep/" "Depth" "1"}) {:status 400})
   (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/src/sub/x/"}) {:status 403})
   (expect h (ring :copy "/dav/src/" :headers {"Destination" "/dav/src/"}) {:status 403})
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "/elsewhere/a.txt"}) {:status 502})
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "/dav/no/a.txt"}) {:status 409})
   (expect h (ring :copy "/dav/src/a.txt") {:status 400})
   (expect h (ring :copy "/dav/src/a.txt" :headers {"Destination" "/dav/b.txt" "Overwrite" "maybe"})
           {:status 400})
   (expect h (ring :move "/dav/src/" :headers {"Destination" "/dav/moved/" "Depth" "0"}) {:status 400})
   (expect h (ring :move "/dav/src/" :headers {"Destination" "/dav/moved/"}) {:status 201})
   (expect h (ring :get "/dav/src/a.txt") {:status 404})
   (expect h (ring :get "/dav/moved/sub/b.txt") {:body "b"})
   (expect h (ring :propfind "/dav/moved/a.txt" :headers {"Depth" "0"} :body (propfind-body (keyword litmus "p")))
           #(is (= [(keyword litmus "p") {} "v"]
                   (get (props % "/dav/moved/a.txt" 200) (keyword litmus "p")))))
   (expect h (ring :move "/dav/a2.txt" :headers {"Destination" "/dav/deep/"}) {:status 204})
   (expect h (ring :get "/dav/deep") {:status 200 :body "a"})])

(deftest copying-and-moving-in-memory
  (apply run (copying-and-moving (share))))

(deftest copying-and-moving-by-the-handler
  (apply run (copying-and-moving (server/handler (dissoc (memory/backend) :move!) {:root "/dav/"}))))

(deftest a-backend-that-copies-itself
  (let [copied  (atom nil)
        backend (assoc (memory/backend) :copy! (fn [from to opts] (reset! copied [from to opts]) nil))
        h       (server/handler backend {:root "/dav/"})]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "/dav/b.txt"}) {:status 201})
         (fn [] (is (= [["a.txt"] ["b.txt"] {:depth :infinity}] @copied))))))

(deftest a-copy-that-partly-fails
  (let [backend (update (dissoc (memory/backend) :move!) :put!
                        (fn [put!]
                          (fn [path body opts]
                            (if (= ["copy" "bad.txt"] path)
                              (throw (ex-info "No room" {:dk.simongray.webdav/status 507}))
                              (put! path body opts)))))
        h       (server/handler backend {:root "/dav/"})]
    (run (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :put "/dav/c/bad.txt" :body "b") {:status 201})
         (expect h (ring :put "/dav/c/good.txt" :body "g") {:status 201})
         (expect h (ring :copy "/dav/c/" :headers {"Destination" "/dav/copy/"})
                 #(do (is (= 207 (:status %)))
                      (is (= {"/dav/copy/bad.txt" {:href "/dav/copy/bad.txt" :status 507}}
                             (multistatus %)))))
         (expect h (ring :get "/dav/copy/good.txt") {:body "g"})
         (expect h (ring :move "/dav/c/" :headers {"Destination" "/dav/copy/"})
                 {:status 207})
         (expect h (ring :get "/dav/c/good.txt") {:body "g"}))))

(deftest propfind
  (let [h (share)]
    (run (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :put "/dav/c/a%20b.txt" :body "hello" :headers {"Content-Type" "text/plain"})
                 {:status 201})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"})
                 #(is (= ["/dav/" "/dav/c/"] (sort (keys (multistatus %))))))
         (expect h (ring :propfind "/dav/c" :headers {"Depth" "1"})
                 #(let [file (props % "/dav/c/a%20b.txt" 200)]
                    (is (= 207 (:status %)))
                    (is (= "application/xml; charset=utf-8" (get-in % [:headers "Content-Type"])))
                    (is (= [:dav/getcontentlength {} "5"] (:dav/getcontentlength file)))
                    (is (= [:dav/getcontenttype {} "text/plain"] (:dav/getcontenttype file)))
                    (is (= [:dav/resourcetype {}] (:dav/resourcetype file)))
                    (is (= [:dav/resourcetype {} [:dav/collection {}]]
                           (:dav/resourcetype (props % "/dav/c/" 200))))))
         (expect h (ring :propfind "/dav/c/" :headers {"Depth" "0"})
                 #(is (= ["/dav/c/"] (keys (multistatus %)))))
         (expect h (ring :propfind "/dav/" :headers {"Depth" "infinity"})
                 {:status 403 :body #"propfind-finite-depth"})
         (expect h (ring :propfind "/dav/") {:status 403})
         (expect h (ring :propfind "/dav/c/a%20b.txt") {:status 207})
         (expect (share (atom {}) {:propfind-infinity? true}) (ring :propfind "/dav/") {:status 207})
         (expect h (ring :propfind "/dav/c/a%20b.txt" :headers {"Depth" "0"}
                         :body (propfind-body :dav/getetag (keyword litmus "missing")))
                 #(do (is (= "\"1\"" (xml/value (:dav/getetag (props % "/dav/c/a%20b.txt" 200)))))
                      (is (contains? (props % "/dav/c/a%20b.txt" 404) (keyword litmus "missing")))))
         (expect h (ring :propfind "/dav/c/a%20b.txt" :headers {"Depth" "0"}
                         :body (xml/emit (xml/propfind {:propname? true})))
                 #(is (= #{:dav/creationdate :dav/getcontentlength :dav/getcontenttype :dav/getetag
                           :dav/getlastmodified :dav/resourcetype}
                         (set (keys (props % "/dav/c/a%20b.txt" 200))))))
         (expect h (ring :propfind "/dav/c/" :headers {"Depth" "0"} :body "<foo>") {:status 400})
         (expect h (ring :propfind "/dav/c/" :headers {"Depth" "0"} :body "<prop xmlns='DAV:'/>")
                 {:status 400})
         (expect h (ring :propfind "/dav/c/" :headers {"Depth" "2"}) {:status 400})
         (expect h (ring :propfind "/dav/none" :headers {"Depth" "0"}) {:status 404}))))

(deftest prefer
  (let [h (share)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1" "Prefer" "return=minimal, depth-noroot"}
                         :body (propfind-body :dav/getetag :dav/displayname))
                 #(do (is (= "return=minimal, depth-noroot" (get-in % [:headers "Preference-Applied"])))
                      (is (= ["/dav/a.txt"] (keys (multistatus %))))
                      (is (= [200] (map :status (:propstats (get (multistatus %) "/dav/a.txt")))))))
         (expect h (ring :propfind "/dav/" :headers {"Depth" "0" "Prefer" "return=minimal"}
                         :body (propfind-body :dav/displayname))
                 #(is (= [{:status 200 :props []}] (:propstats (get (multistatus %) "/dav/")))))
         (expect h (ring :proppatch "/dav/a.txt" :headers {"Prefer" "return=minimal"}
                         :body (proppatch-body [[:set :dav/displayname "A"]]))
                 {:status 204}))))

(deftest live-and-dead-properties
  (let [backend (update (memory/backend) :resource
                        (fn [resource]
                          (fn [path]
                            (some-> (resource path)
                                    (cond-> (= ["cal"] path) (assoc :resource-type [:cal/calendar]))))))
        h       (server/handler backend
                                {:root            "/dav/"
                                 :namespaces      {"urn:ietf:params:xml:ns:caldav" :cal}
                                 :live-properties {:dav/current-user-principal
                                                   (fn [request _ _]
                                                     [:dav/href {} (str "/principals/" (:user request))])}})]
    (run (expect h (ring :mkcol "/dav/cal/") {:status 201})
         (expect h (assoc (ring :propfind "/dav/cal/" :headers {"Depth" "0"}
                                :body (propfind-body :dav/resourcetype :dav/current-user-principal))
                          :user "simon")
                 #(let [found (props % "/dav/cal/" 200)]
                    (is (= [:dav/resourcetype {} [:dav/collection {}] [:cal/calendar {}]]
                           (:dav/resourcetype found)))
                    (is (= [:dav/current-user-principal {} [:dav/href {} "/principals/simon"]]
                           (:dav/current-user-principal found)))
                    (is (str/includes? (text (:body %)) "xmlns:cal=\"urn:ietf:params:xml:ns:caldav\""))))
         (expect h (ring :propfind "/dav/cal/" :headers {"Depth" "0"})
                 #(is (not (contains? (props % "/dav/cal/" 200) :dav/current-user-principal))
                      "allprop leaves out the app's live properties"))
         (expect h (ring :proppatch "/dav/cal/"
                         :body (proppatch-body [[:set :dav/current-user-principal "x"]
                                                [:set :dav/displayname "Cal"]]))
                 #(let [{:keys [propstats]} (get (multistatus %) "/dav/cal/")]
                    (is (= #{[403 [:dav/current-user-principal]] [424 [:dav/displayname]]}
                           (set (map (juxt :status (comp (partial map first) :props)) propstats))))
                    (is (= [[:dav/cannot-modify-protected-property {}]]
                           (:error (first (filter (comp #{403} :status) propstats))))))))))

(deftest proppatch
  (let [h (share)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :proppatch "/dav/a.txt"
                         :body (proppatch-body [[:set :dav/displayname "A"]
                                                [:set [(keyword litmus "x") {:xml/lang "da"} "y"]]
                                                [:set (keyword litmus "gone") "z"]
                                                [:remove (keyword litmus "gone")]]))
                 #(is (= [200] (map :status (:propstats (get (multistatus %) "/dav/a.txt"))))))
         (expect h (ring :propfind "/dav/a.txt" :headers {"Depth" "0"})
                 #(let [found (props % "/dav/a.txt" 200)]
                    (is (= [:dav/displayname {} "A"] (:dav/displayname found)))
                    (is (= [(keyword litmus "x") {:xml/lang "da"} "y"] (get found (keyword litmus "x"))))
                    (is (not (contains? found (keyword litmus "gone"))))))
         (expect h (ring :proppatch "/dav/a.txt" :body (proppatch-body [[:set :dav/getetag "\"x\""]]))
                 #(is (= [403] (map :status (:propstats (get (multistatus %) "/dav/a.txt"))))))
         (expect h (ring :proppatch "/dav/a.txt" :body "<propfind xmlns='DAV:'/>") {:status 400})
         (expect h (ring :proppatch "/dav/a.txt") {:status 400})
         (expect h (ring :proppatch "/dav/none" :body (proppatch-body [[:remove :dav/x]])) {:status 404})
         (expect (server/handler (dissoc (memory/backend) :patch-props!) {:root "/dav/"})
                 (ring :proppatch "/dav/" :body (proppatch-body [[:set :dav/displayname "A"]]))
                 #(is (= [403] (map :status (:propstats (get (multistatus %) "/dav/")))))))))

(deftest refusals
  (let [backend (assoc (memory/backend)
                       :mkcol! (fn [_]
                                 (throw (ex-info "Full" {:dk.simongray.webdav/status 507})))
                       :put!   (fn [_ _ _]
                                 (throw (ex-info "Bad" {:dk.simongray.webdav/status 403
                                                        :dk.simongray.webdav/error  [:cal/valid-calendar-data]}))))
        h       (server/handler backend {:root "/dav/" :namespaces {"urn:ietf:params:xml:ns:caldav" :cal}})]
    (run (expect h (ring :mkcol "/dav/c/") {:status 507})
         (expect h (ring :put "/dav/a.ics" :body "x")
                 #(do (is (= 403 (:status %)))
                      (is (= [:dav/error {} [:cal/valid-calendar-data {}]]
                             (xml/parse (text (:body %)) {"urn:ietf:params:xml:ns:caldav" :cal}))))))))

(deftest methods-of-your-own
  (let [h (share (atom {}) {:methods {:report (fn [{:keys [path resource]}]
                                                {:status 200 :headers {} :body (pr-str [path (:collection? resource)])})}})]
    (run (expect h (ring :report "/dav/") {:status 200 :body "[[] true]"})
         (expect h (ring :lock "/dav/") {:status 405}))))

#?(:clj
   (deftest the-asynchronous-arity
     (let [h       (share)
           answer  (promise)]
       (h (ring :options "/dav/") #(deliver answer %) #(deliver answer %))
       (is (= 200 (:status @answer))))))
