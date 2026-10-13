(ns dk.simongray.webdav.xml-test
  "The XML bodies: names in every namespace, prefixes on writing, and the
  multistatus, propfind and propertyupdate as data."
  (:require #?(:cljs [dk.simongray.webdav.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.webdav.xml :as xml]))

(def litmus
  "The namespace of litmus's properties."
  "http://example.com/neon/litmus/")

(deftest names
  (is (= [:dav/prop {} [(keyword litmus "a") {:xml/lang "en"} "1"] [:n {} "v"]]
         (xml/parse (str "<D:prop xmlns:D='DAV:'><x:a xmlns:x='" litmus "' xml:lang='en'>1</x:a>"
                         "<n xmlns=''>v</n></D:prop>")))
      "a namespace without an alias keeps its URI, and no namespace none")
  (is (= [:cal/calendar {}]
         (xml/parse "<calendar xmlns='urn:ietf:params:xml:ns:caldav'/>"
                    {"urn:ietf:params:xml:ns:caldav" :cal}))
      "an alias from the table")
  (is (= "{DAV:}getetag" (xml/clark :dav/getetag)))
  (is (= (str "{" litmus "}a") (xml/clark (keyword litmus "a"))))
  (is (= "{urn:ietf:params:xml:ns:caldav}calendar"
         (xml/clark :cal/calendar {"urn:ietf:params:xml:ns:caldav" :cal}))))

(deftest malformed
  (doseq [s ["<foo>"
             "<prop xmlns='DAV:'><displayname xmlns='dav'/></prop>"
             "<D:propfind xmlns:D=\"DAV:\"><D:prop><bar:foo xmlns:bar=\"\"/></D:prop></D:propfind>"
             "<D:propfind xmlns:D=\"DAV:\"><bar:foo/></D:propfind>"
             "<prop xmlns='DAV:'><x:p xmlns:x='foo'/></prop>"
             "<!DOCTYPE x [<!ENTITY e 'e'>]><x>&e;</x>"
             (str (char 0xFEFF) "<?xml version='1.0'?> <!-- <x/> --><?pi?>\n<!DOCTYPE x><x/>")]]
    (is (= ::xml/malformed (try (xml/parse s) nil
                                (catch #?(:clj Exception :cljs :default) e
                                  (:type (ex-data e)))))
        s))
  (is (= [:x {} "<!DOCTYPE x>"] (xml/parse "<x><![CDATA[<!DOCTYPE x>]]></x>"))
      "a document type only counts before the root")
  (is (= ::xml/malformed (try (xml/parse "<a><b/></a>" nil {:max-depth 1}) nil
                              (catch #?(:clj Exception :cljs :default) e
                                (:type (ex-data e)))))))

(deftest writing
  (let [tree [:dav/prop {} [(keyword litmus "a") {:xml/lang "en"} "1"] [:nonamespace {} "v"]
              [(keyword "http://bar" "foo") {}]]
        text (xml/emit tree)]
    (is (str/includes? text "xmlns:ns1=\"http://bar\""))
    (is (str/includes? text (str "xmlns:ns2=\"" litmus "\"")))
    (is (str/includes? text "<nonamespace>v</nonamespace>") "no prefix, and no default namespace")
    (is (= tree (xml/parse text)) "a round trip")))

(deftest values
  (is (= "Notes" (xml/value [:dav/displayname {} "Notes"])))
  (is (nil? (xml/value [:dav/getetag {}])))
  (is (= [[:dav/collection {}]] (xml/value [:dav/resourcetype {} [:dav/collection {}]])))
  (is (= [:x {} "v"] (xml/property :x "v")))
  (is (= [:x {} [:y {}]] (xml/property :x [:y {}])))
  (is (= [:x {} "a" [:y {}]] (xml/property :x ["a" [:y {}]]))))

(deftest multistatus
  (let [m {:responses  [{:href      "/a"
                         :propstats [{:status 200 :props [[:dav/getetag {} "\"1\""]]}
                                     {:status 404 :props [[(keyword litmus "b") {}]]}]}
                        {:href "/b" :status 424 :error [[:dav/lock-token-submitted {}]]}]
           :sync-token "t1"}]
    (is (= m (xml/read-multistatus (xml/parse (xml/emit (xml/multistatus m))))))
    (is (str/includes? (xml/emit (xml/multistatus m)) "HTTP/1.1 404 Not Found")))
  (testing "a response with several hrefs gives a map for each"
    (is (= [{:href "/a" :status 404} {:href "/b" :status 404}]
           (:responses (xml/read-multistatus
                        (xml/parse (str "<multistatus xmlns='DAV:'><response><href>/a</href>"
                                        "<href>/b</href><status>HTTP/1.1 404 Not Found</status>"
                                        "</response></multistatus>"))))))))

(deftest propfind
  (is (= {:props [:dav/getetag (keyword litmus "x")]}
         (xml/read-propfind (xml/parse (xml/emit (xml/propfind {:props [:dav/getetag (keyword litmus "x")]}))))))
  (is (= {:propname? true} (xml/read-propfind (xml/parse (xml/emit (xml/propfind {:propname? true}))))))
  (is (= {:allprop? true :include [:dav/x]}
         (xml/read-propfind (xml/parse (xml/emit (xml/propfind {:include [:dav/x]}))))))
  (is (= {:allprop? true}
         (xml/read-propfind (xml/parse "<propfind xmlns=\"DAV:\"><foobar/><allprop/></propfind>")))
      "litmus's propextended: an unknown element is ignored")
  (is (nil? (xml/read-propfind (xml/parse "<propfind xmlns=\"DAV:\"/>"))))
  (is (nil? (xml/read-propfind (xml/parse "<prop xmlns=\"DAV:\"/>")))))

(deftest propertyupdate
  (testing "document order, as section 9.2 requires"
    (is (= [[:remove (keyword litmus "removeset")]
            [:set [(keyword litmus "removeset") {} "x"]]
            [:set [(keyword litmus "valnspace") {} [(keyword "http://bar" "foo") {}]]]]
           (xml/read-propertyupdate
            (xml/parse (str "<propertyupdate xmlns='DAV:'>"
                            "<remove><prop><removeset xmlns='" litmus "'/></prop></remove>"
                            "<set><prop><removeset xmlns='" litmus "'>x</removeset>"
                            "<t:valnspace xmlns:t='" litmus "'><foo xmlns='http://bar'/></t:valnspace>"
                            "</prop></set></propertyupdate>"))))))
  (is (= [[:set [:dav/displayname {} "Notes"]]
          [:set [:dav/x {:xml/lang "da"} "y"]]
          [:remove :dav/z]]
         (xml/read-propertyupdate
          (xml/parse (xml/emit (xml/propertyupdate [[:set :dav/displayname "Notes"]
                                                    [:set [:dav/x {:xml/lang "da"} "y"]]
                                                    [:remove :dav/z]])))))))
