webdav-clj: a proposal
======================

This proposes webdav-clj, a WebDAV library for Clojure and ClojureScript
with the coordinate dk.simongray/webdav-clj and the root namespace
`dk.simongray.webdav`. It has a client whose requests are plain maps, and
a Ring handler that serves a backend of the app's own. The first release,
0.1.0, is built in this repository and isn't released.

It grows out of the two WebDAV namespaces of like-minded. Both are shaped
around one flat collection of documents, and the store mapping stays in
like-minded. This library covers WebDAV itself, with nested collections,
and works like whatwg-url and websub-clj:

- one cljc codebase with the same results on the JVM, in Node and in
  browsers, on Clojure 1.11 and Java 11 or later
- a short citation of the RFC section beside each rule
- errors as data
- a root namespace of mostly public functions, with internal code under
  `impl` and marked `^:no-doc`
- requests as plain maps, and a Ring handler that takes the app's own
  functions as options
- nothing running in the background, and safe defaults


Scope
-----

| Spec | What webdav-clj does with it |
|---|---|
| [RFC 4918](https://www.rfc-editor.org/rfc/rfc4918) WebDAV | class 1 for client and server: GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, OPTIONS, multistatus and the If header; and class 2, LOCK and UNLOCK, when the handler gets a lock table |
| [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110), section 13 | ETags and conditional requests, evaluated in the order of section 13.2.2 |
| [RFC 8144](https://www.rfc-editor.org/rfc/rfc8144) Prefer in WebDAV | return=minimal for PROPFIND and PROPPATCH, and depth-noroot |
| [RFC 5397](https://www.rfc-editor.org/rfc/rfc5397) current-user-principal | a live property that the app computes, through the handler's :live-properties |
| [RFC 4331](https://www.rfc-editor.org/rfc/rfc4331) quota | the same, e.g. so that Finder and Windows show the free space |
| [RFC 6578](https://www.rfc-editor.org/rfc/rfc6578) WebDAV Sync | later, in this library |
| [RFC 4791](https://www.rfc-editor.org/rfc/rfc4791) CalDAV, [RFC 6352](https://www.rfc-editor.org/rfc/rfc6352) CardDAV | libraries of their own that build on this one |

**Prefer belongs here, and comes in the first release.** RFC 8144 only
changes the answers to PROPFIND, PROPPATCH and REPORT, so it costs a few
lines. It names the reports of CalDAV and CardDAV among those it helps,
and a client that asks for twenty properties of every member gets a much
smaller listing.

**current-user-principal and quota are live properties of the app.** The
principal depends on who is logged in, which only the app knows, and the
quota depends on the storage. The handler takes a map of live properties,
and the principal is the example in its docstring. A principal URL also
needs principal resources (RFC 3744), which belong to CalDAV and CardDAV.

**Sync belongs here too, but later.** The sync-collection report and the
sync-token property are generic WebDAV that CalDAV and CardDAV clients
lean on (DAVx5 and Apple's apps use them where a server offers them). A
server needs a change log in its backend, which is a bigger contract
than anything in class 1. Its REPORT method should come together with
the REPORT dispatch that CalDAV needs.

For like-minded, sync would save little. A collection holds one document
for each device, so a listing at depth one is small. I found no sign that
Nextcloud, Fastmail or a Hetzner storage box answer sync-collection on
plain files. Nextcloud propagates the ETag of a collection when anything
in it changes, which its desktop client relies on instead. So like-minded
could ask for the collection's ETag at depth zero first and skip the
listing when it hasn't changed, where a server does this.


What each client needs
----------------------

Whether a desktop can write to a share depends on class 2:

- **macOS Finder** mounts a class 1 server read-only. Its man page for
  [mount_webdav](https://leancrew.com/all-this/man/man8/mount_webdav.html)
  says so, and an [Apple engineer explained](https://lists.w3.org/Archives/Public/w3c-dist-auth/2002JanMar/0334.html)
  that it locks a file while it's open for writing. With class 2 it also
  asks for the quota properties, and it writes `._` and `.DS_Store` files.
- **Windows Explorer** (the WebClient service) asks for OPTIONS and
  PROPFIND at the root of the server, `/`, even for a share under
  `/dav/`, as [WsgiDAV's guide](https://wsgidav.readthedocs.io/en/latest/user_guide_access.html)
  notes. It only sends Basic credentials over https unless a registry key
  allows it, and it limits files to 50 MB by default. It sets its Win32
  properties with PROPPATCH after a copy, so a backend without dead
  properties may make Explorer report an error. It mounts class 1
  servers, but class 2 is what Microsoft's own examples advertise.
- **Microsoft Office** opens a document read-only without LOCK.
- **GNOME Files (gvfs), KDE Dolphin, davfs2, rclone, Cyberduck and
  WinSCP** read and write class 1 servers. davfs2 uses locks when the
  server offers them.
- **Sync clients** such as like-minded, Joplin, Zotero, KeePassXC and
  Obsidian's Remotely Save need class 1 alone.

So class 1 serves sync clients and the Linux file managers, and class 2
lets Finder and Office write too. The podcast server's `/dav/` is for
like-minded's sync, which takes no locks, so it can leave the lock table
out.


The design
----------

### Paths and hrefs

The handler serves everything under its :root, e.g. `/dav/`. A resource
is named by its path: a vector of decoded segments below the root, e.g.
`["notes" "a b.edn"]`, with `[]` for the root itself.

- **A trailing slash is ignored** when the handler looks a resource up,
  so `/dav/notes` and `/dav/notes/` are the same collection. litmus
  moves a document onto `movecoll/` and then deletes `movecoll`.
- **hrefs are absolute paths,** e.g. `/dav/a%20b.edn`, with a slash after
  a collection. RFC 4918, section 8.3, allows them, and they don't depend
  on a Host header that a proxy may have changed.
- **Each segment is percent-encoded with whatwg-url's component set,**
  the one of JavaScript's encodeURIComponent. It encodes more than a
  path needs (e.g. `;`, which Jetty treats as the start of a path
  parameter), so the same name always gives the same href.
- **Segments are decoded with whatwg-url too.** A segment that decodes
  to `.` or `..`, or holds a slash, a backslash, a control character or
  bytes that aren't UTF-8, gets a 400. Empty segments, as in `a//b`, are
  dropped.
- **A Destination is resolved against the request's URL** with
  whatwg-url. A destination outside the root gets a 502, as section
  9.9.4 suggests for "another sub-section of the same server namespace".
- **A Destination on another host gets a 502** too, since section 10.3
  has a server that can't copy there fail the request. Its host name is
  compared with those of the Host and X-Forwarded-Host headers and the
  handler's :hosts, e.g. the public name behind a proxy that rewrites
  both. The scheme and the port aren't compared, since a proxy that ends
  TLS changes them.

### Names of properties

**A property name is a keyword.** The namespace of the keyword is an
alias from a table of namespace URIs, as in qname-hiccup, e.g.
`:dav/getetag` for `{DAV:}getetag`. The default table has `DAV:` alone,
and both the client and the handler take a :namespaces table of your own,
e.g. `{"urn:ietf:params:xml:ns:caldav" :cal}`.

**A name in a namespace without an alias has the URI as its keyword
namespace,** e.g. `(keyword "http://example.com/ns/" "color")`. That
keeps every name, which a server must store faithfully even when it has
never seen the namespace. A name in no namespace is a plain keyword. The
URI keywords don't read back from EDN, so a backend that keeps
properties as text writes their names with `xml/clark`, e.g.
`{http://example.com/ns/}color`, and their elements with `xml/emit`.

**A property is its element,** e.g. `[:dav/displayname {} "Notes"]`, so
that its attributes (e.g. xml:lang) and any XML inside it survive. The
client's resource maps give each value plainly: the text of a property
that holds only text, or its child elements as Hiccup.

### The client

The client follows like-minded and websub-clj:

```clojure
(webdav/request :propfind "https://dav.example.org/notes/"
                {:depth 1 :props [:dav/getetag :dav/resourcetype]})
;; => {:method :propfind :url "https://dav.example.org/notes/"
;;     :headers {"Depth" "1" "Content-Type" "application/xml; charset=utf-8"}
;;     :body "<?xml …"}
```

- `request` makes the map for any method, with the options of that
  method: e.g. :depth and :props for PROPFIND, :patch for PROPPATCH,
  :if-match for PUT and :destination for COPY. An unknown method gets the
  generic options (:depth, :body and :headers), so a REPORT of CalDAV
  needs no change here.
- `parse-response` reads the response to a request and never throws on
  a status. Its map always has the :status, and the method adds what it
  says, e.g. the :resources of a PROPFIND, the :etag of a PUT, or the
  precondition names of a DAV:error body as :error.
- `call!` sends the request with wary-fetch, or with the :send of your
  own, and returns what `parse-response` returns (in ClojureScript a
  promise of it).

**A resource map is the same on both sides.** A PROPFIND gives maps of
:href, :url, :name, :collection?, :etag, :content-type, :content-length,
:last-modified and :created, plus the :props that came back. The
handler's backend returns maps with the same keys, so a client of
like-minded and its server share their vocabulary.

### The server

```clojure
(server/handler (memory/backend) {:root "/dav/"})
```

**The backend is a map of functions,** like websub-clj's store and
like-minded's stores, rather than a protocol. A map lets a backend leave
out what it can't do: without :put! the share is read-only, and without
:patch-props! PROPPATCH answers 403 for each property. Each function
takes a path:

- `:resource`, the resource map of a path, or nil when nothing is there
- `:children`, the resource maps of a collection's members, each with
  its :name
- `:content`, the body of a document, as Ring takes it
- `:put!`, `:mkcol!` and `:delete!`, which write
- `:props` and `:patch-props!`, the dead properties
- `:copy!` and `:move!`, which are optional: without them the handler
  copies with the functions above, and moves by copying and deleting

**The handler checks before it calls.** It evaluates the preconditions,
the If header, the Overwrite header and whether a parent exists, so a
backend function only does its one job. A `:put!` also gets the :etag
that the handler checked, as the backend gave it, as :if-etag. A backend
that can write conditionally (like like-minded's stores) then closes the
gap between the check and the write.

**A backend refuses with an ex-info,** whose data has the status as
:dk.simongray.webdav/status, e.g. 507 when a quota is full. It can add
the names of precondition elements as :dk.simongray.webdav/error, which
the handler writes as a DAV:error body. That's how CalDAV's
`valid-calendar-data` will reach the client. Any other exception goes to
your middleware.

**The handler is cljc, like websub-clj's hub.** Everything it does is
data in and data out: the XML goes through qname-hiccup, the paths
through whatwg-url and the dates through a few lines of arithmetic. Only
two things differ by platform:

- On the JVM the body of a request is an InputStream, which the handler
  reads within its limit. In ClojureScript the adapter must give bytes
  or a string, as for websub-clj's hub.
- A backend in ClojureScript (e.g. over IndexedDB or node:fs) returns
  promises, so the handler chains every call with wary-fetch's
  `async/then`. On the JVM a backend returns plain values, and `then`
  just applies the function, so it costs nothing there.

The gain is that the client's tests run against the real handler in
Node too, with no network, and that a Node server or a Service Worker
can serve WebDAV with the same code.

**The handler answers at once.** Nothing runs in the background, and
nothing needs starting or stopping.

### Dead and live properties

The handler computes the live properties of RFC 4918 from the resource
map: resourcetype, getetag, getlastmodified, getcontentlength,
getcontenttype, getcontentlanguage and creationdate. They're protected,
so PROPPATCH refuses them with `cannot-modify-protected-property`.
displayname isn't among them: it's an ordinary dead property, which
clients set.

The app adds live properties of its own with :live-properties, a map
from a name to a function of the request, the path and the resource.
They're protected too, they show up for propname, and allprop leaves
them out, as section 9.1 allows for expensive properties. A resource
map's :resource-type adds elements to DAV:resourcetype, e.g.
`[:cal/calendar]`.

PROPPATCH hands the backend every instruction at once, in document
order, so that it can apply them all or none, as section 9.2 requires.

### Conditions

The `conditions` namespace parses and compares ETags, evaluates
If-Match, If-None-Match, If-Modified-Since and If-Unmodified-Since in the
order of RFC 9110, section 13.2.2, and parses and evaluates the If header
of RFC 4918, section 10.4.

- If-Match uses the strong comparison and If-None-Match the weak one, as
  RFC 9110 says. The If header uses the weak one, which section 10.4.4
  allows, so that its example with `W/"A weak ETag"` can match.
- A lock token matches anywhere in the scope of its lock, an unmapped URL
  included, so a PUT of a new member of a locked collection can submit
  the collection's token untagged. Without a lock table no token matches,
  and `(Not <DAV:no-lock>)` is always true.
- A tagged list names its resource by URL. The handler resolves it as it
  resolves a Destination, and an unmapped URL is a resource without the
  ETag, as section 10.4.4 says.

The ETag of a resource map is an entity tag, e.g. `"\"v1\""` or
`W/"v1"`. Any other value is quoted, so a backend can give a version
number, as like-minded's numbered stores do.

### Locks

**The lock table is an atom, given as :locks.** Without it the handler is
class 1. It's a map of locks by token, which the handler swaps
atomically, so two LOCKs can't both win, and nothing runs in the
background: an expired lock simply stops counting, and the next LOCK
drops it. The table lives in memory rather than in the backend, since
locks last minutes (an hour at most, by :max-lock-seconds) and a client
takes a new one after a restart. A share served by several processes
needs them to share the atom, or one handler in front.

**A lock covers paths.** It covers its root, and with depth infinity
everything below it, mapped or not. A write must hold a lock that covers
each path it changes: the resource itself and, when it adds or removes a
member, the collection above it. A DELETE or a MOVE also changes the
locked resources below what it removes. Holding a lock means submitting
its token in the If header, as the :principal that took it (section
6.4), which is a function of the request, e.g. of the user that the login
found.

**The order of the checks follows litmus.** A failed If header is a 412
before a lock without its token is a 423, so `(<DAV:no-lock> [etag])`
gets the 412 that litmus expects.

**A LOCK of an unmapped URL makes an empty document,** as section 9.10.4
says, so the backend needs :put! for it. A DELETE destroys the locks of
what it deletes, a MOVE leaves its locks behind, and a COPY doesn't copy
them, as sections 7.6 and 9.6 say.

### XML

qname-hiccup reads and writes every body. Its `uri->alias` table can be
a function, which the library uses to give every namespace a keyword: an
alias from the table, or else the URI itself. When it writes, qname-hiccup
gives each URI a prefix of its own (`ns1`, `ns2` and so on), and it
writes the document on one line, so that a dead property's value reads
back as it was.

A body is refused with a 400 when qname-hiccup throws, e.g. for a prefix
bound to an empty URI, which Namespaces in XML forbids and litmus tests.
It's refused too when its report has an undeclared prefix. The parser's
limits (no external entities, nesting at most 256 deep) apply to every
body.

### Safety defaults

- **PROPFIND at depth infinity gets a 403** with `propfind-finite-depth`,
  as section 9.1.1 allows. A missing Depth means infinity. The option
  :propfind-infinity? turns it on.
- **A PUT body holds at most 32 MB** (:max-bytes), since it's read into
  memory, and a 413 says so before the body is read when the
  Content-Length is larger.
- **An XML body holds at most 1 MB** (:max-xml-bytes).
- **A PUT with a Content-Range gets a 400,** as RFC 9110, section 14.5,
  asks of a server without partial PUT, so a resumed upload can't write
  its part as the whole file.
- **DELETE of the root gets a 403,** so one request can't empty a share.
- **No path escapes the root.** `..`, `%2e%2e`, encoded slashes and
  backslashes, control characters and invalid UTF-8 all get a 400, and a
  Destination outside the root or on another host gets a 502.
- **A namespace URI that's the name of an alias gets a 400,** e.g. a
  property in the namespace `dav`, which would otherwise pass for one in
  `DAV:`.
- **COPY and MOVE refuse a destination inside the source** with a 403,
  since section 9.8.3 warns of the infinite recursion.
- **The handler checks no credentials.** Mount it behind your login,
  as with like-minded's handler.
- **Errors say little.** A 400 gives a short reason in plain text. Any
  exception other than a refusal goes to your middleware, which decides
  what the client sees.


Changes to other libraries
--------------------------

**qname-hiccup** 0.5.0 took four changes, which removed code from
webdav-clj:

1. `uri->alias` can be a function, and the empty URI of no namespace is
   never looked up in it.
2. `emit` gives a keyword namespace with a colon, i.e. a URI, a prefix of
   its own.
3. A prefix bound to an empty URI is malformed, as section 3 of
   Namespaces in XML says, rather than an unknown namespace.
4. An :indent of false writes a document on one line. Before, `emit` put
   each element on a line of its own, so a dead property whose value was
   `<a/><b/>` came back with line breaks between them.

**wary-fetch** has a reader of HTTP dates in `wary-fetch.date`, which is
marked `^:no-doc`. webdav-clj uses it anyway and writes the dates itself.
Making it public, with a writer, would make that a supported dependency.

**whatwg-url** needs no change.


like-minded
-----------

like-minded would take webdav-clj as a dependency and keep only the
mapping between its stores and WebDAV.

**store/webdav.cljc** would delete:

- `propfind-body`, `uri->alias`, `document-name` and `entries`, which
  `webdav/request` and `webdav/parse-response` replace
- the request maps in `request`, which become calls of `webdav/request`
  with :depth, :props, :if-match and :if-none-match

It would keep `calls`, the content types, `store` and `host`. Its
`response` shrinks to a mapping from webdav-clj's maps to the store's,
e.g. a listing becomes this:

```clojure
(->> (:resources (webdav/parse-response request response))
     (remove :collection?)
     (mapv (fn [{:keys [name etag]}] {:name name :version etag})))
```

like-minded keeps its own rule of throwing ::failed for a status it
doesn't expect, since webdav-clj returns every status as data.

Against mod_dav, a version read within a second of a write is a weak
ETag, which never passes If-Match, since If-Match uses the strong
comparison. The put then fails as a conflict, and like-minded's next
sync reads a strong ETag, so it costs a round and nothing more.

**store/webdav/server.clj** would delete its multistatus, preconditions,
OPTIONS, PROPFIND and the methods, close to all of its 331 lines. It
would keep a function that turns a store into a backend:

- the root `[]` is the collection, and `[name]` a document
- `:resource` and `:children` come from the store's :list, or from
  :fetch when it has none, with the version as the ETag
- `:content` comes from :fetch, as text or bytes by the content type
- `:put!` calls the store's :put with :if-version from :if-etag, and
  turns a conflict into a 412
- `:delete!` calls :delete
- `:mkcol!` is left out, so MKCOL answers 405 as before

Its `handler` keeps its signature and calls `server/handler` with that
backend, :root and :max-bytes. A prototype of the two, 54 lines in all,
passed the checks of like-minded's `serving-a-store` test over its memory
store and its numbered store, apart from the two changes below that
those checks see. A few answers change:

- A path within the collection, e.g. `/dav/sub/x.edn`, gets a 409 for
  the missing parent rather than a 400.
- PROPPATCH gets a 207 with a 403 for each property rather than a 405.
- COPY and MOVE work, by fetching and putting.
- The Allow header follows the target, so the collection doesn't list
  GET, and it names PROPPATCH, COPY and MOVE too.

A PROPFIND at depth infinity, or without a Depth, gets the listing today,
since the collection holds no collection. like-minded's `handler` should
pass :propfind-infinity? true to keep that, which is safe for a share
that's one level deep.


The podcast server
------------------

Nothing has to change while like-minded's `handler` keeps its signature.
Two changes would help:

- **Move the quota into the backend.** `within-quota` parses the URI a
  second time and counts a PUT without a Content-Length as 32 MB. A
  `:put!` that wraps the folder store's sees the exact bytes, and throws
  a 507 with :dk.simongray.webdav/status when the folder is full.
- **Give the quota as live properties,** with :live-properties, so that
  a client can show how full the folder is.

The `folders` interceptor stays, since Pedestal's router still doesn't
match the trailing slash of `/dav/`.


CalDAV and CardDAV
------------------

I recommend libraries of their own, caldav-clj and carddav-clj (with the
roots `dk.simongray.caldav` and `dk.simongray.carddav`), on webdav-clj
and ical-clj. ical-clj's proposal leaves CalDAV to another library, and
it already has CalDAV's `expand` in mind. webdav-clj then stays neutral,
and neither needs ical-clj's release cadence.

The extension points are in the first release:

- the handler's :methods, for REPORT, MKCALENDAR and extended MKCOL
  (RFC 5689)
- :live-properties, for calendar-home-set,
  supported-calendar-component-set, getctag and current-user-principal
- the resource map's :resource-type, for `calendar`, `addressbook` and
  `principal`
- :compliance, for the DAV header's `calendar-access` and `addressbook`
- the ex-info data :dk.simongray.webdav/error, for preconditions such as
  `valid-calendar-data` and `no-uid-conflict`
- the `xml` namespace, which reads and writes the multistatus of a
  REPORT and the `prop` element inside calendar-query
- the `conditions` namespace, since CalDAV clients write with If-Match
  and If-None-Match: * all the time
- `request` and `parse-response` for any method, for the client side of
  calendar-query and calendar-multiget

What's missing is sync, which both libraries need, and a shared way to
dispatch REPORT by the name of its root element. Both belong in
webdav-clj's next release but one. ical-clj suggests testing CalDAV with
caldav-server-tester, which would do for CalDAV what litmus does here.


Tests
-----

### litmus

litmus is Joe Orton's WebDAV test suite. It isn't in Homebrew, and
Docker wasn't running here, so `dev/litmus.sh` builds it from source:

1. It downloads litmus 0.18 (28 June 2026) from
   [notroj.github.io/litmus](https://notroj.github.io/litmus/), checks
   its SHA-256 and builds it with its own copy of neon, without TLS, into
   `target/litmus`.
2. It starts the handler over a memory backend and a lock table on Jetty
   at `http://localhost:8765/dav/` with `clojure -M:litmus`.
3. It runs the basic, copymove, props, locks and http suites and stops
   the server.

To repeat the run, install a C compiler (the Xcode command-line tools on
macOS) and run `dev/litmus.sh`. It takes about 20 seconds, and the CI
workflow runs it too. On 13 October 2026, with Jetty 12.1.0 on Java 24
and macOS, every suite passed without a warning:

| Suite | Result |
|---|---|
| basic | 16 of 16 passed |
| copymove | 13 of 13 passed |
| props | 33 of 33 passed |
| http | 4 of 4 passed |
| locks | 40 of 40 passed |

The memory backend has a `:move!` of its own but no `:copy!`, so litmus
tests the handler's own copying, dead properties included.

### Local tests

- **The client's requests and readers,** against the recorded answers of
  Nextcloud and Apache's mod_dav, and a listing written by hand in the
  default namespace with absolute hrefs, in `test/resources/webdav`.
- **The handler,** method by method over the memory backend, with the
  litmus cases that don't need a network, and its locks: of documents and
  collections, shared and exclusive, by principal and until they expire.
- **The client against the handler,** in-process on every platform, and
  over HTTP on the JVM through `com.sun.net.httpserver`, which Java 11
  has, so the floor job needs no Jetty.
- **The conditions, paths and XML,** e.g. the examples of RFC 4918,
  section 10.4.

They pass on the JVM, in Node (also compiled with advanced
optimizations) and in Chromium, where the XML goes through the browser's
own DOMParser rather than xmldom's.

A second reading of the handler against the RFCs found defects that
litmus doesn't reach, e.g. a Destination on another host that was copied
locally, and an overwrite that went ahead when deleting the destination
failed. Each one is fixed and has a test.

### Recorded answers

`dev/record.sh` runs Nextcloud 34.0.4 and Apache 2.4.69 with mod_dav in
Docker, on localhost alone, and records their answers to a listing, a
PROPPATCH, a failing precondition and, from mod_dav, a LOCK. The tests
check what stays the same from one recording to the next, e.g. names and
sizes rather than ETags, so recording again needs no change to them. A
hosted Fastmail or Hetzner storage box would cost money, so they wait.

The real answers differed from what their documentation had led me to
write:

- Nextcloud takes a PROPPATCH of DAV:getetag with a 200, and its type of
  a document comes from the name, whatever the Content-Type of the PUT.
- mod_dav refuses a protected property with a 409 rather than a 403, and
  depth infinity with a page of HTML rather than a DAV:error.
- mod_dav's ETags are weak for a second after a write.
- mod_dav's answer to a LOCK has no DAV:lockroot, its token is an
  opaquelocktoken, and a document's lock has the depth infinity that the
  LOCK asked for.


Nice-to-haves
-------------

- **Streaming bodies.** The handler reads a PUT into memory, so 32 MB is
  the default limit. A backend over files would rather take the stream,
  and GET would like Range requests (e.g. for video).
- **A backend over the file system** on the JVM, with dead properties in
  a file beside each resource. It would make a share in a few lines and
  be the obvious backend for litmus too.
- **A plain HTML index** for GET on a collection, which answers 405
  today.
- **Accepting Windows' Win32 properties** in a backend without dead
  properties, so that Explorer copies without an error.
- **An answer to OPTIONS and PROPFIND at `/`** for Windows, when the
  share is mounted elsewhere, e.g. at `/dav/`.
- **The Content-Range of a partial PUT** (Apache's extension), which
  rclone and Cyberduck use for resumable uploads.


Decisions
---------

The questions of this proposal, as Simon settled them on 13 October
2026:

1. **Property names are keywords,** with aliases for the namespaces you
   know and the URI as the keyword namespace for the rest. Clark strings
   are for storage alone, through `xml/clark`.
2. **Class 2 comes next.** It lets Finder and Office write, and litmus's
   locks suite is ready to test it. Sync waits for CalDAV, which waits
   for ical-clj. Since there'll be no release for each part, class 2 is
   in the first one, and it passes litmus's locks suite.
3. **CalDAV and CardDAV get libraries of their own,** caldav-clj and
   carddav-clj, on webdav-clj and ical-clj.
4. **:max-bytes stays at 32 MB** while bodies are held in memory, and
   grows once they stream.
5. **The recorded answers come from containers on localhost,** Nextcloud
   and mod_dav, which cost nothing. Fastmail and Hetzner wait.
6. **qname-hiccup changes first,** at least writing on one line and
   generating prefixes, so that webdav-clj's release doesn't carry its
   own prefix code and dead properties keep their exact values. They
   came in qname-hiccup 0.5.0, which webdav-clj now takes.
7. **like-minded's `handler` keeps its signature** and passes
   :propfind-infinity? true, so the podcast server needs no change.
8. **The Destination's host is compared,** as RFC 4918 requires, with
   :hosts for a proxy that rewrites the Host header and sets no
   X-Forwarded-Host.
