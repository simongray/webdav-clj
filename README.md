webdav-clj
==========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/webdav-clj.svg)](https://clojars.org/dk.simongray/webdav-clj)

This is a Clojure and ClojureScript library for
[WebDAV](https://www.rfc-editor.org/rfc/rfc4918), the protocol that
Nextcloud, Fastmail, storage boxes and file managers use to share files
over HTTP. It has a client and a Ring handler for classes 1 and 2 of the
standard: collections, properties, COPY and MOVE, and locks.

- **Plain data.** Requests are maps that any HTTP client can send, and
  answers are maps too, whatever their status. Property names are
  keywords, e.g. `:dav/getetag`.
- **Your own storage.** The handler serves a backend that is a map of a
  few functions, so a share can live in a database, in memory or on disk.
  It takes care of the rest: ETags, preconditions, the If header, Depth,
  and copying and moving between collections.
- **Safe by default.** Paths can't leave the share, bodies have limits,
  and PROPFIND refuses depth infinity unless you allow it.
- **Tested against litmus.** The handler passes every test of
  [litmus](https://notroj.github.io/litmus/), the classic WebDAV test
  suite, locks included.

The same code runs on the JVM, in Node and in the browser, with the same
results on each.

> This library was spun out of
> [like-minded](https://github.com/simongray/like-minded), which syncs
> app state through WebDAV collections. Like like-minded, it was developed
> with assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. For the latest release, add it
from [Clojars](https://clojars.org/dk.simongray/webdav-clj) to the
`:deps` in your `deps.edn`:

```clojure
dk.simongray/webdav-clj {:mvn/version "0.1.0"}
```

For changes that aren't released yet, use the SHA of the latest commit on
`master` instead. In shadow-cljs a Git dependency only works through
`deps.edn`, so set `:deps true` in your `shadow-cljs.edn` too. To read XML
in Node, set `globalThis.DOMParser` to a DOMParser, e.g. the one from
`@xmldom/xmldom`.

The client
----------

Make a request map with `webdav/request` and send it with `webdav/call!`,
which uses [wary-fetch](https://github.com/simongray/wary-fetch):

```clojure
(require '[dk.simongray.webdav :as webdav])

(webdav/call! (webdav/request :propfind "https://dav.example.org/notes/"
                              {:depth       1
                               :props       [:dav/getetag :dav/resourcetype]
                               :credentials {:username "simon" :password "…"}}))
;; => {:status    207
;;     :resources [{:name "notes" :collection? true …}
;;                 {:name        "phone.edn"
;;                  :collection? false
;;                  :etag        "\"1\""
;;                  :url         "https://dav.example.org/notes/phone.edn"
;;                  :props       {:dav/getetag "\"1\"" :dav/resourcetype nil}
;;                  …}]}
```

Each resource has keys from its live properties, e.g. `:content-length`
and `:last-modified`, and every value that came back under `:props`. A
property in a namespace without an alias gets the namespace's URI as its
keyword namespace. Give `:namespaces` a table of your own aliases, e.g.
`{"http://owncloud.org/ns" :oc}`.

Writes take the preconditions of HTTP, so two devices can't overwrite
each other's changes:

```clojure
(webdav/call! (webdav/request :put "https://dav.example.org/notes/phone.edn"
                              {:body "{:v 2}" :if-match "\"1\""}))
;; => {:status 204 :etag "\"2\""}
;; or {:status 412} when the document changed in the meantime
```

To keep others out for longer, lock the document, write with the lock's
token, and remove the lock when you're done:

```clojure
(def lock
  (:lock (webdav/call! (webdav/request :lock "https://dav.example.org/notes/phone.edn"
                                       {:scope :exclusive :owner "phone" :timeout 600}))))

(webdav/call! (webdav/request :put "https://dav.example.org/notes/phone.edn"
                              {:body "{:v 3}" :lock-token (:token lock)}))
```

A `:lock` with only the `:lock-token` refreshes the lock, and `:unlock`
removes it. Every other method has options of its own too, e.g. `:patch`
for PROPPATCH, and `:destination` and `:overwrite?` for COPY and MOVE.
The docstring of `webdav/request` lists them. To send the map with
another client, read its answer with `webdav/parse-response`, or give
that client to `webdav/call!` as its `:send`. In a browser, the server
must let the WebDAV methods and headers through CORS and expose the
ETag.

Serving a share
---------------

`server/handler` serves a backend over WebDAV. On the JVM the files
backend serves a directory:

```clojure
(require '[dk.simongray.webdav.files :as files]
         '[dk.simongray.webdav.server :as server]
         '[ring.adapter.jetty :as jetty])

(jetty/run-jetty (server/handler (files/backend "/srv/share") {:root "/dav/"})
                 {:port 8080 :join? false})
```

It keeps the dead properties and the bodies that are being written in a
`.webdav` directory of its own, which no request reaches. A browser that
opens a collection gets a plain page of links to its members. The memory
backend in `dk.simongray.webdav.memory` serves an atom instead, on every
platform, e.g. for tests.

A backend of your own is a map of functions of a path, a vector of
decoded segments below the root. Only `:resource` is required, so this
is a read-only share of a few texts:

```clojure
(def texts
  {"hello.txt" "Hello"
   "bye.txt"   "Bye"})

(def backend
  {:resource (fn [path]
               (cond
                 (empty? path)                                {:collection? true}
                 (and (= 1 (count path)) (texts (first path))) {:etag         (hash (texts (first path)))
                                                                :content-type "text/plain; charset=utf-8"}))
   :children (fn [_]
               (for [[name text] texts]
                 {:name name :etag (hash text)}))
   :content  (fn [[name]]
               (texts name))})
```

Add `:put!`, `:mkcol!`, `:delete!`, `:props` and `:patch-props!` to make
it writable, and `:copy!` and `:move!` when your storage copies and moves
faster than the handler can by itself. The docstring of the `server`
namespace describes each function and the keys of a resource map.

`:put!` gets the body of the request as a stream, so a backend can write
a large file as it comes: an InputStream on the JVM, and a ReadableStream
in ClojureScript. To keep the whole body in memory, read it with
`server/body-bytes`. A document whose resource map has its
`:content-length` also answers a GET of a range of bytes, e.g. for a
video player that seeks.

The handler checks every precondition before it calls a function. A
function refuses with an ex-info, e.g. when a quota is full:

```clojure
(throw (ex-info "The share is full" {:dk.simongray.webdav/status 507}))
```

Give the handler a lock table to take locks too, which macOS Finder needs
before it writes to a share and Microsoft Office before it edits a
document:

```clojure
(server/handler (memory/backend) {:root      "/dav/"
                                  :locks     (atom {})
                                  :principal :user})
```

A write to what a lock covers must then hold the lock: send its token,
as the principal that took it. Here `:principal` gives the `:user` that
your login put in the request. The table lives in memory, since locks
last minutes, and an expired lock simply stops counting.

The options of `server/handler`, listed in `server/default-options`, set
the limits, the share's public host name behind a proxy, the aliases of
namespaces, live properties of your own (e.g. the principal of RFC 5397)
and methods of your own, such as the REPORT of CalDAV. The handler checks
no credentials, so mount it behind your login. Windows sends OPTIONS and
PROPFIND to `/` and to each collection above the share before it maps
it, so when the share isn't at `/`, route those to the handler too.

Not yet
-------

- **WebDAV Sync** ([RFC 6578](https://www.rfc-editor.org/rfc/rfc6578)),
  which CalDAV and CardDAV clients use to fetch only what changed.
- **CalDAV and CardDAV,** which would be libraries of their own on top of
  this one.

Development
-----------

```bash
clojure -X:test                       # the tests on the JVM
npm install                           # once, for the Node tests
clojure -M:cljs compile test          # the tests in Node
clojure -M:cljs compile browser-test  # for a browser, from target/browser-test
dev/litmus.sh                         # litmus against the handler on Jetty
dev/record.sh                         # the answers of Nextcloud and mod_dav, for the tests
```

The litmus script downloads litmus 0.18, builds it into `target/litmus`
(which needs a C compiler), and runs its suites against the handler on
Jetty, over the memory backend and over a directory. The record script
runs Nextcloud and Apache's mod_dav in Docker and records their answers
into `test/resources`. The design and its reasons are in
[doc/proposal.md](doc/proposal.md).

License
-------

The webdav-clj project is licensed under the [MIT licence](LICENSE).
