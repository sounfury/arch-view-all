;; 职责：识别本机 AI provider，为 pi 构造受限参数和独立配置；系统文件隔离由调用方沙箱负责。
;; 核心入口：configuration、prepare、parse-command。

(ns arch-view.context.provider
  (:require [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.channels Channels]
           [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn parse-command
  "按空白和双引号拆成 argv；保留普通反斜杠，不经过 shell。"
  [command]
  (loop [chars (seq command) quoted? false active? false token "" result []]
    (if-let [ch (first chars)]
      (cond
        (= ch \") (recur (next chars) (not quoted?) true token result)
        (and (Character/isWhitespace ^char ch) (not quoted?))
        (recur (next chars) false false "" (if active? (conj result token) result))
        :else (recur (next chars) quoted? true (str token ch) result))
      (do
        (when quoted? (throw (ex-info "本机 AI 命令的双引号未闭合" {:status 400})))
        (let [args (if active? (conj result token) result)]
          (when (str/blank? (first args))
            (throw (ex-info "本机 AI 命令缺少可执行文件" {:status 400})))
          args)))))

(defn- detected-provider [command]
  (let [basename (-> command parse-command first (str/replace "\\" "/") (str/split #"/") last str/lower-case)]
    (cond
      (#{"pi" "pi.cmd" "pi.exe"} basename) "pi"
      (#{"pi-acp" "pi-acp.cmd" "pi-acp.exe"} basename) "pi-acp"
      :else "command")))

(defn- configured-command [provider command]
  (if (str/blank? command)
    (if (#{"pi" "pi-acp"} provider)
      provider
      (throw (ex-info (str "尚未配置本机 AI 命令。请在工具目录的 .env 配置 "
                           "ARCH_VIEW_AGENT_PROVIDER=pi-acp；或设置 "
                           "ARCH_VIEW_AGENT_COMMAND=claude -p（也可使用 codex exec）。")
                      {:status 409})))
    command))

(defn configuration
  "只读取 ws 的全局 opts；auto 以 argv 第一个可执行文件的 basename 识别 pi/pi-acp。"
  [ws]
  (let [provider (str/lower-case (str (or (get-in ws [:opts :agent-provider]) "auto")))
        timeout (get-in ws [:opts :agent-timeout] 180)]
    (when-not (#{"auto" "pi" "pi-acp" "command"} provider)
      (throw (ex-info "本机 AI provider 只支持 auto、pi、pi-acp 或 command" {:status 400})))
    (let [command (configured-command provider (get-in ws [:opts :agent-command]))]
      (when-not (and (number? timeout) (pos? timeout))
        (throw (ex-info "本机 AI 超时秒数必须大于 0" {:status 400})))
      {:provider (if (= "auto" provider) (detected-provider command) provider)
       :command command :timeout timeout})))

(def ^:private pi-forced-args
  ["--print" "--mode" "text" "--no-session" "--no-extensions" "--no-mcp"
   "--tools" "read,grep,find,ls" "--no-skills" "--no-prompt-templates"
   "--no-themes" "--no-context-files" "--no-approve" "--offline"])

(def ^:private pi-rpc-forced-args
  ["--mode" "rpc" "--no-extensions" "--no-mcp" "--tools" "read,grep,find,ls"
   "--no-skills" "--no-prompt-templates" "--no-themes" "--no-context-files"
   "--no-approve" "--offline"])

(def ^:private pi-model-flags #{"--model" "--provider" "--thinking" "--models"})
(def ^:private pi-safe-flags
  #{"-p" "--print" "--no-session" "-ne" "--no-extensions" "--no-mcp"
    "-ns" "--no-skills" "-np" "--no-prompt-templates" "--no-themes"
    "-nc" "--no-context-files" "-na" "--no-approve" "--offline"})

(defn- reject-pi-args! []
  ;; 不回显未知参数或参数值，避免 --api-key 等配置中的凭据出现在错误里。
  (throw (ex-info (str "pi 命令参数被拒绝。支持 --model、--provider、--thinking、--models 及其值，"
                       "-p/--print、--mode text 和只读隔离标志；"
                       "不允许自定义工具、扩展、会话、系统提示词、子命令或其它输出模式。")
                  {:status 400})))

(defn- option-value? [value]
  (and (not (str/blank? value)) (not (str/starts-with? value "-"))))

(defn- pi-model-args [args]
  (loop [args (seq args) result []]
    (if-not args
      result
      (let [[arg value] args]
        (cond
          (pi-safe-flags arg) (recur (next args) result)
          (= "--mode" arg) (if (= "text" value) (recur (nnext args) result) (reject-pi-args!))
          (pi-model-flags arg) (if (option-value? value)
                                (recur (nnext args) (conj result arg value))
                                (reject-pi-args!))
          :else (reject-pi-args!))))))

(def ^:private setting-keys
  ["defaultProvider" "defaultModel" "defaultThinkingLevel" "modelThinkingLevels"
   "thinkingBudgets" "enabledModels" "transport" "httpProxy" "httpIdleTimeoutMs"
   "websocketConnectTimeoutMs" "retry"])
(def ^:private retry-keys ["enabled" "maxRetries" "baseDelayMs" "maxAgentDelayMs" "provider"])
(def ^:private provider-retry-keys ["timeoutMs" "maxRetries" "maxRetryDelayMs"])
(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- private-attributes [^Path parent permissions]
  (if (.supportsFileAttributeView (Files/getFileStore parent) "posix")
    (into-array FileAttribute [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString permissions))])
    (make-array FileAttribute 0)))

(defn- restrict-private! [^Path path directory?]
  ;; POSIX 在创建时已限制权限；其它文件系统使用 Java 的 owner-only 权限接口。
  (when-not (.supportsFileAttributeView (Files/getFileStore path) "posix")
    (let [file (.toFile path)]
      (when-not (every? true? [(.setReadable file false false) (.setWritable file false false)
                               (.setExecutable file false false) (.setReadable file true true)
                               (.setWritable file true true) (.setExecutable file directory? true)])
        (throw (ex-info "无法限制 pi 临时配置的私有权限" {:status 500})))))
  path)

(defn- create-private-directory! [^Path parent name]
  (restrict-private! (Files/createDirectory (.resolve parent ^String name)
                                            (private-attributes parent "rwx------")) true))

(defn- regular-file? [^Path path]
  (Files/isRegularFile path no-follow))

(defn- read-options []
  (into-array OpenOption [StandardOpenOption/READ LinkOption/NOFOLLOW_LINKS]))

(defn- private-output [^Path destination]
  (let [channel (Files/newByteChannel destination
                                      #{StandardOpenOption/WRITE StandardOpenOption/CREATE_NEW}
                                      (private-attributes (.getParent destination) "rw-------"))]
    (try
      (restrict-private! destination false)
      (Channels/newOutputStream channel)
      (catch Exception ex (.close channel) (throw ex)))))

(defn- copy-private-file! [^Path source ^Path target name]
  (let [path (.resolve source ^String name)]
    (when (regular-file? path)
      (with-open [input (Files/newInputStream path (read-options))
                  output (private-output (.resolve target ^String name))]
        (io/copy input output)))))

(defn- read-settings [^Path source]
  (let [path (.resolve source "settings.json")]
    (when (regular-file? path)
      (try
        (with-open [input (Files/newInputStream path (read-options))]
          (let [settings (json/read-json (slurp input :encoding "UTF-8"))]
            (when-not (map? settings) (throw (IllegalArgumentException.)))
            settings))
        (catch Exception _
          (throw (ex-info "无法读取 pi settings.json；需要常规 JSON 对象文件" {:status 400})))))))

(defn- safe-settings [settings]
  (cond-> (select-keys settings setting-keys)
    (map? (get settings "retry"))
    (assoc "retry" (cond-> (select-keys (get settings "retry") retry-keys)
                     (map? (get-in settings ["retry" "provider"]))
                     (assoc "provider" (select-keys (get-in settings ["retry" "provider"]) provider-retry-keys))))))

(defn- copy-settings! [^Path source ^Path target]
  (when-let [settings (read-settings source)]
    (with-open [output (private-output (.resolve target "settings.json"))]
      (.write output (.getBytes ^String (json/write-json (safe-settings settings)) "UTF-8")))))

(defn- source-agent-directory [ws]
  (.toPath (io/file (str (or (get-in ws [:opts :pi-agent-dir])
                           (not-empty (System/getenv "PI_CODING_AGENT_DIR"))
                           (io/file (System/getProperty "user.home") ".pi" "agent"))))))

(def ^:private parent-session-variables
  ["PI_SESSION_ID" "PI_SESSION_FILE" "PI_PROVIDER" "PI_MODEL" "PI_REASONING_LEVEL"])

(defn- readonly-lock-preload! [^Path scratch]
  (let [resource (io/resource "arch_view/context/pi_readonly_locks.cjs")
        destination (.resolve scratch "pi_readonly_locks.cjs")
        filename (str (.toAbsolutePath destination))]
    (when-not resource (throw (ex-info "缺少 pi 私有锁兼容脚本" {:status 503})))
    (when (some #(Character/isISOControl ^char %) filename)
      (throw (ex-info "pi 临时目录路径包含不支持的控制字符" {:status 503})))
    (with-open [input (io/input-stream resource)
                output (private-output destination)]
      (io/copy input output))
    ;; NODE_OPTIONS 使用 Node 自己的双引号语法，不经过 shell，也不继承父进程钩子。
    (str "--require=\"" (-> filename (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\"")))

(defn- pi-environment! [ws scratch-directory]
  (let [scratch (.toPath (io/file (str scratch-directory)))
        source (source-agent-directory ws)
        target (create-private-directory! scratch "pi-agent")
        sessions (create-private-directory! scratch "pi-sessions")]
    (doseq [name ["models.json" "auth.json"]] (copy-private-file! source target name))
    (copy-settings! source target)
    (merge (zipmap parent-session-variables (repeat nil))
           {"NODE_OPTIONS" (readonly-lock-preload! scratch)
            "PI_CODING_AGENT_DIR" (str (.toAbsolutePath target))
            "PI_CODING_AGENT_SESSION_DIR" (str (.toAbsolutePath sessions))
            "PI_OFFLINE" "1" "PI_SKIP_VERSION_CHECK" "1" "PI_TELEMETRY" "0"})))

(defn- executable-private! [^Path path]
  (if (.supportsFileAttributeView (Files/getFileStore path) "posix")
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rwx------"))
    (when-not (.setExecutable (.toFile path) true true)
      (throw (ex-info "无法设置 pi 私有启动脚本的可执行权限" {:status 503}))))
  path)

(defn- pi-acp-launch! [^Path scratch command args sessions]
  (let [resource (io/resource "arch_view/context/pi_acp_launch.cjs")
        destination (.resolve scratch "pi_acp_launch.cjs")
        config (json/write-json {:command command :args args :sessions sessions})]
    (when-not resource (throw (ex-info "缺少 pi-acp 私有启动脚本" {:status 503})))
    ;; 配置使用 JSON 字面量固化，路径和参数始终以 argv 传递，不经过 shell。
    (with-open [output (private-output destination)]
      (.write output (.getBytes ^String (str/replace (slurp resource :encoding "UTF-8")
                                                   "__ARCH_VIEW_PI_ACP_CONFIG__" config) "UTF-8")))
    (str (.toAbsolutePath (executable-private! destination)))))

(defn- prepare-pi-acp [ws argv scratch-directory]
  (let [command (get-in ws [:opts :pi-command])
        [executable & options] (parse-command (if (str/blank? command) "pi" command))
        args (into (pi-model-args options) pi-rpc-forced-args)]
    (when (str/blank? (first argv)) (reject-pi-args!))
    ;; 先复制真实用户的凭据和设置，再为 adapter 分配隔离 HOME。
    (let [env (pi-environment! ws scratch-directory)
          scratch (.toPath (io/file (str scratch-directory)))
          home (create-private-directory! scratch "pi-acp-home")
          launch (pi-acp-launch! scratch executable args (get env "PI_CODING_AGENT_SESSION_DIR"))]
      {:args (vec argv) :provider "pi-acp" :protocol :acp
       :env (assoc env "HOME" (str (.toAbsolutePath home)) "PI_ACP_PI_COMMAND" launch)})))

(defn prepare
  "接收已拆分的 argv；pi 配置仅写入调用方提供的全新 scratch 目录，清理由调用方负责。
   pi-acp 保留 adapter argv，通过 opts :pi-command 配置底层 pi，并返回 :protocol :acp。"
  [ws argv scratch-directory]
  (let [{:keys [provider]} (configuration ws)]
    (case provider
      "command" {:args (vec argv) :env {} :provider provider}
      "pi-acp" (prepare-pi-acp ws argv scratch-directory)
      (let [model-args (pi-model-args (rest argv))]
        (when (str/blank? (first argv)) (reject-pi-args!))
        {:args (into [(first argv)] (concat model-args pi-forced-args))
         :env (pi-environment! ws scratch-directory) :provider provider}))))
