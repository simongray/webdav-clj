(ns dk.simongray.webdav.files-test
  "The backend over the file system: the client's scenario and the
  handler's copies and moves over a directory, and what it keeps out of
  reach."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.webdav.client-server-test :refer [scenario]]
            [dk.simongray.webdav.files :as files]
            [dk.simongray.webdav.server :as server]
            [dk.simongray.webdav.server-test :refer [copying-and-moving]]
            [dk.simongray.webdav.test-support :refer [expect ring run text through]]
            [dk.simongray.webdav.xml :as xml])
  (:import [java.io ByteArrayInputStream]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- temp-dir
  "A new, empty directory."
  ^Path []
  (Files/createTempDirectory "webdav-clj" (make-array FileAttribute 0)))

(defn- share
  "A handler at /dav/ over the directory `dir`, with the `opts` of the
  handler."
  ([dir]
   (share dir {}))
  ([dir opts]
   (server/handler (files/backend (.toFile ^Path dir)) (merge {:root "/dav/"} opts))))

(defn- stream
  "A stream of the UTF-8 of the text `s`."
  [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(deftest the-client-over-a-directory
  (apply run (scenario (through (share (temp-dir) {:locks (atom {})})) "http://localhost/dav/")))

(deftest copying-and-moving-files
  (apply run (copying-and-moving (share (temp-dir)))))

(deftest what-stays-out-of-reach
  (let [dir     (temp-dir)
        outside (temp-dir)
        h       (share dir {:max-bytes 4})]
    (Files/writeString (.resolve outside "secret.txt") "secret" (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve dir "out") outside (make-array FileAttribute 0))
    (run (expect h (ring :get "/dav/out/secret.txt") {:status 404})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"})
                 #(is (not (re-find #"secret|\.webdav" (text (:body %))))))
         (expect h (ring :put "/dav/a.txt" :body (stream "1234")) {:status 201})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"})
                 #(is (not (re-find #"\.webdav" (text (:body %)))) "its own directory isn't listed"))
         (expect h (ring :get "/dav/.webdav/tmp") {:status 404})
         (expect h (ring :propfind "/dav/.WEBDAV/" :headers {"Depth" "0"}) {:status 404})
         (expect h (ring :put "/dav/.WEBDAV/props/a.txt.xml" :body (stream "<x/>")) {:status 409})
         (expect h (ring :get "/dav/a.txt/x") {:status 404})
         (expect h (ring :put "/dav/a.txt/x" :body (stream "x")) {:status 409})
         (expect h (ring :mkcol "/dav/a.txt/x/") {:status 409})
         (expect h (ring :put "/dav/a.txt" :body (stream "12345")) {:status 413})
         (expect h (ring :get "/dav/a.txt") {:status 200})
         (fn []
           (is (= "1234" (slurp (io/file (.toFile dir) "a.txt"))) "a body past the limit changes nothing")
           (is (empty? (.list (io/file (.toFile dir) ".webdav" "tmp"))) "and leaves no file behind")))))

(defn- displayname
  "A PROPPATCH of the request `uri` that sets its DAV:displayname to `s`."
  [uri s]
  (ring :proppatch uri :body (xml/emit (xml/propertyupdate [[:set :dav/displayname s]]))))

(defn- displayname?
  "A step that checks that the DAV:displayname of `uri` over the handler
  `h` is `s`, or that it has none when `s` is nil."
  [h uri s]
  (expect h (ring :propfind uri :headers {"Depth" "0"}
                  :body (xml/emit (xml/propfind {:props [:dav/displayname]})))
          #(if s
             (is (str/includes? (text (:body %)) (str ">" s "<")) uri)
             (is (str/includes? (text (:body %)) "404 Not Found") uri))))

(deftest names-that-alias
  (let [h (share (temp-dir))]
    (run (expect h (ring :put "/dav/a.txt" :body (stream "a")) {:status 201})
         (expect h (ring :move "/dav/a.txt" :headers {"Destination" "/dav/A.txt"})
                 #(is (#{201 403} (:status %)) "a rename where case counts, else a refusal"))
         (fn []
           (is (= ["a"] (for [uri   ["/dav/a.txt" "/dav/A.txt"]
                              :let  [response (h (ring :get uri))]
                              :when (= 200 (:status response))]
                          (text (:body response))))
               "either way the file is there once"))
         (expect h (ring :put "/dav/caf%C3%A9.txt" :body (stream "c")) {:status 201})
         (expect h (ring :move "/dav/caf%C3%A9.txt" :headers {"Destination" "/dav/cafe%CC%81.txt"})
                 {:status 403})
         (expect h (ring :get "/dav/caf%C3%A9.txt") {:status 200 :body "c"}))))

(deftest properties-stay-apart
  (let [dir (temp-dir)
        h   (share dir)]
    (run (expect h (ring :put "/dav/a" :body (stream "a")) {:status 201})
         (expect h (displayname "/dav/a" "of-a") {:status 207})
         (expect h (ring :mkcol "/dav/a.xml/") {:status 201})
         (expect h (ring :put "/dav/a.xml/m" :body (stream "m")) {:status 201})
         (expect h (displayname "/dav/a.xml/m" "of-m") {:status 207})
         (displayname? h "/dav/a" "of-a")
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"}) {:status 207})
         (expect h (ring :delete "/dav/a.xml/") {:status 204})
         (displayname? h "/dav/a" "of-a")
         (fn [] (Files/delete (.resolve dir "a")))
         (expect h (ring :put "/dav/a" :body (stream "a")) {:status 201})
         (displayname? h "/dav/a" nil)
         (fn []
           (is (empty? (.list (io/file (.toFile dir) ".webdav" "tmp"))))))))

(deftest long-names
  (let [h    (share (temp-dir))
        name (apply str (repeat 251 "a"))]
    (run (expect h (ring :put (str "/dav/" name "b") :body (stream "a")) {:status 400})
         (expect h (ring :mkcol (str "/dav/" name "b/")) {:status 400})
         (expect h (ring :put (str "/dav/" name) :body (stream "a")) {:status 201})
         (expect h (displayname (str "/dav/" name) "long") {:status 207})
         (displayname? h (str "/dav/" name) "long"))))

(deftest a-read-only-directory
  (let [dir (temp-dir)]
    (Files/writeString (.resolve dir "a.txt") "a" (make-array java.nio.file.OpenOption 0))
    (Files/setPosixFilePermissions dir (PosixFilePermissions/fromString "r-xr-xr-x"))
    (try
      (let [h (share dir)]
        (run (expect h (ring :get "/dav/a.txt") {:status 200 :body "a"})
             (expect h (ring :propfind "/dav/" :headers {"Depth" "1"}) {:status 207})))
      (finally
        (Files/setPosixFilePermissions dir (PosixFilePermissions/fromString "rwxr-xr-x"))))))

(deftest links-and-other-odd-files
  (let [dir (temp-dir)
        h   (share dir)]
    (Files/createDirectory (.resolve dir "v2") (make-array FileAttribute 0))
    (Files/writeString (.resolve dir "v2/report.txt") "r" (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve dir "latest") (.resolve dir "v2") (make-array FileAttribute 0))
    (.waitFor (.start (ProcessBuilder. ["mkfifo" (str (.resolve dir "pipe"))])))
    (run (expect h (ring :get "/dav/latest/report.txt") {:status 404})
         (expect h (ring :move "/dav/latest/report.txt" :headers {"Destination" "/dav/v2/report.txt"})
                 {:status 404})
         (expect h (ring :get "/dav/v2/report.txt") {:status 200 :body "r"})
         (expect h (ring :get "/dav/pipe") {:status 404})
         (expect h (ring :propfind "/dav/" :headers {"Depth" "1"})
                 #(is (not (re-find #"latest|pipe" (text (:body %)))))))))

(deftest what-the-backend-keeps-for-itself
  (let [dir     (temp-dir)
        outside (temp-dir)]
    (files/backend (.toFile dir))
    (Files/createDirectories (.resolve dir ".webdav/tmp") (make-array FileAttribute 0))
    (Files/writeString (.resolve dir ".webdav/tmp/left") "x" (make-array java.nio.file.OpenOption 0))
    (files/backend (.toFile dir))
    (is (not (Files/exists (.resolve dir ".webdav/tmp/left") (make-array java.nio.file.LinkOption 0)))
        "a body left from a process that stopped is deleted")
    (Files/createSymbolicLink (.resolve dir ".webdav/props") outside (make-array FileAttribute 0))
    (is (thrown? clojure.lang.ExceptionInfo (files/backend (.toFile dir)))
        "nor does it start with a link among its own directories")))

(deftest etags-of-quick-writes
  (let [put! (:put! (files/backend (.toFile (temp-dir))))]
    (is (apply distinct? (for [s ["1" "2" "3"]]
                           (:etag (put! ["a.txt"] (stream s) {})))))))

(deftest a-write-that-raced
  (let [dir     (temp-dir)
        backend (files/backend (.toFile dir))]
    ((:put! backend) ["a.txt"] (stream "1") {})
    (is (= 412 (try ((:put! backend) ["a.txt"] (stream "2") {:if-etag "\"stale\""})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      (:dk.simongray.webdav/status (ex-data e))))))
    (is (= "1" (slurp (io/file (.toFile dir) "a.txt"))))
    (is (empty? (.list (io/file (.toFile dir) ".webdav" "tmp"))))))
