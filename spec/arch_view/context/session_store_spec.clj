;; 职责：验证原生 pi 快照原样恢复、项目隔离、原子保存与全路径符号链接拒绝。
;; 核心入口：session-store 的公开 API；所有文件均位于测试临时目录。

(ns arch-view.context.session-store-spec
  (:require [arch-view.context.session-store :as store]
            [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util UUID Comparator]
           [java.util.zip ZipInputStream]))

(def ^:private attributes
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                              (PosixFilePermissions/fromString "rwx------"))]))
(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private local-id "1b67b502-091a-4572-b494-c28ba5f6a693")
(def ^:private agent-id "292c459c-72f8-4a64-85a2-6bcc6d3596bb")
(def ^:private upstream-id "8947636b-e240-4a53-899c-b2b73852ef82")
(def ^:private native-jsonl
  (str "{\"type\":\"session\",\"version\":3,\"id\":\"" upstream-id "\",\"cwd\":\"/original/project\"}\n"
       "{\"type\":\"message\",\"id\":\"first\",\"parentId\":null,\"message\":{\"role\":\"user\",\"content\":\"解释调用链\"}}\n"
       "{\"type\":\"compaction\",\"id\":\"compact\",\"parentId\":\"first\",\"summary\":\"native memory\",\"firstKeptEntryId\":\"first\",\"tokensBefore\":32768}\n"
       "{\"type\":\"message\",\"id\":\"last\",\"parentId\":\"compact\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"原生回答\"}]}}\n"))

(defn- mkdir [^Path parent name]
  (Files/createDirectory (.resolve parent ^String name) attributes))

(defn- write-text [^Path path text]
  (spit (.toFile path) text :encoding "UTF-8")
  path)

(defn- read-text [^Path path]
  (slurp (.toFile path) :encoding "UTF-8"))

(defn- delete-tree! [^Path path]
  (when (Files/exists path nofollow)
    (with-open [stream (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
      (doseq [entry (.toArray (.sorted stream (Comparator/reverseOrder)))]
        (Files/delete ^Path entry)))))

(defn- fresh-prepared [^Path root name]
  (let [scratch (mkdir root name)]
    {:directory (.toFile scratch)
     :env {"HOME" (str (.resolve scratch "provider-selected-home"))
           "PI_CODING_AGENT_SESSION_DIR" (str (.resolve scratch "provider-selected-sessions"))}}))

(defn- env-path [prepared key]
  (.toPath (io/file (get-in prepared [:env key]))))

(defn- adapter-map-path [prepared]
  (.resolve (env-path prepared "HOME") ".pi/pi-acp/session-map.json"))

(defn- write-map! [fixture entry]
  (write-text (adapter-map-path (:prepared fixture))
              (json/write-json {:version 1 :sessions {agent-id entry}})))

(defn- fixture [^Path root]
  (let [prepared (fresh-prepared root "initial-scratch")
        home (Files/createDirectories (env-path prepared "HOME") attributes)
        _ (Files/createDirectories (.getParent (adapter-map-path prepared)) attributes)
        sessions (Files/createDirectories (env-path prepared "PI_CODING_AGENT_SESSION_DIR") attributes)
        native (write-text (.resolve sessions "original.jsonl") native-jsonl)
        ws {:root (mkdir root "project") :opts {:agent-state-dir (str (.resolve root "private-state"))}}
        metadata {:id local-id :agentSessionId agent-id :target "app.Core" :provider "pi-acp"
                  :createdAt "2026-06-01T10:00:00Z" :updatedAt "2026-06-01T10:01:00Z"
                  :history [{:question "解释调用链" :answer "展示回答"
                             :anchors [{:path "src/app.clj" :line 42}] :elapsedMs 123}]}
        fixture {:ws ws :prepared prepared :metadata metadata :native native :root root}]
    (doseq [name ["auth.json" "models.json" "settings.json"]]
      (write-text (.resolve home ^String name) "private credentials/settings"))
    (write-map! fixture {:sessionId upstream-id :cwd (str (:root ws)) :sessionFile (str native)})
    fixture))

(defn- with-fixture [f]
  (let [root (.toRealPath (Files/createTempDirectory "pi-session-store-spec-" attributes)
                          (make-array LinkOption 0))]
    (try (f (fixture root)) (finally (delete-tree! root)))))

(defn- save! [{:keys [ws metadata prepared]}]
  (store/save! ws local-id metadata prepared))

(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo ex
                 (assoc (ex-data ex) :message (.getMessage ex)))))

(defn- snapshot-path [{:keys [ws]}]
  (let [state (.toPath (io/file (get-in ws [:opts :agent-state-dir])))]
    (with-open [paths (Files/walk state (make-array java.nio.file.FileVisitOption 0))]
      (first (filter #(= "snapshot.zip" (str (.getFileName ^Path %))) (.toArray paths))))))

(defn- symlink! [^Path path ^Path target]
  (Files/createSymbolicLink path target (make-array FileAttribute 0)))

(defn- zip-names [^Path snapshot]
  (with-open [zip (ZipInputStream. (io/input-stream (.toFile snapshot)))]
    (loop [names []]
      (if-let [entry (.getNextEntry zip)]
        (recur (conj names (.getName entry)))
        names))))

(describe "pi ACP 原生会话快照"
  (it "保留 native header、compaction 和消息原始字节，恢复 map 指向新 scratch 的绝对路径"
    (with-fixture
      (fn [{:keys [ws metadata prepared root] :as fixture}]
        (should= metadata (save! fixture))
        (should= metadata (store/read-session ws local-id))
        (delete-tree! (.toPath (:directory prepared)))
        (let [fresh (fresh-prepared root "fresh-scratch")
              history (.resolve (env-path fresh "PI_CODING_AGENT_SESSION_DIR") "history.jsonl")]
          (should= metadata (store/restore! ws local-id fresh))
          (should= native-jsonl (read-text history))
          (should= (json/read-json
                     (json/write-json {:version 1 :sessions {agent-id {:sessionId upstream-id :cwd (str (:root ws))
                                                                      :sessionFile (str history) :updatedAt (:updatedAt metadata)}}}))
                   (json/read-json (read-text (adapter-map-path fresh))))
          (should (.isAbsolute history))))))

  (it "只持久化两个快照条目，不复制 auth、models 或 settings；持久化目录 0700、文件 0600"
    (with-fixture
      (fn [{:keys [ws] :as fixture}]
        (save! fixture)
        (should= ["metadata.json" "history.jsonl"] (zip-names (snapshot-path fixture)))
        (with-open [paths (Files/walk (.toPath (io/file (get-in ws [:opts :agent-state-dir])))
                                     (make-array java.nio.file.FileVisitOption 0))]
          (doseq [^Path path (.toArray paths)]
            (should= (if (Files/isDirectory path nofollow) "rwx------" "rw-------")
                     (PosixFilePermissions/toString (Files/getPosixFilePermissions path nofollow))))))))

  (it "两个项目即使本地 UUID 相同也隔离，列表按 target 筛选并返回 turnCount"
    (with-fixture
      (fn [{:keys [ws metadata prepared root] :as fixture}]
        (let [other (assoc ws :root (mkdir root "other-project"))]
          (should= [] (store/list-sessions ws))
          (save! fixture)
          (should= [] (store/list-sessions other))
          (should= 404 (:status (error-data #(store/read-session other local-id))))
          (should= [] (store/list-sessions ws "other.Target"))
          (should= [(assoc (select-keys metadata [:id :target :createdAt :updatedAt :provider])
                           :turnCount 1 :title "解释调用链")]
                   (store/list-sessions ws "app.Core"))
          (let [other-metadata (assoc metadata :target "other.Target")]
            (store/save! other local-id other-metadata prepared)
            (should= metadata (store/read-session ws local-id))
            (should= other-metadata (store/read-session other local-id)))))))

  (it "默认根目录使用 JVM user.home，不使用 prepared 的私有 HOME"
    (with-fixture
      (fn [{:keys [ws root] :as fixture}]
        (let [home (mkdir root "jvm-home")
              ws (assoc ws :opts {})
              previous (System/getProperty "user.home")]
          (try
            (System/setProperty "user.home" (str home))
            (save! (assoc fixture :ws ws))
            (should (Files/isDirectory (.resolve home ".local/state/arch-view/acp") nofollow))
            (should= (:metadata fixture) (store/read-session ws local-id))
            (finally (System/setProperty "user.home" previous)))))))

  (it "完整 UUID 校验拒绝本地 ID 和 ACP ID 的路径穿越"
    (with-fixture
      (fn [{:keys [ws metadata prepared]}]
        (doseq [id ["../escape" "1-1-1-1-1" "" (str local-id "/child")]]
          (should= 502 (:status (error-data #(store/save! ws id (assoc metadata :id id) prepared))))
          (should= 502 (:status (error-data #(store/read-session ws id))))
          (should= 502 (:status (error-data #(store/restore! ws id prepared)))))
        (should= 502 (:status (error-data #(store/save! ws local-id
                                            (assoc metadata :agentSessionId "../escape") prepared)))))))

  (it "只接受指定 adapter 项与私有 sessiondir 内的原生文件，并校验原生 sessionId"
    (with-fixture
      (fn [{:keys [prepared root native] :as fixture}]
        (let [outside (write-text (.resolve root "outside.jsonl") native-jsonl)]
          (write-map! fixture {:sessionId upstream-id :sessionFile (str outside)})
          (should= 502 (:status (error-data #(save! fixture))))
          (write-map! fixture {:sessionId upstream-id :sessionFile (str native)})
          (write-text native (str/replace native-jsonl upstream-id agent-id))
          (should= 502 (:status (error-data #(save! fixture))))
          (write-text native native-jsonl)
          (write-text (adapter-map-path prepared) "{\"version\":1,\"sessions\":{}}")
          (should= 502 (:status (error-data #(save! fixture))))))))

  (it "拒绝 sessionFile 本身、sessionFile 中间目录和 adapter map 的符号链接"
    (with-fixture
      (fn [{:keys [prepared root native] :as fixture}]
        (let [outside (write-text (.resolve root "outside.jsonl") native-jsonl)
              sessions (env-path prepared "PI_CODING_AGENT_SESSION_DIR")]
          (Files/delete native)
          (symlink! native outside)
          (should= 502 (:status (error-data #(save! fixture))))
          (Files/delete native)
          (let [directory (mkdir sessions "actual")
                file (write-text (.resolve directory "native.jsonl") native-jsonl)
                link (symlink! (.resolve sessions "linked") directory)]
            (write-map! fixture {:sessionId upstream-id :sessionFile (str (.resolve link "native.jsonl"))})
            (should= 502 (:status (error-data #(save! fixture))))
            (write-map! fixture {:sessionId upstream-id :sessionFile (str file)}))
          (let [map-file (adapter-map-path prepared)
                outside-map (write-text (.resolve root "outside-map.json") (read-text map-file))]
            (Files/delete map-file)
            (symlink! map-file outside-map)
            (should= 502 (:status (error-data #(save! fixture)))))))))

  (it "拒绝 state 根路径的中间链接、会话目录链接与快照文件链接"
    (with-fixture
      (fn [{:keys [ws root prepared] :as fixture}]
        (let [outside (mkdir root "outside-state")
              link (symlink! (.resolve root "linked-state") outside)
              unsafe (assoc-in ws [:opts :agent-state-dir] (str (.resolve link "nested")))]
          (should= 502 (:status (error-data #(save! (assoc fixture :ws unsafe)))))
          (should= 502 (:status (error-data #(store/list-sessions unsafe)))))
        (save! fixture)
        (let [snapshot (snapshot-path fixture)
              copy (write-text (.resolve root "outside-snapshot") "outside")]
          (Files/delete snapshot)
          (symlink! snapshot copy)
          (should= 502 (:status (error-data #(store/read-session ws local-id))))
          (should= 502 (:status (error-data #(save! fixture))))
          (Files/delete snapshot)
          (let [directory (.getParent snapshot)]
            (Files/delete directory)
            (symlink! directory (mkdir root "outside-session"))
            (should= 502 (:status (error-data #(store/restore! ws local-id prepared)))))))))

  (it "恢复拒绝 HOME 祖先链接和 sessiondir 链接，不写入链接目标"
    (with-fixture
      (fn [{:keys [ws root] :as fixture}]
        (save! fixture)
        (let [fresh (fresh-prepared root "fresh")
              outside (mkdir root "restore-outside")
              home (env-path fresh "HOME")
              sessions (env-path fresh "PI_CODING_AGENT_SESSION_DIR")]
          (symlink! home outside)
          (should= 502 (:status (error-data #(store/restore! ws local-id fresh))))
          (Files/delete home)
          (symlink! sessions outside)
          (should= 502 (:status (error-data #(store/restore! ws local-id fresh))))
          (should= [] (vec (.listFiles (.toFile outside))))))))

  (it "原子替换前写入失败或拒绝超大 metadata 时，旧快照仍可读取"
    (with-fixture
      (fn [{:keys [ws metadata native] :as fixture}]
        (save! fixture)
        (with-redefs-fn {#'store/write-channel! (fn [_ _] (throw (java.io.IOException. "模拟中断")))}
          #(should= 502 (:status (error-data (fn [] (save! fixture))))))
        (should= metadata (store/read-session ws local-id))
        (let [large (assoc metadata :history [{:question (apply str (repeat (* 2 1024 1024) "x")) :answer ""}])]
          (should= 502 (:status (error-data #(save! (assoc fixture :metadata large))))))
        (should= metadata (store/read-session ws local-id))
        (should= ["snapshot.zip"] (vec (.list (.toFile (.getParent (snapshot-path fixture))))))
        (write-text native "broken header")
        (should= 502 (:status (error-data #(save! fixture))))
        (should= metadata (store/read-session ws local-id)))))

  (it "拒绝超过 32 MiB 的 JSONL，并返回可读的损坏与不存在错误"
    (with-fixture
      (fn [{:keys [ws native] :as fixture}]
        (with-open [file (java.io.RandomAccessFile. (.toFile native) "rw")]
          (.setLength file (inc (* 32 1024 1024))))
        (let [error (error-data #(save! fixture))]
          (should= 502 (:status error))
          (should-contain "大小限制" (:message error)))
        (should= 404 (:status (error-data #(store/read-session ws local-id))))
        (write-text native native-jsonl)
        (save! fixture)
        (write-text (snapshot-path fixture) "corrupt zip")
        (let [error (error-data #(store/read-session ws local-id))]
          (should= 502 (:status error))
          (should-contain "读取原生 pi 会话失败" (:message error))))))

  (it "拒绝 prepared 目录越出 scratch，以及 env 路径中的 .."
    (with-fixture
      (fn [{:keys [root prepared] :as fixture}]
        (let [outside (assoc-in prepared [:env "HOME"] (str root))
              traversal (assoc-in prepared [:env "PI_CODING_AGENT_SESSION_DIR"]
                                     (str (:directory prepared) "/../escape"))]
          (should= 502 (:status (error-data #(save! (assoc fixture :prepared outside)))) )
          (should= 502 (:status (error-data #(save! (assoc fixture :prepared traversal))))))))))
