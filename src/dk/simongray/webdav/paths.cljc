(ns dk.simongray.webdav.paths
  "The paths of resources below the root of a share, and the hrefs that
  name them.

  A path is a vector of decoded segments below the root, e.g. [\"notes\"
  \"a b.edn\"], and [] is the root itself. Each segment is in Unicode's
  normalization form C, so that café names one resource however a client
  writes it. An href is an absolute path with each segment
  percent-encoded, as RFC 4918, section 8.3, allows, e.g.
  /dav/notes/a%20b.edn. The percent-encoding is whatwg-url's, so it's the
  same on every platform."
  (:refer-clojure :exclude [name resolve])
  (:require [clojure.string :as str]
            [dk.simongray.whatwg-url :as url]
            [dk.simongray.whatwg-url.percent :as percent]))

(defn root
  "The `s` as the root of a share: an absolute path with a slash at each
  end, e.g. /dav/ for dav. The other functions take a root in this form."
  [s]
  (let [s (str/replace (str s) #"^/*" "/")]
    (if (str/ends-with? s "/") s (str s "/"))))

;; RFC 4918, section 8.3; URL Standard, section 4.4, the single-dot and
;; double-dot segments
(defn segment?
  "Whether the decoded segment `s` can name a resource: neither . nor ..,
  and without a slash, a backslash, a control character or the U+FFFD
  that bytes that aren't UTF-8 decode to."
  [s]
  (not (or (#{"." ".."} s)
           (re-find #"[/\\\x00-\x1f\x7f�]" s))))

;; the two Unicode forms of a name, e.g. café as macOS and as Windows
;; write it, are one name for a lock as for a file system
(defn- nfc
  "The text `s` in Unicode's normalization form C."
  [s]
  #?(:clj  (java.text.Normalizer/normalize ^String s java.text.Normalizer$Form/NFC)
     :cljs (.normalize s "NFC")))

(defn- decoded
  "The decoded segments of the percent-encoded path `s`, without the empty
  ones, in normalization form C."
  [s]
  (into [] (comp (remove empty?) (map (comp nfc percent/decode))) (str/split (str s) #"/")))

(defn segments
  "The decoded segments of the percent-encoded path `s`, without the empty
  ones, or :invalid when a segment can't name a resource."
  [s]
  (let [d (decoded s)]
    (if (every? segment? d) d :invalid)))

(defn parse
  "The path of the percent-encoded absolute path `s`, e.g. the :uri of a
  Ring request, below the `root` of a share. It's nil when `s` isn't below
  the root, and :invalid when a segment can't name a resource (see
  segment?). The root matches by its decoded segments, so that e.g.
  /simon%40example.com/ is below the root /simon@example.com/ too.

      (parse \"/dav/\" \"/dav/notes/a%20b.edn\")
      ;; => [\"notes\" \"a b.edn\"]"
  [root s]
  (let [root (decoded root)
        d    (decoded s)]
    (when (= root (take (count root) d))
      (let [path (subvec d (count root))]
        (if (every? segment? path) path :invalid)))))

;; RFC 4918, section 10.3: the Destination header and the Resource-Tag of
;; the If header are absolute URIs or absolute paths
(defn resolve
  "The path of the URL or absolute path `s` below the `root` of a share,
  resolved against the absolute path `base`, e.g. the :uri of the request.
  Only the path of the URL counts. It's nil when `s` isn't a URL or isn't
  below the root, and :invalid as for parse."
  [root base s]
  (when-let [resolved (url/parse (str s) (str "http://localhost" base))]
    (parse root (url/pathname resolved))))

(defn href
  "The href of the `path` below the `root` of a share, with a trailing
  slash when it's a `collection?`. A segment that can't name a resource
  (see segment?), e.g. .., throws.

      (href \"/dav/\" [\"notes\" \"a b.edn\"])
      ;; => \"/dav/notes/a%20b.edn\""
  ([root path]
   (href root path false))
  ([root path collection?]
   (when-let [bad (first (remove segment? path))]
     (throw (ex-info "A segment can't name a resource" {:type ::invalid :segment bad})))
   (str root
        (str/join "/" (map percent/encode path))
        (when (and collection? (seq path)) "/"))))

(defn name
  "The decoded last segment of the `href`, a URL or a path, e.g. a b.edn
  for /dav/notes/a%20b.edn. It's empty for the root of a server."
  [href]
  ;; split rather than matched, since a regex for the last segment
  ;; backtracks for each of a long run of slashes
  (let [path (or (some-> (url/parse (str href) "http://localhost/") url/pathname)
                 (str href))]
    (or (some-> (last (remove empty? (str/split path #"/"))) percent/decode)
        "")))

(defn parent
  "The path of the collection that holds the resource at `path`, or nil
  for the root."
  [path]
  (when (seq path)
    (pop (vec path))))

(defn within?
  "Whether the `path` is the path `ancestor` or below it."
  [ancestor path]
  (and (<= (count ancestor) (count path))
       (= (seq ancestor) (seq (take (count ancestor) path)))))

(comment
  (parse "/dav/" "/dav/notes/a%20b.edn")
  (parse "/dav/" "/dav")
  (parse "/dav/" "/dav/%2e%2e/etc")
  (resolve "/dav/" "/dav/a" "http://other.example/dav/b%20c/")
  (href "/dav/" ["notes" "100% ;real.edn"] true)
  (name "https://dav.example.org/dav/a%20b/")
  #_.)
