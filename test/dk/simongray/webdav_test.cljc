(ns dk.simongray.webdav-test
  "The client: its request maps, and its readings of the answers of
  Nextcloud and Apache's mod_dav, which dev/record.sh records. The tests
  check what stays the same from one recording to the next, e.g. names
  and sizes rather than ETags. A listing in the default namespace with
  absolute hrefs is written by hand, for the forms that neither server
  writes."
  (:require #?(:cljs [dk.simongray.webdav.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.webdav :as webdav]
            [dk.simongray.webdav.test-support :refer [fixture]]
            [dk.simongray.webdav.xml :as xml]))

(def root
  "A collection on a Nextcloud."
  "https://cloud.example.org/remote.php/dav/files/simon/notes/")

(def owncloud
  "The alias of ownCloud's namespace, which Nextcloud writes."
  {"http://owncloud.org/ns" :oc})

(deftest requests
  (testing "PROPFIND"
    (let [r (webdav/request :propfind root {:depth 1 :props [:dav/getetag :oc/fileid] :namespaces owncloud})]
      (is (= :propfind (:method r)))
      (is (= "1" (get-in r [:headers "Depth"])))
      (is (= "application/xml; charset=utf-8" (get-in r [:headers "Content-Type"])))
      (is (= {:props [:dav/getetag :oc/fileid]}
             (xml/read-propfind (xml/parse (:body r) owncloud))))
      (is (= owncloud (:namespaces r)) "kept, so that the answer reads by them too"))
    (is (= "0" (get-in (webdav/request :propfind root) [:headers "Depth"])) "depth 0 unless given")
    (is (= {:allprop? true} (xml/read-propfind (xml/parse (:body (webdav/request :propfind root))))))
    (is (= "infinity" (get-in (webdav/request :PROPFIND root {:depth :infinity}) [:headers "Depth"]))))
  (testing "credentials, hidden when printed"
    (let [r (webdav/request :get root {:credentials {:username "simon" :password "app pass"}})]
      (is (= "Basic c2ltb246YXBwIHBhc3M=" (get-in (http/revealed r) [:headers "Authorization"])))
      (is (not (str/includes? (pr-str r) "c2ltb246")))))
  (testing "PROPPATCH"
    (is (= [[:set [:dav/displayname {} "Notes"]] [:remove :oc/favorite]]
           (xml/read-propertyupdate
            (xml/parse (:body (webdav/request :proppatch root
                                              {:patch      [[:set :dav/displayname "Notes"]
                                                            [:remove :oc/favorite]]
                                               :namespaces owncloud}))
                       owncloud)))))
  (testing "PUT and its preconditions"
    (let [r (webdav/request :put (str root "a.edn") {:body "{}" :content-type "application/edn"
                                                     :if-match "\"1\""})]
      (is (= {"Content-Type" "application/edn" "If-Match" "\"1\""} (:headers r)))
      (is (= "{}" (:body r))))
    (is (= "*" (get-in (webdav/request :put (str root "a.edn") {:body "{}" :if-none-match "*"})
                       [:headers "If-None-Match"]))))
  (testing "COPY and MOVE"
    (is (= {"Destination" (str root "b%20c.edn") "Overwrite" "F"}
           (:headers (webdav/request :copy (str root "a.edn") {:destination "b%20c.edn" :overwrite? false}))))
    (is (= {"Destination" "https://cloud.example.org/x" "Depth" "infinity"}
           (:headers (webdav/request :move (str root "a/") {:destination "/x" :depth :infinity}))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (webdav/request :move root))))
  (testing "LOCK and UNLOCK"
    (let [r (webdav/request :lock (str root "a.edn") {:scope :exclusive :owner "phone" :timeout 600 :depth 0})]
      (is (= {"Timeout" "Second-600" "Depth" "0" "Content-Type" "application/xml; charset=utf-8"} (:headers r)))
      (is (= [:dav/lockinfo {}
              [:dav/lockscope {} [:dav/exclusive {}]]
              [:dav/locktype {} [:dav/write {}]]
              [:dav/owner {} "phone"]]
             (xml/parse (:body r)))))
    (is (= {"If" "(<urn:uuid:t>)" "Timeout" "Infinite"}
           (:headers (webdav/request :lock root {:lock-token "urn:uuid:t" :timeout :infinite})))
        "a refresh")
    (is (nil? (:body (webdav/request :lock root {:lock-token "urn:uuid:t"}))))
    (is (= {"Lock-Token" "<urn:uuid:t>"} (:headers (webdav/request :unlock root {:lock-token "urn:uuid:t"}))))
    (is (= {"If" "(<urn:uuid:t>)"} (:headers (webdav/request :put root {:lock-token "urn:uuid:t"})))
        "a write that holds the lock")
    (is (= {"If" "(Not <DAV:no-lock>)"}
           (:headers (webdav/request :delete root {:lock-token "urn:uuid:t" :if "(Not <DAV:no-lock>)"})))
        "an :if of one's own wins"))
  (testing "what any request takes"
    (let [r (webdav/request :report root {:depth 1 :body "<x/>" :minimal? true :timeout-seconds 5
                                          :if [{:href nil :conditions [{:token "DAV:no-lock" :not? true}]}]})]
      (is (= {"Depth" "1" "Prefer" "return=minimal" "If" "(Not <DAV:no-lock>)"} (:headers r)))
      (is (= "<x/>" (:body r)))
      (is (= 5 (:timeout-seconds r))))))

(defn- response
  "A response as wary-fetch gives it, of `status` with an XML `body`."
  ([status body]
   (response status body {}))
  ([status body headers]
   {:status  status
    :headers (merge {"content-type" "application/xml; charset=utf-8"} headers)
    :body    body}))

(defn- by-name
  "The `resources` by their names."
  [resources]
  (into {} (map (juxt :name identity)) resources))

(deftest a-listing-of-nextcloud
  (let [{:keys [status resources]} (webdav/parse-response (webdav/request :propfind root {:depth 1 :namespaces owncloud})
                                                          (response 207 (fixture "webdav/nextcloud-propfind.xml")))
        {:strs [notes Archive]
         :as   found}              (by-name resources)
        phone                      (found "phone.edn")
        laptop                     (found "laptop at home.edn")]
    (is (= 207 status))
    (is (= #{"notes" "phone.edn" "laptop at home.edn" "Archive"} (set (keys found))))
    (is (= [true true false false] (map :collection? [notes Archive phone laptop])))
    (is (= "https://cloud.example.org/remote.php/dav/files/simon/notes/laptop%20at%20home.edn" (:url laptop)))
    (is (re-matches #"\"[0-9a-f]+\"" (:etag phone)))
    (is (re-matches #"\"[0-9a-f]+\"" (:etag notes)) "a collection has an ETag too")
    (is (= [6 7] (map :content-length [phone laptop])))
    (is (= "application/octet-stream" (:content-type phone))
        "by the name, whatever the Content-Type of the PUT")
    (is (inst? (:last-modified laptop)))
    (is (re-matches #"\d+" (get-in phone [:props :oc/fileid])) "by the alias")
    (is (= {:dav/getcontentlength 404 :dav/getcontenttype 404} (:failed notes)))
    (is (= {:dav/quota-used-bytes 404 :dav/quota-available-bytes 404} (:failed phone)))
    (is (= "-3" (get-in Archive [:props :dav/quota-available-bytes])))
    (is (= #{{:name "phone.edn" :version (:etag phone)}
             {:name "laptop at home.edn" :version (:etag laptop)}}
           (->> resources
                (remove :collection?)
                (map (fn [{:keys [name etag]}] {:name name :version etag}))
                set))
        "the listing that like-minded would make of it")))

(deftest a-listing-of-mod-dav
  (let [{:keys [resources]}       (webdav/parse-response (webdav/request :propfind "http://localhost/dav/notes/" {:depth 1})
                                                         (response 207 (fixture "webdav/apache-propfind.xml")))
        {:strs [notes sub]
         :as   found}             (by-name resources)
        file                      (found "report one.txt")]
    (is (= #{"notes" "report one.txt" "sub"} (set (keys found))))
    (is (and (:collection? notes) (:collection? sub)) "its prefix lp1 is DAV: too")
    (is (= "httpd/unix-directory" (:content-type notes)))
    (is (inst? (:created notes)))
    (is (not (:collection? file)))
    (is (= 12 (:content-length file)))
    (is (= "text/plain" (:content-type file)))
    (is (re-matches #"(W/)?\"[0-9a-f-]+\"" (:etag file)) "weak for a second after the PUT")
    (is (= "F" (get-in file [:props (keyword "http://apache.org/dav/props/" "executable")])))
    (is (= [:dav/lockentry :dav/lockentry] (map first (get-in file [:props :dav/supportedlock]))))
    (is (nil? (get-in file [:props :dav/lockdiscovery])))))

(deftest a-listing-in-the-default-namespace
  (let [{:keys [resources]} (webdav/parse-response (webdav/request :propfind "https://dav.example.org/files/Shared%20Docs/" {:depth 1})
                                                   (response 207 (fixture "webdav/default-namespace.xml")))
        [dir file gone]     resources]
    (is (= "Shared Docs" (:name dir)))
    (is (= #inst "2026-09-30T20:15:00Z" (:created dir)) "an offset")
    (is (= "café.md" (:name file)))
    (is (= "W/\"77\"" (:etag file)))
    (is (= 12 (:content-length file)) "trimmed")
    (is (= #inst "2026-09-30T20:15:00Z" (:last-modified file)) "a date of RFC 850")
    (is (= "blue" (get-in file [:props (keyword "http://example.com/ns/" "color")])))
    (is (= {:status 404 :name "gone.md"} (select-keys gone [:status :name])))))

(deftest other-answers
  (testing "a multistatus that can't be read"
    (is (= {:status 207 :malformed? true}
           (webdav/parse-response (webdav/request :propfind root {}) (response 207 "<D:multistatus")))))
  (testing "PROPPATCH"
    (let [patched   #(first (:resources (webdav/parse-response (webdav/request :proppatch root {:patch [] :namespaces owncloud})
                                                               (response 207 %))))
          nextcloud (patched (fixture "webdav/nextcloud-proppatch.xml"))
          apache    (patched (fixture "webdav/apache-proppatch.xml"))]
      (is (= {:oc/favorite nil :dav/getetag nil} (:props nextcloud)) "Nextcloud takes even DAV:getetag")
      (is (nil? (:failed nextcloud)))
      (is (= {:dav/getetag 409 (keyword "http://example.com/ns/" "color") 424} (:failed apache))
          "mod_dav refuses it, and so the other one")))
  (testing "a precondition that failed, with sabre/dav's body"
    (is (= {:status 412 :error [(keyword "http://sabredav.org/ns" "exception")
                                (keyword "http://sabredav.org/ns" "message")
                                (keyword "http://sabredav.org/ns" "header")]}
           (webdav/parse-response (webdav/request :put root) (response 412 (fixture "webdav/nextcloud-412.xml"))))))
  (testing "a lock that mod_dav took"
    (let [{:keys [status lock]} (webdav/parse-response (webdav/request :lock root {:scope :exclusive})
                                                       (response 200 (fixture "webdav/apache-lock.xml")))]
      (is (= 200 status))
      (is (= {:scope :exclusive :depth :infinity :timeout 600 :owner [:dav/owner {} "webdav-clj"]}
             (dissoc lock :token)))
      (is (str/starts-with? (:token lock) "opaquelocktoken:"))))
  (testing "depth infinity, which mod_dav refuses with a page of HTML"
    (is (= {:status 403}
           (webdav/parse-response (webdav/request :propfind root {:depth :infinity})
                                  {:status  403
                                   :headers {"content-type" "text/html; charset=iso-8859-1"}
                                   :body    (fixture "webdav/apache-403.xml")}))))
  (testing "a DAV:error of RFC 4918"
    (is (= {:status 403 :error [:dav/propfind-finite-depth]}
           (webdav/parse-response (webdav/request :propfind root {:depth :infinity})
                                  (response 403 "<D:error xmlns:D=\"DAV:\"><D:propfind-finite-depth/></D:error>")))))
  (testing "an error without XML"
    (is (= {:status 404}
           (webdav/parse-response (webdav/request :get root)
                                  {:status 404 :headers {"content-type" "text/html"} :body "<html>"}))))
  (testing "OPTIONS"
    (is (= {:status 200 :dav ["1" "2" "3" "addressbook"] :allow #{:options :get :propfind}}
           (webdav/parse-response (webdav/request :options root)
                                  {:status 200 :headers {"dav"   ["1, 2" "3, addressbook"]
                                                         "allow" "OPTIONS, GET,PROPFIND"}}))))
  (testing "GET"
    (is (= {:status 200 :etag "\"v1\"" :content-type "application/edn" :content-length 2
            :last-modified #inst "2026-10-13T15:04:05Z" :body "{}"}
           (webdav/parse-response (webdav/request :get root)
                                  {:status  200
                                   :headers {"etag"           "\"v1\""
                                             "content-type"   "application/edn"
                                             "content-length" "2"
                                             "last-modified"  "Tue, 13 Oct 2026 15:04:05 GMT"}
                                   :body    "{}"}))))
  (testing "PUT"
    (is (= {:status 201 :etag "\"v2\""}
           (webdav/parse-response (webdav/request :put root) {:status 201 :headers {"etag" ["\"v2\""]}})))))

(defn- listed
  "The resource that a 207 to a PROPFIND of root gives for the `href`."
  [href]
  (first (:resources (webdav/parse-response
                      (webdav/request :propfind root)
                      (response 207 (str "<D:multistatus xmlns:D='DAV:'><D:response><D:href>" href
                                         "</D:href><D:status>HTTP/1.1 200 OK</D:status>"
                                         "</D:response></D:multistatus>"))))))

(deftest a-server-that-lies
  (testing "a URL on another origin, which the credentials of root mustn't reach"
    (is (= (str root "a.txt") (:url (listed "a.txt"))))
    (doseq [href ["https://evil.example/a.txt" "//evil.example/a.txt" "http://cloud.example.org/a.txt"
                  "javascript:alert(1)" "file:///etc/passwd"]]
      (is (not (contains? (listed href) :url)) href)))
  (testing "a name that can't name a file"
    (is (= "a b.txt" (:name (listed "/x/a%20b.txt"))))
    (doseq [href ["/dav/..%2F..%2F.ssh%2Fauthorized_keys" "/dav/a%5Cb" "/dav/a%00b"]]
      (is (not (contains? (listed href) :name)) href)))
  (testing "a lock scope as text, and a refresh among the locks of others"
    (let [discovery (str "<D:prop xmlns:D='DAV:'><D:lockdiscovery>"
                         "<D:activelock><D:lockscope>exclusive</D:lockscope>"
                         "<D:locktoken><D:href>urn:uuid:other</D:href></D:locktoken></D:activelock>"
                         "<D:activelock><D:lockscope><D:shared/></D:lockscope>"
                         "<D:locktoken><D:href>urn:uuid:mine</D:href></D:locktoken></D:activelock>"
                         "</D:lockdiscovery></D:prop>")]
      (is (= {:token "urn:uuid:mine" :scope :shared :depth 0}
             (:lock (webdav/parse-response (webdav/request :lock root {:lock-token "urn:uuid:mine"})
                                           (response 200 discovery))))))))

(deftest requests-that-are-refused
  (let [refused #(try (%) nil (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
    (doseq [url ["https://simon:secret@cloud.example.org/notes/" "file:///etc/passwd" "notes/"]]
      (is (some? (refused #(webdav/request :get url))) url))
    (is (not (str/includes? (pr-str (refused #(webdav/request :copy root {:credentials {:username "simon" :password "secret"}})))
                            "secret"))
        "an error leaves the password out")
    (is (some? (refused #(webdav/request :proppatch root {:patch [[:set (keyword "urn:x" "a/><x") "v"]]})))
        "a name with markup in it")))
