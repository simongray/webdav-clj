(ns dk.simongray.webdav.files-test
  "The backend over the file system: the client's scenario and the
  handler's copies and moves over a directory, and what it keeps out of
  reach."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dk.simongray.webdav.client-server-test :refer [scenario]]
            [dk.simongray.webdav.files :as files]
            [dk.simongray.webdav.server :as server]
            [dk.simongray.webdav.server-test :refer [copying-and-moving]]
            [dk.simongray.webdav.test-support :refer [expect ring run text through]])
  (:import [java.io ByteArrayInputStream]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

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
         (expect h (ring :put "/dav/a.txt" :body (stream "12345")) {:status 413})
         (expect h (ring :get "/dav/a.txt") {:status 200})
         (fn []
           (is (= "1234" (slurp (io/file (.toFile dir) "a.txt"))) "a body past the limit changes nothing")
           (is (empty? (.list (io/file (.toFile dir) ".webdav" "tmp"))) "and leaves no file behind")))))

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
