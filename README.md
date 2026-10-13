webdav-clj
==========

[![Clojars Project](https://img.shields.io/clojars/v/dk.simongray/webdav-clj.svg)](https://clojars.org/dk.simongray/webdav-clj)

A [WebDAV](https://en.wikipedia.org/wiki/WebDAV) client and server for
Clojure and ClojureScript. Talk to Nextcloud, Fastmail or any other
WebDAV server, or serve your own storage as a share that file managers
can mount.

- **Plain data.** Requests and answers are maps, whatever the status, and
  property names are keywords such as `:dav/getetag`.
- **Your own storage.** A backend is a map of a few functions over a
  database, a directory or an atom. The
  [Ring](https://github.com/ring-clojure/ring/wiki/Concepts) handler takes
  care of the rest:
  [ETags](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag),
  [preconditions](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Conditional_requests),
  locks, copying and moving.
- **Safe by default.** Paths stay inside the share, every body, property
  and lock has a limit, and uploaded documents can't run scripts.
- **Tested against litmus.** The handler passes every test of
  [litmus](https://notroj.github.io/litmus/), the classic WebDAV test
  suite.
- **One codebase.** The same code runs on the JVM, in Node and in the
  browser.

> This library was spun out of
> [like-minded](https://github.com/simongray/like-minded), which syncs
> app state through WebDAV collections. Like like-minded, it was developed
> with assistance from frontier LLMs.

Getting started
---------------

It requires Clojure 1.11+ and Java 11+. Add it from
[Clojars](https://clojars.org/dk.simongray/webdav-clj) to the `:deps` in
your [`deps.edn`](https://clojure.org/guides/deps_and_cli):

```clojure
dk.simongray/webdav-clj {:mvn/version "0.1.1"}
```

For unreleased changes, depend on the latest commit on `master` instead.
In [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html#deps-edn),
a Git dependency only works through `deps.edn`, so set `:deps true` in
your `shadow-cljs.edn` too. To read XML in Node, set
`globalThis.DOMParser` to a
[DOMParser](https://developer.mozilla.org/en-US/docs/Web/API/DOMParser),
e.g. the one from [`@xmldom/xmldom`](https://github.com/xmldom/xmldom).

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

Each resource has keys for its
[live properties](https://sabre.io/dav/properties/), e.g. `:etag` and
`:last-modified`, and every value that came back is in `:props`. An
[XML namespace](https://en.wikipedia.org/wiki/XML_namespace) without an
alias keeps its URI as the keyword namespace, so give `:namespaces`
aliases of your own, e.g. `{"http://owncloud.org/ns" :oc}`.

Writes take the preconditions of HTTP, so two devices can't overwrite
each other's changes:

```clojure
(def phone "https://dav.example.org/notes/phone.edn")

(webdav/call! (webdav/request :put phone {:body "{:v 2}" :if-match "\"1\""}))
;; => {:status 204 :etag "\"2\""}
;; or {:status 412} when the document changed in the meantime
```

To keep others out for longer, lock the document and write with the
lock's token:

```clojure
(def lock
  (-> (webdav/request :lock phone {:scope   :exclusive
                                   :owner   "phone"
                                   :timeout 600})
      webdav/call!
      :lock))

(webdav/call! (webdav/request :put phone {:body       "{:v 3}"
                                          :lock-token (:token lock)}))
```

A `:lock` with only the `:lock-token` refreshes the lock, and `:unlock`
removes it. The docstring of `webdav/request` lists the options of every
method, e.g. `:patch` for PROPPATCH and `:destination` for COPY and MOVE.
To send the map with another HTTP client, give it to `webdav/call!` as
`:send`, or read its answer with `webdav/parse-response`. In a browser,
the server must let the WebDAV methods and headers through
[CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/CORS) and
expose the ETag.

A resource only has a `:url` on the
[origin](https://developer.mozilla.org/en-US/docs/Glossary/Origin) of the
request, since a request to it carries your credentials. When the URL of a share comes
from a user, give `webdav/request` an `:allow-pred` of wary-fetch's
`url/public?`, so that neither the URL nor a redirect reaches your own
network.

Serving a share
---------------

Serve a backend over WebDAV with `server/handler`. On the JVM, the files
backend serves a directory:

```clojure
(require '[dk.simongray.webdav.files :as files]
         '[dk.simongray.webdav.server :as server]
         '[ring.adapter.jetty :as jetty])

(jetty/run-jetty (server/handler (files/backend "/srv/share") {:root "/dav/"})
                 {:port 8080 :join? false})
```

It keeps [dead properties](https://sabre.io/dav/properties/) and
unfinished uploads in a `.webdav` directory that no request reaches, and
it follows no symbolic links. Nothing else
should write to the directory while it's served. For tests, the memory
backend in `dk.simongray.webdav.memory` keeps a share in an atom
instead.

A backend of your own is a map of functions of a path, a vector of
decoded segments below the root. Only `:resource` is required, so this
is a read-only share of a few texts:

```clojure
(def texts
  {"hello.txt" "Hello"
   "bye.txt"   "Bye"})

(defn text-resource
  "The resource map of the text `s`."
  [s]
  {:etag (hash s) :content-type "text/plain; charset=utf-8"})

(def backend
  {:resource (fn [path]
               (cond
                 (empty? path)      {:collection? true}
                 (= 1 (count path)) (some-> (texts (first path)) text-resource)))
   :children (fn [_]
               (for [[name text] texts]
                 (assoc (text-resource text) :name name)))
   :content  (fn [[name]]
               (texts name))})
```

Add `:put!`, `:mkcol!`, `:delete!`, `:props` and `:patch-props!` to make
it writable, and `:copy!` and `:move!` if your storage does those faster
than the handler can. The docstring of the `server` namespace describes
each function and the keys of a resource map.

The body of a PUT reaches `:put!` as a stream, an
[InputStream](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/InputStream.html)
on the JVM and a
[ReadableStream](https://developer.mozilla.org/en-US/docs/Web/API/ReadableStream)
in ClojureScript, so a large file can be written as it arrives, or read
into memory with `server/body-bytes`. A document whose resource map has
a `:content-length` also answers requests for a
[range of bytes](https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Range_requests),
e.g. from a video player that seeks.

The handler checks every precondition before it calls a function, and a
function refuses with an
[`ex-info`](https://clojuredocs.org/clojure.core/ex-info), e.g. when a
quota is full:

```clojure
(throw (ex-info "The share is full" {:dk.simongray.webdav/status 507}))
```

Give the handler a lock table to take locks too, which
[macOS Finder](https://sabre.io/dav/clients/finder/) needs before it
writes to a share and Microsoft Office before it edits a document:

```clojure
(server/handler (memory/backend) {:root      "/dav/"
                                  :locks     (atom {})
                                  :principal :user})
```

A write to what a lock covers must then send the lock's token, as the
principal that took it: here, the `:user` that your login put in the
request.

The handler checks no credentials, so mount it behind your login. The
options in `server/default-options` set the limits, the share's public
host name behind a proxy, aliases of namespaces, and live properties and
methods of your own, e.g. the REPORT of
[CalDAV](https://en.wikipedia.org/wiki/CalDAV).
[Windows](https://sabre.io/dav/clients/windows/) sends OPTIONS and
PROPFIND to `/` and to each collection above a share before it maps it,
so if the share isn't at `/`, route those to the handler too.

Not yet
-------

- **[WebDAV Sync](https://sabre.io/dav/building-a-caldav-client/#speeding-up-sync-with-webdav-sync),**
  which CalDAV and [CardDAV](https://en.wikipedia.org/wiki/CardDAV)
  clients use to fetch only what changed.
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

The litmus script builds litmus 0.18 into `target/litmus`, which needs a
C compiler, and runs it against the handler over memory and over a
directory. The record script needs Docker. The design and its reasons
are in [doc/proposal.md](doc/proposal.md).

License
-------

The webdav-clj project is licensed under the [MIT licence](LICENSE).
