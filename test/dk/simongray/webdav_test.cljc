(ns dk.simongray.webdav-test
  "The client: its request maps, and its readings of recorded answers in
  the shapes that Nextcloud, Apache's mod_dav and others write."
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

(deftest a-listing-of-nextcloud
  (let [{:keys [status resources]} (webdav/parse-response (webdav/request :propfind root {:depth 1 :namespaces owncloud})
                                                          (response 207 (fixture "webdav/nextcloud-propfind.xml")))
        [self doc laptop archive]  resources]
    (is (= 207 status))
    (is (= [true false false true] (map :collection? resources)))
    (is (= ["notes" "8f1c9e2a-7a4b-4c1d-9e2f-0a1b2c3d4e5f.edn" "laptop at home.edn" "Archive"]
           (map :name resources)))
    (is (= "https://cloud.example.org/remote.php/dav/files/simon/notes/laptop%20at%20home.edn" (:url laptop)))
    (is (= "\"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6\"" (:etag doc)))
    (is (= 2048 (:content-length doc)))
    (is (= #inst "2026-10-12T21:03:09Z" (:last-modified laptop)))
    (is (= "00000124ocabcdef1234" (get-in doc [:props :oc/fileid])) "by the alias")
    (is (= {:dav/getcontentlength 404 :dav/getcontenttype 404} (:failed self)))
    (is (= "-3" (get-in archive [:props :dav/quota-available-bytes])))
    (is (= [{:name "8f1c9e2a-7a4b-4c1d-9e2f-0a1b2c3d4e5f.edn" :version "\"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6\""}
            {:name "laptop at home.edn" :version "\"0f9e8d7c6b5a4938271605f4e3d2c1b0\""}]
           (->> resources
                (remove :collection?)
                (mapv (fn [{:keys [name etag]}] {:name name :version etag}))))
        "the listing that like-minded would make of it")))

(deftest a-listing-of-mod-dav
  (let [{:keys [resources]} (webdav/parse-response (webdav/request :propfind "http://localhost/dav/" {:depth 1})
                                                   (response 207 (fixture "webdav/apache-propfind.xml")))
        [dir file]          resources]
    (is (:collection? dir) "lp1 is DAV: too")
    (is (= "httpd/unix-directory" (:content-type dir)))
    (is (= #inst "2026-10-01T10:00:00Z" (:created dir)))
    (is (= "report one.txt" (:name file)))
    (is (= 1234 (:content-length file)))
    (is (= "\"4d2-63f0b2c3d4e5f\"" (:etag file)))
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
  (testing "PROPPATCH"
    (is (= {:dav/getetag 403 :oc/favorite 424}
           (:failed (first (:resources (webdav/parse-response (webdav/request :proppatch root {:patch [] :namespaces owncloud})
                                                              (response 207 (fixture "webdav/proppatch-207.xml")))))))))
  (testing "a precondition that failed, with sabre/dav's body"
    (is (= {:status 412 :error [(keyword "http://sabredav.org/ns" "sabredav-version")
                                (keyword "http://sabredav.org/ns" "exception")
                                (keyword "http://sabredav.org/ns" "message")
                                (keyword "http://sabredav.org/ns" "header")]}
           (webdav/parse-response (webdav/request :put root) (response 412 (fixture "webdav/sabre-412.xml"))))))
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
