;; 职责：安全目录操作。优先 SecureDirectoryStream；macOS 等无此接口时用 NOFOLLOW 路径操作，拒绝跟随符号链接。
;; 核心入口：with-directory、delete-tree!、with-cleanup。

(ns arch-view.context.secure-fs
  (:import [java.io Closeable]
           [java.nio.file CopyOption Files Path SecureDirectoryStream LinkOption
            StandardCopyOption NoSuchFileException FileAlreadyExistsException]
           [java.nio.file.attribute BasicFileAttributes BasicFileAttributeView
            FileAttribute PosixFileAttributeView PosixFilePermissions]))

(def ^:dynamic *force-path-ops* false)
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private dir-mode (PosixFilePermissions/fromString "rwx------"))
(def ^:private posix-dir-attrs
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute dir-mode)]))
(def ^:private atomic-move
  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))

(defprotocol Dir
  (dir-path [d])
  (entry-attrs [d name])
  (open-child [d name])
  (child-names [d])
  (byte-channel [d name options attrs])
  (delete-file [d name])
  (delete-directory [d name])
  (create-dir [d name])
  (move-to [d from dest dest-name])
  (set-permissions [d perms]))

(declare ^:private ->stream-dir ^:private ->path-dir ^:private real-directory!
         ^:private open-dir ^:private open-named ^:private delete-entry!)

(defn- fail! [message]
  (throw (ex-info message {:status 502})))

(defn- stream-capable? []
  (try
    (let [path (.toPath (java.io.File. (System/getProperty "java.io.tmpdir")))]
      (with-open [stream (Files/newDirectoryStream path)]
        (instance? SecureDirectoryStream stream)))
    (catch Exception _ false)))

(def ^:private stream-capable (delay (stream-capable?)))

(defn- use-stream? []
  (and (not *force-path-ops*) @stream-capable))

(defn- real-directory! [^Path path]
  (let [attrs (Files/readAttributes path BasicFileAttributes nofollow)]
    (when (.isSymbolicLink attrs) (fail! "禁止经符号链接访问目录"))
    (when-not (.isDirectory attrs) (fail! "路径必须是普通目录"))
    attrs))

(deftype StreamDir [^SecureDirectoryStream stream ^Path path]
  Dir
  (dir-path [_] path)
  (entry-attrs [_ name]
    (.readAttributes ^BasicFileAttributeView
                     (.getFileAttributeView stream name BasicFileAttributeView nofollow)))
  (open-child [_ name]
    (->stream-dir (.newDirectoryStream stream name nofollow) (.resolve path ^Path name)))
  (child-names [_]
    (mapv #(.getFileName ^Path %) (iterator-seq (.iterator stream))))
  (byte-channel [_ name options attrs]
    (.newByteChannel stream name options attrs))
  (delete-file [_ name] (.deleteFile stream name))
  (delete-directory [_ name] (.deleteDirectory stream name))
  (create-dir [_ name]
    (try (Files/createDirectory (.resolve path ^Path name) posix-dir-attrs)
         (catch FileAlreadyExistsException _ nil)))
  (move-to [_ from dest dest-name]
    (.move stream from ^SecureDirectoryStream (.stream ^StreamDir dest) dest-name))
  (set-permissions [_ perms]
    (.setPermissions ^PosixFileAttributeView
                     (.getFileAttributeView stream PosixFileAttributeView) perms))
  Closeable
  (close [_] (.close stream)))

(defn- ->stream-dir [stream path]
  (StreamDir. stream path))

(deftype PathDir [^Path path]
  Dir
  (dir-path [_] path)
  (entry-attrs [_ name]
    (Files/readAttributes (.resolve path ^Path name) BasicFileAttributes nofollow))
  (open-child [_ name]
    (let [child (.resolve path ^Path name)]
      (real-directory! child)
      (->path-dir child)))
  (child-names [_]
    (real-directory! path)
    (with-open [stream (Files/newDirectoryStream path)]
      (real-directory! path)
      (mapv #(.getFileName ^Path %) (iterator-seq (.iterator stream)))))
  (byte-channel [_ name options attrs]
    (Files/newByteChannel (.resolve path ^Path name) options attrs))
  (delete-file [_ name]
    (Files/delete (.resolve path ^Path name)))
  (delete-directory [_ name]
    (Files/delete (.resolve path ^Path name)))
  (create-dir [_ name]
    (try (Files/createDirectory (.resolve path ^Path name) posix-dir-attrs)
         (catch FileAlreadyExistsException _ nil)))
  (move-to [_ from dest dest-name]
    (Files/move (.resolve path ^Path from)
                (.resolve ^Path (dir-path dest) ^Path dest-name)
                atomic-move))
  (set-permissions [_ perms]
    (.setPermissions ^PosixFileAttributeView
                     (Files/getFileAttributeView path PosixFileAttributeView nofollow) perms))
  Closeable
  (close [_]))

(defn- ->path-dir [path]
  (PathDir. path))

(defn- open-dir ^Closeable [^Path path]
  (if (use-stream?)
    (let [stream (Files/newDirectoryStream path)]
      (if (instance? SecureDirectoryStream stream)
        (->stream-dir stream path)
        (do (.close stream) (real-directory! path) (->path-dir path))))
    (do (real-directory! path) (->path-dir path))))

(defn- open-named [parent name create?]
  (try (open-child parent name)
       (catch NoSuchFileException ex
         (when-not create? (throw ex))
         (create-dir parent name)
         (open-child parent name))))

(defn- walk-directory [stream ^Path full parts create? f]
  (if-let [name (first parts)]
    (with-open [next-stream (open-named stream name create?)]
      (walk-directory next-stream (.resolve full ^Path name) (next parts) create? f))
    (f stream)))

(defn with-directory
  "从文件系统根逐段打开目录。有 SecureDirectoryStream 时用目录句柄；否则每段 lstat(NOFOLLOW)，遇符号链接失败。"
  [^Path path create? f]
  (with-open [root (open-dir (.getRoot path))]
    (walk-directory root (.getRoot path) (seq path) create? f)))

(defn- delete-entry! [parent name]
  (try
    (let [attrs (entry-attrs parent name)]
      (if (.isDirectory attrs)
        (do
          (with-open [children (open-child parent name)]
            (doseq [child (child-names children)]
              (delete-entry! children child)))
          (delete-directory parent name))
        (delete-file parent name)))
    (catch NoSuchFileException _ nil)))

(defn delete-tree!
  "删除目录树：符号链接只删链接本身，不进入目标。无 SecureDirectoryStream 时仍用 NOFOLLOW。"
  [^Path directory]
  (when-let [parent (.getParent directory)]
    (try
      (with-open [dir (open-dir parent)]
        (delete-entry! dir (.getFileName directory)))
      (catch NoSuchFileException _ nil))))

(defn with-cleanup
  "执行 f。f 失败时清理异常挂到原异常上；f 成功时忽略清理失败，避免盖住结果。"
  [cleanup f]
  (let [error (volatile! nil)
        value (try (f)
                   (catch Exception ex
                     (vreset! error ex)
                     nil))]
    (try (cleanup)
         (catch Exception clean
           (when-let [ex @error] (.addSuppressed ex clean))))
    (if-let [ex @error] (throw ex) value)))
