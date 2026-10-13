(ns dk.simongray.webdav.conditions-test
  "ETags, the preconditions of RFC 9110 and the If header of RFC 4918,
  with the examples of section 10.4."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.webdav.conditions :as conditions]))

(deftest entity-tags
  (is (= "\"7\"" (conditions/etag 7)) "a version is quoted")
  (is (= "W/\"x\"" (conditions/etag "W/\"x\"")))
  (is (= "\"ab\"" (conditions/etag "a \"b\"")))
  (is (nil? (conditions/etag nil)))
  (is (conditions/strong-match? "\"1\"" "\"1\""))
  (is (not (conditions/strong-match? "W/\"1\"" "\"1\"")))
  (is (conditions/weak-match? "W/\"1\"" "\"1\""))
  (is (= ["\"a\"" "W/\"b,c\""] (conditions/parse-etags "\"a\", W/\"b,c\"")))
  (is (= :any (conditions/parse-etags " * "))))

(def resource
  "A resource of ETag \"2\", last modified at 15:04:05.7."
  {:etag "\"2\"" :last-modified #inst "2026-10-13T15:04:05.700Z"})

(deftest preconditions
  (testing "If-Match, by the strong comparison"
    (is (nil? (conditions/evaluate :put {"if-match" "\"1\", \"2\""} resource)))
    (is (= 412 (conditions/evaluate :put {"if-match" "W/\"2\""} resource)))
    (is (= 412 (conditions/evaluate :put {"if-match" "*"} nil)))
    (is (nil? (conditions/evaluate :put {"if-match" "*"} resource))))
  (testing "If-None-Match, by the weak comparison"
    (is (= 412 (conditions/evaluate :put {"if-none-match" "*"} resource)))
    (is (nil? (conditions/evaluate :put {"if-none-match" "*"} nil)))
    (is (= 304 (conditions/evaluate :get {"if-none-match" "W/\"2\""} resource)))
    (is (= 412 (conditions/evaluate :delete {"if-none-match" "\"2\""} resource))))
  (testing "the dates, to the second"
    (is (= 304 (conditions/evaluate :get {"if-modified-since" "Tue, 13 Oct 2026 15:04:05 GMT"} resource)))
    (is (nil? (conditions/evaluate :get {"if-modified-since" "Tue, 13 Oct 2026 15:04:04 GMT"} resource)))
    (is (nil? (conditions/evaluate :get {"if-modified-since" "not a date"} resource)))
    (is (= 412 (conditions/evaluate :put {"if-unmodified-since" "Tue, 13 Oct 2026 15:04:04 GMT"} resource))))
  (testing "the order of RFC 9110, section 13.2.2"
    (is (nil? (conditions/evaluate :put {"if-match"            "\"2\""
                                         "if-unmodified-since" "Tue, 13 Oct 2026 15:04:04 GMT"}
                                   resource))
        "If-Unmodified-Since doesn't count beside If-Match")
    (is (nil? (conditions/evaluate :get {"if-none-match"     "\"3\""
                                         "if-modified-since" "Tue, 13 Oct 2026 15:04:05 GMT"}
                                   resource))
        "nor If-Modified-Since beside If-None-Match")))

(deftest if-header
  (testing "a bracket that never closes"
    (doseq [s ["<" "(<" "([" "<a> (<" "(<a> [)"]]
      (is (nil? (conditions/parse-if s)) s)))
  (testing "section 10.4.6, untagged lists"
    (is (= [{:href nil :conditions [{:token "urn:uuid:181d4fae-7d8c-11d0-a765-00a0c91e6bf2" :not? false}
                                    {:etag "\"I am an ETag\"" :not? false}]}
            {:href nil :conditions [{:etag "\"I am another ETag\"" :not? false}]}]
           (conditions/parse-if (str "(<urn:uuid:181d4fae-7d8c-11d0-a765-00a0c91e6bf2>\n"
                                     "  [\"I am an ETag\"])\n  ([\"I am another ETag\"])")))))
  (testing "section 10.4.7, Not"
    (is (= [{:token "urn:uuid:a" :not? true} {:token "urn:uuid:b" :not? false}]
           (:conditions (first (conditions/parse-if "(Not <urn:uuid:a>\n <urn:uuid:b>)"))))))
  (testing "section 10.4.9, a tagged list"
    (is (= ["/resource1" "/resource1"]
           (map :href (conditions/parse-if "</resource1>\n (<urn:uuid:a> [W/\"A weak ETag\"]) ([\"strong ETag\"])")))))
  (testing "what isn't an If header"
    (doseq [s ["" "(<a>" "(Not)" "garbage" "(<a>) <x> (<b>)" "()"]]
      (is (nil? (conditions/parse-if s)) s)))
  (testing "a round trip"
    (let [s "<http://x/a> (<urn:a> [W/\"b\"]) (Not <DAV:no-lock>) <http://x/b> ([\"c\"])"]
      (is (= s (conditions/if-header (conditions/parse-if s)))))))

(deftest if-evaluation
  (let [state (constantly {:etag "\"2\""})]
    (is (conditions/if-true? (conditions/parse-if "([\"2\"])") state))
    (is (conditions/if-true? (conditions/parse-if "([W/\"2\"])") state) "weak matches")
    (is (not (conditions/if-true? (conditions/parse-if "([\"1\"])") state)))
    (is (conditions/if-true? (conditions/parse-if "([\"1\"]) ([\"2\"])") state) "any list")
    (is (not (conditions/if-true? (conditions/parse-if "([\"2\"] <urn:x>)") state)) "every condition")
    (testing "section 10.4.8: a class 1 server has no lock tokens"
      (is (not (conditions/if-true? (conditions/parse-if "(<urn:uuid:x>)") state)))
      (is (conditions/if-true? (conditions/parse-if "(<urn:uuid:x>) (Not <DAV:no-lock>)") state)))
    (testing "section 10.4.11: an unmapped URL has no ETag"
      (is (not (conditions/if-true? (conditions/parse-if "</specs/rfc2518.doc> ([\"4217\"])")
                                    (constantly {}))))
      (is (conditions/if-true? (conditions/parse-if "</specs/rfc2518.doc> (Not [\"4217\"])")
                               (constantly {}))))))
