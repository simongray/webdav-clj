(ns dk.simongray.webdav.files
  "A backend over a directory of the file system, on the JVM.

  A path below the root of the share is the file or directory of the same
  path below the directory. The backend keeps what it needs of its own in
  a directory named .webdav at the top, which no request reaches and no
  listing shows:

  - props, the dead properties of each resource as an XML file, in a tree
    that mirrors the share, e.g. props/notes.d/a.txt.xml for notes/a.txt,
    and root-props.xml beside it for the root's
  - tmp, the bodies of PUTs while they're written

  A PUT is written to a file in tmp and then moved into place, so a body
  that's refused or broken off changes nothing. A MOVE renames the file or
  the directory, properties and all, and a COPY goes through the handler's
  own copying. A new name can be 251 bytes long, so that its properties'
  file fits beside it.

  An ETag is the size, the time of the last change and the inode of a
  file, where the file system has inodes. Each PUT makes a new file, so
  its ETag differs from the last one even within one tick of the clock.

  Some paths count as nothing there, nor what's below them:

  - a symbolic link, wherever it leads, and a FIFO or a device
  - the backend's own directory by any name
  - a name that the file system takes for another, e.g. A.txt for a.txt
    on one that ignores case, as macOS's does, so a MOVE can't rename a
    file by its case alone

  One process serves a directory, and nobody else writes to it, since a
  local user who swaps a directory for a link while a request is under
  way can make the backend write where the link leads."
  (:require [clojure.java.io :as io]
            [dk.simongray.webdav.xml :as xml])
  (:import [java.io InputStream IOException]
           [java.net URLConnection]
           [java.text Normalizer Normalizer$Form]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files
            FileSystemException FileVisitOption InvalidPathException LinkOption
            OpenOption Path StandardCopyOption]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.util Date UUID]
           [java.util.concurrent TimeUnit]))

(def own-dir
  "The name of the directory at the top of the share that the backend
  keeps for itself."
  ".webdav")

(defn- refuse
  "Throw a refusal with the `status`, for the `message`."
  [status message]
  (throw (ex-info message {:dk.simongray.webdav/status status})))

(defn- resolved
  "The file of the share's `path` below the directory `root`, a real path."
  ^Path [^Path root path]
  (reduce #(.resolve ^Path %1 ^String %2) root path))

;; A second name for a file, e.g. A.txt for a.txt on a file system that
;; ignores case, .WEBDAV for the backend's own directory, or a symbolic
;; link, makes two resources of one file for the handler, so that a MOVE
;; from one to the other deletes the file as the destination it
;; overwrites. A link can also lead out of the directory. The two Unicode
;; forms of a name are one name, which Java on macOS gives in NFC.
(defn- own-name?
  "Whether the file `f` below the directory `root`, or its directory when
  it isn't there, has that name on disk, with no symbolic link on the way."
  [^Path root ^Path f]
  (let [no-links (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
        existing (if (Files/exists f no-links) f (.getParent f))
        on-disk  (.toRealPath existing no-links)
        nfc      #(Normalizer/normalize (str (.relativize root ^Path %)) Normalizer$Form/NFC)]
    (and (= (nfc existing) (nfc on-disk))
         (= on-disk (.toRealPath existing (make-array LinkOption 0))))))

(defn- file-of
  "The file of the share's `path` below the directory `root`, or nil when
  it's in the backend's own directory, has another name on disk, is or
  is below a symbolic link, or can't be there, e.g. below a document."
  ^Path [^Path root path]
  (when-not (= own-dir (first path))
    (try
      (let [f (resolved root path)]
        (when (own-name? root f)
          f))
      (catch FileSystemException _ nil)
      (catch InvalidPathException _ nil))))

(defn- writable
  "The file of the share's `path` below `root`, or else a refusal of 403."
  ^Path [root path]
  (or (file-of root path)
      (refuse 403 "That path is out of reach")))

;; the properties of a resource are in a file of its name and .xml
(defn- creatable
  "The file of the share's `path` below `root` for a new resource, or else
  a refusal: of 400 when its name is too long to keep its properties
  beside it, or as for writable."
  ^Path [root path]
  (when (< (- 255 (count ".xml")) (count (.getBytes ^String (peek (vec path)) "UTF-8")))
    (refuse 400 "The name is too long"))
  (writable root path))

(defn- attributes
  "The basic attributes of the file `f`, or nil when it isn't there or
  can't be, e.g. below a document."
  ^BasicFileAttributes [^Path f]
  (try
    (Files/readAttributes f BasicFileAttributes ^"[Ljava.nio.file.LinkOption;" (make-array LinkOption 0))
    (catch FileSystemException _ nil)))

;; a GET of a FIFO or a device would wait or read forever
(defn- resource
  "The resource map of the file `f`, with its content type by the function
  `content-type` of its name, or nil when it isn't there or is neither a
  regular file nor a directory."
  [^Path f content-type]
  (let [a (attributes f)]
    (cond
      (nil? a)
      nil

      (.isDirectory a)
      {:collection?   true
       :created       (Date. (.toMillis (.creationTime a)))
       :last-modified (Date. (.toMillis (.lastModifiedTime a)))}

      (.isRegularFile a)
      (let [modified (.lastModifiedTime a)]
        (cond-> {:etag           (str "\"" (Long/toHexString (.size a)) "-"
                                      (Long/toHexString (.to modified TimeUnit/NANOSECONDS))
                                      (some->> (.fileKey a) .hashCode Integer/toHexString (str "-"))
                                      "\"")
                 :content-length (.size a)
                 :created        (Date. (.toMillis (.creationTime a)))
                 :last-modified  (Date. (.toMillis modified))}
          (content-type (str (.getFileName f)))
          (assoc :content-type (content-type (str (.getFileName f))))))

      :else
      nil)))

;; a collection's directory ends in .d and a file of properties in .xml,
;; so that the properties of a and of the members of a.xml stay apart
(defn- props-dir
  "The directory of the dead properties of the members of the collection
  at the share's `path` below `root`."
  ^Path [^Path root path]
  (resolved root (into [own-dir "props"] (map #(str % ".d")) path)))

(defn- props-file
  "The file of the dead properties of the share's `path` below `root`.
  That of the root is beside the tree of the others, so that no name of
  a resource can reach it."
  ^Path [^Path root path]
  (if (empty? path)
    (resolved root [own-dir "root-props.xml"])
    (.resolve (props-dir root (pop (vec path))) (str (peek path) ".xml"))))

(defn- delete-tree!
  "Delete the file or directory `f` and everything in it, if it's there,
  the deepest first."
  [^Path f]
  (when (Files/exists f (make-array LinkOption 0))
    (let [found (with-open [walk (Files/walk f (make-array FileVisitOption 0))]
                  (vec (iterator-seq (.iterator walk))))]
      (doseq [^Path p (sort-by #(.getNameCount ^Path %) > found)]
        (Files/deleteIfExists p)))))

(defn- move!
  "Move the file or directory `from` to `to`, atomically where the file
  system can, making the directories above `to`."
  [^Path from ^Path to]
  (when (Files/exists from (make-array LinkOption 0))
    (Files/createDirectories (.getParent to) (make-array FileAttribute 0))
    (try
      (Files/move from to (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (catch AtomicMoveNotSupportedException _
        (Files/move from to (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))))

(defn- temp-file
  "A new file for a body in the backend's tmp directory below `root`."
  ^Path [^Path root]
  (let [dir (resolved root [own-dir "tmp"])]
    (Files/createDirectories dir (make-array FileAttribute 0))
    (.resolve dir (str (UUID/randomUUID)))))

(defn- write-temp!
  "Write the `body`, a stream or bytes, to a new temporary file below
  `root`, and return it. A body that fails, e.g. past a limit, leaves no
  file behind."
  ^Path [root body]
  (let [tmp (temp-file root)]
    (try
      (if (instance? InputStream body)
        (with-open [in ^InputStream body]
          (Files/copy in tmp ^"[Ljava.nio.file.CopyOption;" (make-array CopyOption 0)))
        (Files/write tmp ^bytes body ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0)))
      tmp
      (catch Throwable e
        (Files/deleteIfExists tmp)
        (throw e)))))

(defn- install!
  "Move the temporary file `tmp` to `f`, or delete it when that fails."
  [^Path tmp ^Path f]
  (try
    (move! tmp f)
    (catch Throwable e
      (Files/deleteIfExists tmp)
      (throw e))))

;; a file deleted by another program leaves its properties behind, which a
;; new resource of the same name mustn't inherit
(defn- delete-props!
  "Delete the dead properties of the share's `path` below `root` and those
  of its members."
  [root path]
  (Files/deleteIfExists (props-file root path))
  (delete-tree! (props-dir root path)))

(defn- read-props
  "The elements of the dead properties in the file `f`, by `namespaces`."
  [^Path f namespaces]
  (when (Files/exists f (make-array LinkOption 0))
    (drop 2 (xml/parse (slurp (.toFile f) :encoding "UTF-8") namespaces))))

(defn- write-props!
  "Write the dead properties `elements` to the file `f` below `root`, by
  `namespaces`, or delete it when there are none."
  [root ^Path f elements namespaces]
  (if (empty? elements)
    (Files/deleteIfExists f)
    (let [tmp (write-temp! root (.getBytes ^String (xml/emit (into [:dav/props {}] elements) namespaces)
                                           "UTF-8"))]
      (install! tmp f))))

(defn- patched
  "The dead properties `elements` after the PROPPATCH operations `ops`."
  [elements ops]
  (vals (reduce (fn [m [kind x]]
                  (case kind
                    :set    (assoc m (first x) x)
                    :remove (dissoc m x)))
                (into (array-map) (map (juxt first identity)) elements)
                ops)))

(defn backend
  "A backend over the directory `dir` (a path or a File), which must be
  there, with the `opts` below:

  - :namespaces, the aliases of namespaces in property names, the same as
    the handler's
  - :content-type, a function of a file's name that returns its media
    type or nil, by default URLConnection's guess from its extension

  It has every function of a backend but :copy!."
  ([dir]
   (backend dir {}))
  ([dir {:keys [namespaces content-type]
         :or   {content-type #(URLConnection/guessContentTypeFromName %)}
         :as   opts}]
   (let [root (.toRealPath (.toPath (io/file dir)) (make-array LinkOption 0))
         lock (Object.)
         own  (map #(resolved root %) [[own-dir] [own-dir "props"] [own-dir "tmp"]])]
     (when-let [link (first (filter #(Files/isSymbolicLink %) own))]
       (throw (ex-info (str link " is a symbolic link, which the backend won't follow")
                       {:path (str link)})))
     ;; made now, so that no request can make it first by another name; a
     ;; directory that can't be written to has nothing to keep there. Any
     ;; temporary file is left from a process that stopped in a PUT.
     (try
       (delete-tree! (resolved root [own-dir "tmp"]))
       (Files/createDirectories (.resolve root ^String own-dir) (make-array FileAttribute 0))
       (catch IOException _))
     {:resource     (fn [path]
                      (some-> (file-of root path) (resource content-type)))
      :children     (fn [path]
                      (when-let [f (file-of root path)]
                        (with-open [entries (Files/list f)]
                          (->> (iterator-seq (.iterator entries))
                               (keep (fn [^Path p]
                                       (let [name (str (.getFileName p))]
                                         (when-not (and (empty? path) (= own-dir name))
                                           (some-> (file-of root (conj (vec path) name))
                                                   (resource content-type)
                                                   (assoc :name name))))))
                               (sort-by :name)
                               vec))))
      :content      (fn [path]
                      (Files/newInputStream (writable root path) (make-array OpenOption 0)))
      ;; the path is checked again once the body is in, since a directory
      ;; on the way may have become a link while it came
      :put!         (fn [path body {:keys [if-etag] :as opts}]
                      (creatable root path)
                      (let [tmp (write-temp! root body)]
                        (try
                          (locking lock
                            (let [f       (creatable root path)
                                  current (resource f content-type)]
                              (when (and (contains? opts :if-etag) (not= if-etag (:etag current)))
                                (refuse 412 "The document changed"))
                              (when-not current
                                (delete-props! root path))
                              (move! tmp f)
                              (resource f content-type)))
                          (catch Throwable e
                            (Files/deleteIfExists tmp)
                            (throw e)))))
      :mkcol!       (fn [path]
                      (let [f (creatable root path)]
                        (locking lock
                          (delete-props! root path)
                          (Files/createDirectory f (make-array FileAttribute 0))))
                      nil)
      :delete!      (fn [path]
                      (locking lock
                        (delete-tree! (writable root path))
                        (delete-props! root path))
                      nil)
      :move!        (fn [from to]
                      (let [source      (writable root from)
                            destination (creatable root to)]
                        (locking lock
                          (delete-props! root to)
                          (move! source destination)
                          (move! (props-file root from) (props-file root to))
                          (move! (props-dir root from) (props-dir root to))))
                      nil)
      :props        (fn [path]
                      (read-props (props-file root path) namespaces))
      :patch-props! (fn [path ops]
                      (locking lock
                        (let [f (props-file root path)]
                          (write-props! root f (patched (read-props f namespaces) ops) namespaces)))
                      nil)})))

(comment
  (def b (backend "/tmp/share"))
  ((:children b) [])
  #_.)
