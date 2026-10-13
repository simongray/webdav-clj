(ns dk.simongray.webdav.paths-test
  "Paths below the root of a share, the hrefs that name them, and the
  segments that can't name a resource."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.webdav.paths :as paths]))

(deftest roots
  (is (= "/dav/" (paths/root "dav")))
  (is (= "/dav/" (paths/root "/dav")))
  (is (= "/" (paths/root ""))))

(deftest parsing
  (is (= ["notes" "a b.edn"] (paths/parse "/dav/" "/dav/notes/a%20b.edn")))
  (is (= [] (paths/parse "/dav/" "/dav/")))
  (is (= [] (paths/parse "/dav/" "/dav")) "the root without its trailing slash")
  (is (= ["notes"] (paths/parse "/dav/" "/dav/notes/")) "a trailing slash doesn't count")
  (is (= ["a" "b"] (paths/parse "/" "/a//b")) "nor an empty segment")
  (is (= ["res-€"] (paths/parse "/dav/" "/dav/res-%e2%82%ac")))
  (is (= ["100%.edn"] (paths/parse "/dav/" "/dav/100%.edn"))
      "a % that starts no escape is part of the name")
  (is (nil? (paths/parse "/dav/" "/davx/a")))
  (is (= ["a"] (paths/parse "/s@x/" "/s%40x/a")) "the root matches however it's encoded")
  (is (= ["a"] (paths/parse "/caf%c3%a9/" "/caf%C3%A9/a")))
  (is (= ["a"] (paths/parse "/" "/a")))
  (is (nil? (paths/parse "/dav/" "/elsewhere")))
  (testing "a segment that can't name a resource"
    (doseq [uri ["/dav/../etc" "/dav/%2e%2e/etc" "/dav/%2E" "/dav/a%2Fb"
                 "/dav/a%5Cb" "/dav/x%00.edn" "/dav/%FF"]]
      (is (= :invalid (paths/parse "/dav/" uri)) uri))))

(deftest resolving
  (is (= ["b c"] (paths/resolve "/dav/" "/dav/a" "http://other.example/dav/b%20c/"))
      "only the path of a URL counts")
  (is (= ["b"] (paths/resolve "/dav/" "/dav/a" "/dav/b")))
  (is (= ["b"] (paths/resolve "/dav/" "/dav/a" "b")) "relative to the request")
  (is (nil? (paths/resolve "/dav/" "/dav/a" "/dav/../etc")) "dot segments resolve first")
  (is (nil? (paths/resolve "/dav/" "/dav/a" "/other/b")))
  (is (nil? (paths/resolve "/dav/" "/dav/a" "http://[bad"))))

(deftest hrefs
  (is (= "/dav/notes/a%20b.edn" (paths/href "/dav/" ["notes" "a b.edn"])))
  (is (= "/dav/notes/" (paths/href "/dav/" ["notes"] true)))
  (is (= "/dav/" (paths/href "/dav/" [] true)))
  (is (= "/dav/100%25%20%3B%23.edn" (paths/href "/dav/" ["100% ;#.edn"]))
      "%, ; and # are encoded, so the href reads back as the name")
  (is (= ["100% ;#.edn"] (paths/parse "/dav/" (paths/href "/dav/" ["100% ;#.edn"])))))

(deftest names-and-parents
  (is (= "a b" (paths/name "https://dav.example.org/dav/a%20b/")))
  (is (= "x.edn" (paths/name "/dav/x.edn")))
  (is (= "" (paths/name "/")))
  (is (= ["a"] (paths/parent ["a" "b"])))
  (is (nil? (paths/parent [])))
  (is (paths/within? ["a"] ["a" "b"]))
  (is (paths/within? [] ["a"]))
  (is (not (paths/within? ["a" "b"] ["a"]))))
