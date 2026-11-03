;; 职责：创建独立临时写入目录，为本机 AI 参数向量添加强制只读系统沙箱。
;; 核心入口：open!；隔离不可用时拒绝执行，cleanup 不跟随符号链接。

(ns arch-view.context.sandbox
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files Path SecureDirectoryStream LinkOption NoSuchFileException]
           [java.nio.file.attribute BasicFileAttributeView FileAttribute PosixFilePermissions]))

(declare ^:private platform ^:private temporary-directory ^:private cleanup! ^:private delete-entry!
         ^:private linux-wrapper ^:private macos-wrapper ^:private profile-string)

(defn open!
  "返回 {:directory File :env map :wrap fn :cleanup fn :kind string}。
   Linux 的实际能力检查在 helper 启动时进行；失败退出 125，绝不降级。"
  [ws]
  (let [system (platform)]
    (when-not (#{:linux :macos} system)
      (throw (ex-info "只读沙箱不可用：当前系统暂无系统级文件隔离支持" {:status 503})))
    (let [directory (temporary-directory)]
      (try
        (let [scratch (str directory)
              wrap (case system
                     :linux (linux-wrapper ws directory)
                     :macos (macos-wrapper scratch))]
          {:directory (.toFile ^Path directory)
           :env {"TMPDIR" scratch "TMP" scratch "TEMP" scratch}
           :wrap wrap :cleanup #(cleanup! directory)
           :kind (if (= :linux system) "linux-landlock" "macos-sandbox")})
        (catch Exception ex
          (cleanup! directory)
          (throw (ex-info (str "只读沙箱不可用：" (.getMessage ex)) {:status 503} ex)))))))

;; ===== 私有方法 =====

(defn- platform []
  (let [name (str/lower-case (System/getProperty "os.name" ""))]
    (cond
      (str/starts-with? name "linux") :linux
      (str/starts-with? name "mac") :macos
      :else :unsupported)))

(defn- temporary-directory []
  (try
    (let [permissions (PosixFilePermissions/asFileAttribute
                        (PosixFilePermissions/fromString "rwx------"))]
      (.toRealPath (Files/createTempDirectory "arch-view-sandbox-"
                                             (into-array FileAttribute [permissions]))
                   (make-array LinkOption 0)))
    (catch Exception ex
      (throw (ex-info (str "只读沙箱不可用：无法创建独占临时目录：" (.getMessage ex))
                      {:status 503} ex)))))

(defn- delete-entry! [^SecureDirectoryStream parent ^Path name]
  (try
    (let [options (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
          view (.getFileAttributeView parent name BasicFileAttributeView options)]
      (if (.isDirectory (.readAttributes ^BasicFileAttributeView view))
        (do
          (with-open [children (.newDirectoryStream parent name options)]
            (doseq [^Path child children]
              (delete-entry! children (.getFileName child))))
          (.deleteDirectory parent name))
        (.deleteFile parent name)))
    (catch NoSuchFileException _ nil)))

(defn- cleanup! [^Path directory]
  ;; 目录句柄 + NOFOLLOW_LINKS，避免检查后被替换的链接把清理带到宿主目录。
  (with-open [parent (Files/newDirectoryStream (.getParent directory))]
    (when-not (instance? SecureDirectoryStream parent)
      (throw (ex-info "无法安全清理临时目录：文件系统不支持安全目录句柄" {:status 503})))
    (delete-entry! parent (.getFileName directory))))

(defn- linux-wrapper [ws ^Path directory]
  (let [resource (io/resource "arch_view/context/readonly_sandbox.py")
        helper (.toFile (.resolve directory "readonly_sandbox.py"))
        python (get-in ws [:opts :python] "python3")]
    (when-not resource (throw (ex-info "缺少 Linux 只读沙箱 helper" {:status 503})))
    (when-not (and (string? python) (not (str/blank? python)))
      (throw (ex-info "Python 命令不能为空" {:status 503})))
    (with-open [source (io/input-stream resource)] (io/copy source helper))
    (fn [argv]
      (into [python "-I" "-B" (.getAbsolutePath helper) "--scratch" (str directory) "--"] argv))))

(defn- profile-string [value]
  (when (some #(Character/isISOControl ^char %) value)
    (throw (ex-info "临时目录路径包含不支持的控制字符" {:status 503})))
  (str "\"" (-> value (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- macos-wrapper [scratch]
  (when-not (.canExecute (io/file "/usr/bin/sandbox-exec"))
    (throw (ex-info "系统未提供 sandbox-exec，无法强制只读隔离" {:status 503})))
  (let [profile (str "(version 1) (allow default) (deny file-write*) "
                     "(allow file-write* (subpath " (profile-string scratch) ")) "
                     "(allow file-write-data (literal \"/dev/null\"))")]
    (fn [argv] (into ["/usr/bin/sandbox-exec" "-p" profile] argv))))
