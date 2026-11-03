;; 职责：将原生 pi 会话与展示历史原子保存到项目隔离的私有状态目录。
;; 核心入口：save!、restore!、read-session、list-sessions；只访问 prepared 环境指定的会话文件。

(ns arch-view.context.session-store
  (:require [arch-view.context.secure-fs :as sfs]
            [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.nio.channels Channels FileChannel]
           [java.nio.file Path LinkOption StandardOpenOption NoSuchFileException]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util UUID]
           [java.util.zip ZipEntry ZipInputStream ZipOutputStream]))

(def ^:private jsonl-limit (* 32 1024 1024))
(def ^:private metadata-limit (* 2 1024 1024))
(def ^:private snapshot-limit (+ jsonl-limit metadata-limit (* 64 1024)))
(def ^:private directory-permissions (PosixFilePermissions/fromString "rwx------"))
(def ^:private file-permissions (PosixFilePermissions/fromString "rw-------"))
(def ^:private metadata-keys [:id :agentSessionId :target :createdAt :updatedAt :provider :history])

(defn- fail! [message]
  (throw (ex-info message {:status 502})))

(defn- operation [action f]
  (try (f)
       (catch clojure.lang.ExceptionInfo ex
         (throw (ex-info (str action "：" (.getMessage ex))
                         (merge {:status 502} (ex-data ex)) ex)))
       (catch NoSuchFileException ex
         (throw (ex-info (str action "：会话文件不存在") {:status 404} ex)))
       (catch Exception ex
         (throw (ex-info (str action "：无法安全访问会话文件（" (.getMessage ex) "）")
                         {:status 502} ex)))))

(defn- valid-uuid? [value]
  (and (string? value)
       (boolean (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" value))))

(defn- uuid! [value]
  (when-not (valid-uuid? value) (fail! "会话 ID 必须是完整 UUID，禁止路径穿越"))
  value)

(defn- absolute-path [value]
  (when (or (nil? value) (str/blank? (str value))) (fail! "缺少会话目录路径"))
  (let [path (.toAbsolutePath (.toPath (io/file (str value))))]
    (when (some #{".."} (map str path)) (fail! "会话路径禁止包含 .."))
    (.normalize path)))

(defn- private-directory! [^Path path]
  (sfs/with-directory path true
    (fn [directory] (sfs/set-permissions directory directory-permissions)))
  path)

(defn- project-directory [ws]
  (let [root (absolute-path (:root ws))
        _ (sfs/with-directory root false (constantly nil))
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str root) "UTF-8"))
        hash (apply str (map #(format "%02x" (bit-and 255 %)) digest))
        base (absolute-path (get-in ws [:opts :agent-state-dir]
                                  (str (System/getProperty "user.home") "/.local/state/arch-view/acp")))]
    (.resolve base ^String hash)))

(defn- storage-directory! [ws local-id]
  (uuid! local-id)
  (let [project (project-directory ws)]
    (private-directory! (.getParent project))
    (private-directory! project)
    (private-directory! (.resolve project ^String local-id))))

(defn- prepared-paths [prepared]
  (let [scratch (absolute-path (:directory prepared))
        home (absolute-path (get-in prepared [:env "HOME"]))
        sessions (absolute-path (get-in prepared [:env "PI_CODING_AGENT_SESSION_DIR"]))]
    (doseq [^Path path [home sessions]]
      (when-not (and (.startsWith path scratch) (not= path scratch))
        (fail! "HOME 和 PI_CODING_AGENT_SESSION_DIR 必须位于独立 scratch 内")))
    (sfs/with-directory scratch false (constantly nil))
    {:home home :sessions sessions :map (.resolve home ".pi/pi-acp/session-map.json")}))

(defn- bounded-read [input limit]
  (let [out (ByteArrayOutputStream.) buffer (byte-array 8192)]
    (loop [total 0]
      (let [size (.read ^java.io.InputStream input buffer)]
        (when-not (= -1 size)
          (when (> (+ total size) limit) (fail! "会话文件超过大小限制"))
          (.write out buffer 0 size)
          (recur (+ total size)))))
    (.toByteArray out)))

(defn- read-file [^Path path limit]
  (sfs/with-directory (.getParent path) false
    (fn [directory]
      (let [name (.getFileName path) attrs (sfs/entry-attrs directory name)]
        (when-not (.isRegularFile attrs) (fail! "会话文件必须是普通文件，禁止符号链接"))
        (when (> (.size attrs) limit) (fail! "会话文件超过大小限制"))
        (with-open [channel (sfs/byte-channel directory name
                              #{StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS}
                              (make-array FileAttribute 0))
                    input (Channels/newInputStream channel)]
          (bounded-read input limit))))))

(defn- json-bytes [value limit]
  (let [bytes (.getBytes (json/write-json value) "UTF-8")]
    (when (> (alength bytes) limit) (fail! "会话 metadata 超过大小限制"))
    bytes))

(defn- parse-json [bytes]
  (json/read-json (String. ^bytes bytes "UTF-8")))

(defn- metadata! [local-id metadata]
  (uuid! local-id)
  (uuid! (:agentSessionId metadata))
  (when-not (and (= local-id (:id metadata)) (= "pi-acp" (:provider metadata))
                 (string? (:target metadata)) (vector? (:history metadata)))
    (fail! "会话 metadata 的 ID、provider、target 或 history 无效"))
  (doseq [key [:createdAt :updatedAt]] (Instant/parse (get metadata key)))
  (select-keys metadata metadata-keys))

(defn- native-header! [bytes upstream-id]
  (uuid! upstream-id)
  (let [text (String. ^bytes bytes "UTF-8")
        end (.indexOf text "\n")
        header (json/read-json (if (neg? end) text (subs text 0 end)))]
    (when-not (and (= "session" (get header "type")) (= upstream-id (get header "id")))
      (fail! "原生 pi JSONL header 与 adapter sessionId 不匹配")))
  bytes)

(defn- native-session [paths metadata]
  (let [db (parse-json (read-file (:map paths) metadata-limit))
        entry (get-in db ["sessions" (:agentSessionId metadata)])
        upstream-id (get entry "sessionId")
        file (absolute-path (get entry "sessionFile"))
        ^Path sessions (:sessions paths)]
    (when-not (= 1 (get db "version")) (fail! "不支持的 pi-acp session-map 版本"))
    (when-not (and (.startsWith file sessions) (not= file sessions))
      (fail! "adapter 会话文件必须位于私有 PI_CODING_AGENT_SESSION_DIR 内"))
    (sfs/with-directory sessions false (constantly nil))
    {:upstreamSessionId (uuid! upstream-id)
     :native (native-header! (read-file file jsonl-limit) upstream-id)}))

(defn- snapshot-bytes [metadata upstream-id native]
  ;; 两个条目组成一个原子替换单元；原生 JSONL 字节不经重写，亦不复制任何配置目录。
  (let [out (ByteArrayOutputStream.)
        manifest (json-bytes {:metadata metadata :upstreamSessionId upstream-id} metadata-limit)]
    (with-open [zip (ZipOutputStream. out)]
      (doseq [[name bytes] [["metadata.json" manifest] ["history.jsonl" native]]]
        (.putNextEntry zip (ZipEntry. name))
        (.write zip ^bytes bytes)
        (.closeEntry zip)))
    (.toByteArray out)))

(defn- file-name [name]
  (.toPath (io/file name)))

(defn- check-destination! [directory ^Path name]
  (try
    (when-not (.isRegularFile (sfs/entry-attrs directory name))
      (fail! "目标会话文件必须是普通文件，禁止符号链接"))
    (catch NoSuchFileException _ nil)))

(defn- write-channel! [channel bytes]
  (let [buffer (ByteBuffer/wrap bytes)]
    (while (.hasRemaining buffer) (.write ^java.nio.channels.SeekableByteChannel channel buffer))
    (when (instance? FileChannel channel) (.force ^FileChannel channel true))))

(defn- atomic-write! [^Path path bytes]
  (private-directory! (.getParent path))
  (sfs/with-directory (.getParent path) false
    (fn [directory]
      (let [destination (.getFileName path)
            temporary (file-name (str "." (UUID/randomUUID) ".tmp"))]
        (check-destination! directory destination)
        (try
          (with-open [channel (sfs/byte-channel directory temporary
                                #{StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW LinkOption/NOFOLLOW_LINKS}
                                (into-array FileAttribute [(PosixFilePermissions/asFileAttribute file-permissions)]))]
            (write-channel! channel bytes))
          (sfs/move-to directory temporary directory destination)
          (finally
            (try (sfs/delete-file directory temporary) (catch NoSuchFileException _ nil))))))))

(defn- snapshot-entries [bytes]
  (with-open [zip (ZipInputStream. (ByteArrayInputStream. bytes))]
    (loop [entries {}]
      (if-let [entry (.getNextEntry zip)]
        (let [name (.getName entry)
              limit ({"metadata.json" metadata-limit "history.jsonl" jsonl-limit} name)]
          (when (or (nil? limit) (contains? entries name)) (fail! "原生快照包含非法或重复条目"))
          (recur (assoc entries name (bounded-read zip limit))))
        (do
          (when-not (= #{"metadata.json" "history.jsonl"} (set (keys entries)))
            (fail! "原生快照缺少 metadata 或 JSONL"))
          entries)))))

(defn- read-snapshot [^Path directory local-id]
  (let [entries (snapshot-entries (read-file (.resolve directory "snapshot.zip") snapshot-limit))
        manifest (parse-json (get entries "metadata.json"))
        metadata (metadata! local-id (walk/keywordize-keys (get manifest "metadata")))
        upstream-id (get manifest "upstreamSessionId")]
    {:metadata metadata :upstreamSessionId upstream-id
     :native (native-header! (get entries "history.jsonl") upstream-id)}))

(defn save!
  "每轮完成后保存原生 pi JSONL 和展示 metadata；成功返回持久化的 metadata。"
  [ws local-id metadata prepared]
  (operation "保存原生 pi 会话失败"
    (fn []
      (let [metadata (metadata! local-id metadata)
            paths (prepared-paths prepared)
            {:keys [native upstreamSessionId]} (native-session paths metadata)
            bytes (snapshot-bytes metadata upstreamSessionId native)
            directory (storage-directory! ws local-id)]
        (atomic-write! (.resolve directory "snapshot.zip") bytes)
        metadata))))

(defn restore!
  "将原生会话恢复到新 scratch，并创建 session/load 所需的 adapter map；返回 metadata。"
  [ws local-id prepared]
  (operation "恢复原生 pi 会话失败"
    (fn []
      (uuid! local-id)
      (let [directory (.resolve (project-directory ws) ^String local-id)
            {:keys [metadata native upstreamSessionId]} (read-snapshot directory local-id)
            paths (prepared-paths prepared)
            history (.resolve ^Path (:sessions paths) "history.jsonl")
            entry {:sessionId upstreamSessionId :cwd (str (absolute-path (:root ws)))
                   :sessionFile (str history) :updatedAt (:updatedAt metadata)}]
        (private-directory! (:home paths))
        (private-directory! (:sessions paths))
        (atomic-write! history native)
        (atomic-write! (:map paths)
          (json-bytes {:version 1 :sessions {(:agentSessionId metadata) entry}} metadata-limit))
        metadata))))

(defn read-session
  "读取完整展示 metadata 而不启动 agent；不存在时返回 404 错误。"
  [ws local-id]
  (operation "读取原生 pi 会话失败"
    (fn []
      (uuid! local-id)
      (:metadata (read-snapshot (.resolve (project-directory ws) ^String local-id) local-id)))))

(defn- project-session-ids [^Path project]
  (try
    (sfs/with-directory project false
      (fn [directory]
        (->> (sfs/child-names directory)
             (map str)
             (filter valid-uuid?) vec)))
    (catch NoSuchFileException _ [])))

(defn- session-summary [metadata]
  (let [question (:question (last (:history metadata)))
        title (str (or question (:target metadata)))]
    (assoc (select-keys metadata [:id :target :createdAt :updatedAt :provider])
           :turnCount (count (:history metadata))
           :title (subs title 0 (min 60 (count title))))))

(defn- project-summaries [ws target]
  (let [project (project-directory ws)]
    (->> (project-session-ids project)
         (map #(read-session ws %))
         (filter #(or (nil? target) (= target (:target %))))
         (sort-by (juxt #(Instant/parse (:updatedAt %)) :id)) reverse
         (mapv session-summary))))

(defn list-sessions
  "返回当前项目的会话摘要（不含 history），可按 target 精确筛选，最近更新优先。"
  ([ws] (list-sessions ws nil))
  ([ws target]
   (operation "读取原生 pi 会话列表失败" #(project-summaries ws target))))
