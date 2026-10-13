(ns dk.simongray.webdav.locks-test
  "Class 2: the handler's locks of documents and collections, shared and
  exclusive, who may use them and for how long, on both platforms."
  (:require #?(:cljs [dk.simongray.webdav.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.webdav.test-support :refer [expect ring run share text]]
            [dk.simongray.webdav.xml :as xml]))

(defn- lockinfo
  "The XML of a DAV:lockinfo of a write lock of `scope`, :exclusive or
  :shared, owned by `owner`."
  [scope owner]
  (str "<D:lockinfo xmlns:D='DAV:'><D:lockscope><D:" (name scope) "/></D:lockscope>"
       "<D:locktype><D:write/></D:locktype><D:owner>" owner "</D:owner></D:lockinfo>"))

(defn- taken
  "A check of the answer to a LOCK that it has the `status` and the
  DAV:lockdiscovery of a lock, whose token it keeps in the atom `token`."
  [status token]
  (fn [{:keys [headers body] :as response}]
    (is (= status (:status response)) (text body))
    (when-let [[_ t] (some->> (get headers "Lock-Token") (re-matches #"<(.+)>"))]
      (reset! token t))
    (is (str/includes? (text body) "lockdiscovery"))))

(defn- holding
  "A function that returns the Ring request of `method` for `uri` with the
  If header of the token in the atom `token`, and the `headers` and `body`
  given."
  [method uri token & {:keys [headers body]}]
  (fn []
    (ring method uri :headers (assoc headers "If" (str "(<" @token ">)")) :body body)))

(defn- lockdiscovery
  "The DAV:lockdiscovery element of the `href` in the 207 `response`."
  [href response]
  (->> (:responses (xml/read-multistatus (xml/parse (text (:body response)))))
       (some #(when (= href (:href %)) %))
       :propstats
       (mapcat :props)
       (some #(when (= :dav/lockdiscovery (first %)) %))))

(def ask-for-locks
  "A PROPFIND body that asks for the locks of a resource."
  "<D:propfind xmlns:D='DAV:'><D:prop><D:lockdiscovery/><D:supportedlock/></D:prop></D:propfind>")

(deftest locking-a-document
  (let [h     (share (atom {}) {:locks (atom {})})
        token (atom nil)]
    (run (expect h (ring :options "/dav/")
                 {:headers {"DAV"   "1, 2"
                            "Allow" "OPTIONS, GET, HEAD, DELETE, PROPFIND, PROPPATCH, COPY, MOVE, LOCK, UNLOCK"}})
         (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/a.txt" :headers {"Timeout" "Second-600"}
                         :body (lockinfo :exclusive "me"))
                 (taken 200 token))
         (expect h (ring :put "/dav/a.txt" :body "b")
                 {:status 423 :body #"lock-token-submitted.*/dav/a.txt"})
         (expect h (holding :put "/dav/a.txt" token :body "b") {:status 204})
         (expect h (ring :proppatch "/dav/a.txt"
                         :body "<propertyupdate xmlns='DAV:'><set><prop><displayname>A</displayname></prop></set></propertyupdate>")
                 {:status 423})
         (expect h (ring :delete "/dav/a.txt") {:status 423})
         (expect h (ring :move "/dav/a.txt" :headers {"Destination" "/dav/b.txt"}) {:status 423})
         (expect h (ring :copy "/dav/a.txt" :headers {"Destination" "/dav/c.txt"}) {:status 201})
         (expect h (ring :propfind "/dav/c.txt" :headers {"Depth" "0"} :body ask-for-locks)
                 #(is (= [:dav/lockdiscovery {}] (lockdiscovery "/dav/c.txt" %))
                      "a lock doesn't follow a copy"))
         (expect h (ring :propfind "/dav/a.txt" :headers {"Depth" "0"} :body ask-for-locks)
                 #(let [found (str (lockdiscovery "/dav/a.txt" %))]
                    (is (str/includes? found @token))
                    (is (re-find #"Second-(600|59\d)" found) "the time that's left")
                    (is (str/includes? found "[:dav/owner {} \"me\"]"))))
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :exclusive "you"))
                 {:status 423 :body #"no-conflicting-lock"})
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared "you")) {:status 423})
         (expect h (holding :lock "/dav/a.txt" token :headers {"Timeout" "Second-100"})
                 #(is (str/includes? (text (:body %)) "Second-100") "refreshed"))
         (expect h (ring :lock "/dav/a.txt") {:status 400})
         (expect h (ring :lock "/dav/a.txt" :headers {"If" "(<urn:uuid:other>)"}) {:status 412})
         (expect h (ring :unlock "/dav/a.txt" :headers {"Lock-Token" "<urn:uuid:bogus>"})
                 {:status 409 :body #"lock-token-matches-request-uri"})
         (expect h (ring :unlock "/dav/a.txt") {:status 400})
         (expect h #(ring :unlock "/dav/a.txt" :headers {"Lock-Token" (str "<" @token ">")})
                 {:status 204})
         (expect h (ring :put "/dav/a.txt" :body "c") {:status 204}))))

(deftest a-failed-if-header-before-a-missing-token
  (let [h     (share (atom {}) {:locks (atom {})})
        token (atom nil)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :exclusive "me")) (taken 200 token))
         (expect h (ring :put "/dav/a.txt" :body "b" :headers {"If" "(<DAV:no-lock>)"}) {:status 412})
         (expect h #(ring :put "/dav/a.txt" :body "b" :headers {"If" (str "(<" @token "x>) (Not <DAV:no-lock>)")})
                 {:status 423})
         (expect h #(ring :put "/dav/a.txt" :body "b" :headers {"If" (str "(<" @token ">) (Not <DAV:no-lock>)")})
                 {:status 204})
         (expect h #(ring :put "/dav/a.txt" :body "c"
                          :headers {"If" (str "<http://localhost/dav/a.txt> (<" @token ">)")})
                 {:status 204}))))

(deftest locking-a-collection
  (let [h     (share (atom {}) {:locks (atom {})})
        deep  (atom nil)
        flat  (atom nil)]
    (run (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :put "/dav/c/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/c/" :body (lockinfo :exclusive "me")) (taken 200 deep))
         (expect h (ring :put "/dav/c/a.txt" :body "b") {:status 423})
         (expect h (ring :put "/dav/c/new.txt" :body "b") {:status 423})
         (expect h (ring :mkcol "/dav/c/d/") {:status 423})
         (expect h (ring :delete "/dav/c/a.txt") {:status 423})
         (expect h (holding :put "/dav/c/new.txt" deep :body "b") {:status 201})
         (expect h (ring :propfind "/dav/c/a.txt" :headers {"Depth" "0"} :body ask-for-locks)
                 #(is (str/includes? (str (lockdiscovery "/dav/c/a.txt" %)) "/dav/c/")
                      "a member lists the lock of its collection"))
         (expect h (holding :lock "/dav/c/a.txt" deep) {:status 200})
         (expect h (holding :delete "/dav/c/" deep) {:status 204})
         (expect h (ring :mkcol "/dav/c/") {:status 201})
         (expect h (ring :put "/dav/c/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/c/" :headers {"Depth" "0"} :body (lockinfo :exclusive "me"))
                 (taken 200 flat))
         (expect h (ring :put "/dav/c/a.txt" :body "b") {:status 204})
         (expect h (ring :put "/dav/c/new.txt" :body "b") {:status 423})
         (expect h (ring :lock "/dav/c/" :headers {"Depth" "1"} :body (lockinfo :exclusive "me"))
                 {:status 400}))))

(deftest locking-an-unmapped-url
  (let [h     (share (atom {}) {:locks (atom {})})
        token (atom nil)]
    (run (expect h (ring :lock "/dav/new.txt" :headers {"Depth" "0"} :body (lockinfo :exclusive "me"))
                 (taken 201 token))
         (expect h (ring :get "/dav/new.txt") {:status 200 :body ""})
         (expect h (ring :lock "/dav/no/x.txt" :body (lockinfo :exclusive "me")) {:status 409})
         (expect h (ring :lock "/dav/x.txt" :body "<D:lockinfo xmlns:D='DAV:'/>") {:status 400}))))

(deftest shared-locks
  (let [h     (share (atom {}) {:locks (atom {})})
        one   (atom nil)
        two   (atom nil)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared "one")) (taken 200 one))
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared "two")) (taken 200 two))
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :exclusive "three")) {:status 423})
         (expect h (holding :put "/dav/a.txt" two :body "b") {:status 204})
         (expect h #(ring :unlock "/dav/a.txt" :headers {"Lock-Token" (str "<" @one ">")}) {:status 204})
         (expect h (ring :put "/dav/a.txt" :body "b") {:status 423})
         (expect h #(ring :unlock "/dav/a.txt" :headers {"Lock-Token" (str "<" @two ">")}) {:status 204}))))

(deftest principals-and-expiry
  (let [now   (atom 0)
        h     (share (atom {}) {:locks     (atom {})
                                :principal #(get-in % [:headers "x-user"])
                                :clock     #(#?(:clj java.util.Date. :cljs js/Date.) @now)})
        token (atom nil)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/a.txt" :headers {"X-User" "alice" "Timeout" "Second-60"}
                         :body (lockinfo :exclusive "alice"))
                 (taken 200 token))
         (expect h (holding :put "/dav/a.txt" token :headers {"X-User" "bob"} :body "b")
                 {:status 423})
         (expect h (holding :put "/dav/a.txt" token :headers {"X-User" "alice"} :body "b")
                 {:status 204})
         (expect h #(ring :unlock "/dav/a.txt" :headers {"X-User" "bob" "Lock-Token" (str "<" @token ">")})
                 {:status 403})
         (fn [] (reset! now 61000))
         (expect h (ring :put "/dav/a.txt" :headers {"X-User" "bob"} :body "c") {:status 204}))))

(deftest locks-that-others-cannot-get-around
  (let [h     (share (atom {}) {:locks (atom {}) :principal #(get-in % [:headers "x-user"])})
        alice {"X-User" "alice"}
        bob   {"X-User" "bob"}
        token (atom nil)]
    (run (expect h (ring :put "/dav/caf%C3%A9.txt" :headers alice :body "a") {:status 201})
         (expect h (ring :lock "/dav/caf%C3%A9.txt" :headers alice :body (lockinfo :exclusive "alice"))
                 (taken 200 token))
         (expect h (ring :put "/dav/cafe%CC%81.txt" :headers bob :body "b") {:status 423})
         (expect h (ring :delete "/dav/cafe%CC%81.txt" :headers bob) {:status 423})
         (expect h (ring :mkcol "/dav/c/" :headers alice) {:status 201})
         (expect h (ring :lock "/dav/c/" :headers (assoc alice "Depth" "0")
                         :body (lockinfo :exclusive "alice"))
                 (taken 200 token))
         (expect h (ring :lock "/dav/c/bob.txt" :headers bob :body (lockinfo :exclusive "bob"))
                 {:status 423})
         (expect h (ring :get "/dav/c/bob.txt") {:status 404}))))

(deftest limits-of-locks
  (let [h     (share (atom {}) {:locks (atom {}) :max-locks 10})
        token (atom nil)]
    (apply run
           (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
           (expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared (str/join (repeat 1100 "x"))))
                   {:status 400})
           (concat
            (for [_ (range 8)]
              (expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared "me")) (taken 200 token)))
            [(expect h (ring :lock "/dav/a.txt" :body (lockinfo :shared "me")) {:status 507})
             (expect h (ring :put "/dav/b.txt" :body "b") {:status 201})
             (expect h (ring :lock "/dav/b.txt" :body (lockinfo :shared "me")) (taken 200 token))
             (expect h (ring :lock "/dav/b.txt" :body (lockinfo :shared "me")) (taken 200 token))
             (expect h (ring :lock "/dav/b.txt" :body (lockinfo :shared "me")) {:status 507})]))))

(deftest locks-go-with-their-resources
  (let [table (atom {})
        h     (share (atom {}) {:locks table})
        token (atom nil)]
    (run (expect h (ring :put "/dav/a.txt" :body "a") {:status 201})
         (expect h (ring :lock "/dav/a.txt" :body (lockinfo :exclusive "me")) (taken 200 token))
         (expect h (holding :delete "/dav/a.txt" token) {:status 204})
         (fn [] (is (empty? @table) "a delete destroys the lock"))
         (expect h (ring :put "/dav/b.txt" :body "b") {:status 201})
         (expect h (ring :lock "/dav/b.txt" :body (lockinfo :exclusive "me")) (taken 200 token))
         (expect h (holding :move "/dav/b.txt" token :headers {"Destination" "/dav/c.txt"})
                 {:status 201})
         (fn [] (is (empty? @table) "and a move leaves it behind"))
         (expect h (ring :put "/dav/c.txt" :body "c") {:status 204}))))

(deftest class-1-without-a-lock-table
  (let [h (share)]
    (run (expect h (ring :lock "/dav/" :body (lockinfo :exclusive "me")) {:status 405})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "0"})
                 #(is (not (str/includes? (text (:body %)) "lockdiscovery")))))))
