;; 职责：验证 pi CLI 参数收敛、配置私有复制，以及普通命令兼容性。
;; 核心入口：provider configuration / prepare Speclj 示例。

(ns arch-view.context.provider-spec
  (:require [arch-view.context.fixtures :as fixture :refer [status-of]]
            [arch-view.context.provider :as provider]
            [arch-view.context.sandbox :as sandbox]
            [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util.concurrent TimeUnit]))

(defn- put-json! [root path data]
  (fixture/put! root path (json/write-json data)))

(defn- read-json [directory name]
  (json/read-json (slurp (io/file (str directory) name) :encoding "UTF-8")))

(defn- failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo ex ex)))

(defn- run-command
  ([argv env] (run-command argv env {}))
  ([argv env parent-env]
   (let [builder (ProcessBuilder. ^java.util.List argv)
         environment (.environment builder)]
     (.putAll environment parent-env)
     (doseq [[key value] env]
       (if (nil? value) (.remove environment key) (.put environment key value)))
     (let [process (.start builder)]
       (.close (.getOutputStream process))
       (try
         (when-not (.waitFor process 20 TimeUnit/SECONDS)
           (throw (ex-info "pi 锁兼容测试命令超时" {})))
         {:exit (.exitValue process)
          :out (slurp (.getInputStream process))
          :err (slurp (.getErrorStream process))}
         (finally (.destroyForcibly process)))))))

(describe "本机 AI provider 配置"
  (it "auto 识别第一个 argv 的 pi basename，兼容大小写、空格及 Windows 路径"
    (doseq [command ["pi" "pi -p" "/usr/bin/PI --model sonnet"
                     "\"/some path/Pi\" --mode text"
                     "C:\\tools\\PI.CMD -p" "\"C:\\Program Files\\Pi.EXE\" -p"]]
      (should= {:provider "pi" :command command :timeout 180}
               (provider/configuration {:opts {:agent-command command}}))))

  (it "auto 识别 pi-acp basename，兼容大小写、空格及 Windows 扩展名"
    (doseq [command ["pi-acp" "/usr/bin/PI-ACP" "\"/some path/Pi-Acp\""
                     "C:\\tools\\PI-ACP.CMD" "\"C:\\Program Files\\Pi-Acp.EXE\""]]
      (should= {:provider "pi-acp" :command command :timeout 180}
               (provider/configuration {:opts {:agent-command command}}))))

  (it "显式 pi-acp 默认命令为 pi-acp"
    (doseq [command [nil "" "  "]]
      (should= {:provider "pi-acp" :command "pi-acp" :timeout 180}
               (provider/configuration {:opts {:agent-provider "PI-ACP" :agent-command command}}))))

  (it "auto 不把包装命令或仅包含 pi 的名字识别为 pi"
    (doseq [command ["echo pi" "env pi" "node /bin/pi" "not-pi" "/bin/pi-wrapper" "pi.js"
                     "node /bin/pi-acp" "not-pi-acp" "pi-acp.js" "pi-acp-wrapper"]]
      (should= "command" (:provider (provider/configuration {:opts {:agent-command command}})))))

  (it "显式 pi 默认用 pi，可省略命令或使用空白命令"
    (doseq [command [nil "" "  "]]
      (should= {:provider "pi" :command "pi" :timeout 180}
               (provider/configuration {:opts {:agent-provider "pi" :agent-command command}}))))

  (it "显式 command 不自动收敛 pi 参数，并保留指定超时"
    (should= {:provider "command" :command "pi --mode rpc" :timeout 12.5}
             (provider/configuration {:opts {:agent-provider "command" :agent-command "pi --mode rpc"
                                            :agent-timeout 12.5}})))

  (it "auto 和 command 缺命令返回 409，优先提示设置 pi provider"
    (doseq [opts [{} {:agent-command " "} {:agent-provider "command"}]]
      (let [ex (failure #(provider/configuration {:opts opts}))]
        (should= 409 (:status (ex-data ex)))
        (should-contain "工具目录" (.getMessage ex))
        (should (< (.indexOf (.getMessage ex) "ARCH_VIEW_AGENT_PROVIDER=pi")
                   (.indexOf (.getMessage ex) "ARCH_VIEW_AGENT_COMMAND="))))))

  (it "未知 provider 或非法超时返回 400"
    (doseq [provider ["other" 123 :unknown]]
      (should= 400 (status-of #(provider/configuration {:opts {:agent-provider provider}}))))
    (doseq [timeout [nil 0 -1 "180"]]
      (should= 400 (status-of #(provider/configuration {:opts {:agent-provider "pi" :agent-timeout timeout}})))))

  (it "统一拆分双引号和空白，shell 语法及 Windows 反斜杠保持字面量"
    (should= ["/path with space/pi" "two words" "" "$(touch injected);" "C:\\pi\\pi.exe"]
             (provider/parse-command " \t\"/path with space/pi\"  \"two words\" \"\" \"$(touch injected);\" C:\\pi\\pi.exe\n")))

  (it "拒绝缺少可执行文件或未闭合的引号"
    (doseq [command [nil "" " " "\"\"" "pi \"unfinished"]]
      (should= 400 (status-of #(provider/parse-command command))))))

(describe "pi-acp 私有启动准备"
  (with ws (fixture/project))
  (with scratch (Files/createDirectory (.resolve (:root @ws) "scratch") (make-array FileAttribute 0)))
  (with source (.resolve (:root @ws) "original-agent"))
  (with acp-ws (assoc @ws :opts {:agent-provider "pi-acp" :pi-agent-dir (str @source)}))
  (after (fixture/cleanup! @ws))

  (it "返回 ACP 协议和原 adapter argv，默认底层 pi，独立 HOME 及会话目录"
    (let [argv ["node" "/adapter path/index.js"]
          prepared (provider/prepare @acp-ws argv @scratch)
          env (:env prepared)
          launch (io/file (get env "PI_ACP_PI_COMMAND"))
          text (slurp launch)]
      (should= {:args argv :provider "pi-acp" :protocol :acp} (dissoc prepared :env))
      (should= (str (.resolve @scratch "pi-acp-home")) (get env "HOME"))
      (should= (str (.resolve @scratch "pi-sessions")) (get env "PI_CODING_AGENT_SESSION_DIR"))
      (should (.isDirectory (io/file (get env "HOME"))))
      (should (.canExecute launch))
      (should-contain "\"command\":\"pi\"" text)
      (should-not-contain "__ARCH_VIEW_PI_ACP_CONFIG__" text)
      (should-not-contain "--print" text)
      (should-not-contain "--no-session" text)
      (should= "rwx------" (PosixFilePermissions/toString
                               (Files/getPosixFilePermissions (.toPath launch) (make-array LinkOption 0))))))

  (it "先复制真实源 auth/settings，继承只读锁钩子、禁更新遥测和父会话清理"
    (let [auth {"fake" {"key" "fixture-private-key"}}
          settings {"defaultModel" "fake-model" "extensions" ["evil"]}]
      (put-json! (:root @ws) "original-agent/auth.json" auth)
      (put-json! (:root @ws) "original-agent/settings.json" settings)
      (let [prepared (provider/prepare @acp-ws ["pi-acp"] @scratch)
            env (:env prepared)
            agent (get env "PI_CODING_AGENT_DIR")]
        (should= auth (read-json agent "auth.json"))
        (should= {"defaultModel" "fake-model"} (read-json agent "settings.json"))
        (should-not-contain "fixture-private-key" (pr-str prepared))
        (should-contain "pi_readonly_locks.cjs" (get env "NODE_OPTIONS"))
        (should= ["1" "1" "0"] (mapv env ["PI_OFFLINE" "PI_SKIP_VERSION_CHECK" "PI_TELEMETRY"]))
        (doseq [name ["PI_SESSION_ID" "PI_SESSION_FILE" "PI_PROVIDER" "PI_MODEL" "PI_REASONING_LEVEL"]]
          (should (contains? env name))
          (should-be-nil (get env name))))))

  (it "实际执行 wrapper，保留底层 pi 模型参数，强制持久 RPC 和只读工具"
    (let [fake (fixture/put! (:root @ws) "fake pi 'quote' $dollar;$(literal).cjs"
                           (str "#!/usr/bin/env node\n"
                                "process.stdin.resume();process.stdin.on('end',()=>{"
                                "process.stdout.write(JSON.stringify(process.argv.slice(2)));"
                                "process.stderr.write('fake stderr');process.exitCode=37;});\n"))
          _ (.setExecutable fake true true)
          ws (assoc-in @acp-ws [:opts :pi-command]
                       (str "\"" fake "\" --model custom/demo --provider custom --thinking high --models custom/*"))
          directory (Files/createDirectory (.resolve @scratch "space \"quote\" \\slash $dollar;$(literal)")
                                           (make-array FileAttribute 0))
          prepared (provider/prepare ws ["pi-acp"] directory)
          env (:env prepared)
          result (run-command [(get env "PI_ACP_PI_COMMAND") "--mode" "rpc" "--no-themes"] env)
          args (json/read-json (:out result))]
      (should= [37 "fake stderr"] [(:exit result) (:err result)])
      (should= ["--model" "custom/demo" "--provider" "custom" "--thinking" "high" "--models" "custom/*"
                "--mode" "rpc" "--no-extensions" "--no-mcp" "--tools" "read,grep,find,ls"
                "--no-skills" "--no-prompt-templates" "--no-themes" "--no-context-files"
                "--no-approve" "--offline" "--session-dir" (get env "PI_CODING_AGENT_SESSION_DIR")] args)
      (should-not-contain "--no-session" args)
      (should= #{} (set (.list (io/file (get env "HOME")))))))

  (it "底层 pi 拒绝可覆盖隔离或泄露凭据的参数，并在写入前失败"
    (doseq [command ["pi --tools bash" "pi --session old.jsonl" "pi --mode rpc"
                     "pi --extension evil.js" "pi --api-key fixture-private-key"
                     "pi --model" "pi prompt" "pi -- --offline"]]
      (let [ex (failure #(provider/prepare (assoc-in @acp-ws [:opts :pi-command] command)
                                         ["pi-acp"] @scratch))]
        (should= 400 (:status (ex-data ex)))
        (should-not-contain "fixture-private-key" (str ex))))
    (should= #{} (set (.list (.toFile @scratch)))))

  (it "底层 pi 未配置或空白时采用 pi"
    (doseq [[index command] (map-indexed vector [nil "" "  "])]
      (let [directory (Files/createDirectory (.resolve @scratch (str index)) (make-array FileAttribute 0))
            prepared (provider/prepare (assoc-in @acp-ws [:opts :pi-command] command) ["pi-acp"] directory)]
        (should-contain "\"command\":\"pi\"" (slurp (get-in prepared [:env "PI_ACP_PI_COMMAND"]))))))

  (it "普通 pi 不使用 ACP 的底层命令或 HOME"
    (let [ws (assoc @acp-ws :opts {:agent-provider "pi" :pi-agent-dir (str @source)
                                  :pi-command "pi --api-key ignored"})
          prepared (provider/prepare ws ["original-pi"] @scratch)]
      (should= "original-pi" (first (:args prepared)))
      (should-not (contains? (:env prepared) "HOME"))
      (should-not (contains? prepared :protocol)))))

(describe "pi CLI 隔离准备"
  (with ws (fixture/project))
  (with scratch (Files/createDirectory (.resolve (:root @ws) "scratch") (make-array FileAttribute 0)))
  (with source (.resolve (:root @ws) "original-agent"))
  (with pi-ws (assoc @ws :opts {:agent-provider "pi" :pi-agent-dir (str @source)}))
  (after (fixture/cleanup! @ws))

  (it "强制单次 text、只读工具、禁用 MCP、扩展及所有自动资源"
    (let [{:keys [args provider]} (provider/prepare @pi-ws ["pi"] @scratch)]
      (should= "pi" provider)
      (should= ["pi" "--print" "--mode" "text" "--no-session" "--no-extensions" "--no-mcp"
                "--tools" "read,grep,find,ls" "--no-skills" "--no-prompt-templates"
                "--no-themes" "--no-context-files" "--no-approve" "--offline"] args)))

  (it "保留模型选择参数，兼容并归一化 print/text 及只读参数"
    (let [model-args ["--model" "custom/sonnet:high" "--provider" "custom"
                      "--thinking" "high" "--models" "custom/*,other/model"]
          args (into ["pi" "-p" "--print" "--mode" "text" "--no-mcp" "-ne" "-nc" "-na"] model-args)
          prepared (:args (provider/prepare @pi-ws args @scratch))]
      (should= (into ["pi"] model-args) (subvec prepared 0 (inc (count model-args))))
      (should= 1 (count (filter #{"--print"} prepared)))
      (should= 1 (count (filter #{"--no-mcp"} prepared)))))

  (it "白名单拒绝可覆盖隔离的参数、其它模式、子命令和额外提示词"
    (doseq [args [["-e" "evil.js"] ["--extension" "evil.js"] ["--tools" "bash"]
                  ["-t" "read"] ["--tools=read"] ["--system-prompt" "override"]
                  ["--append-system-prompt" "override"] ["--continue"] ["-c"]
                  ["--session" "old.jsonl"] ["--session-dir" "/tmp"] ["--resume"]
                  ["--mode" "rpc"] ["--mode" "json"] ["--approve"] ["-a"]
                  ["--skill" "evil"] ["--theme" "evil"] ["--prompt-template" "evil"]
                  ["--api-key" "fake-secret"] ["--api-key=fake-secret"]
                  ["--model"] ["--thinking" "--extension"] ["--mode"]
                  ["auth" "print-api-key"] ["install" "evil"] ["rpc"]
                  ["@private-file"] ["prompt"] ["--" "--extension" "evil"]]]
      (let [ex (failure #(provider/prepare @pi-ws (into ["pi"] args) @scratch))]
        (should= 400 (:status (ex-data ex)))
        (should-contain "支持 --model" (.getMessage ex))
        (should-not-contain "fake-secret" (.getMessage ex))))
    (should-not (.exists (io/file (str @scratch) "pi-agent"))))

  (it "复制常规 auth/models 到私有目录且不修改源文件，不返回假凭据"
    (let [auth {"fake" {"type" "api_key" "key" "fixture-private-key"}}
          models {"providers" {"fake" {"baseUrl" "https://example.invalid" "apiKey" "FAKE_KEY"}}}
          original-auth (put-json! (:root @ws) "original-agent/auth.json" auth)
          original-models (put-json! (:root @ws) "original-agent/models.json" models)
          auth-text (slurp original-auth)
          result (provider/prepare @pi-ws ["pi"] @scratch)
          target (get-in result [:env "PI_CODING_AGENT_DIR"])
          auth-path (.toPath (io/file target "auth.json"))]
      (should= (str (.resolve @scratch "pi-agent")) target)
      (should= auth (read-json target "auth.json"))
      (should= models (read-json target "models.json"))
      (should= auth-text (slurp original-auth))
      (should= models (json/read-json (slurp original-models)))
      (should= "rw-------" (PosixFilePermissions/toString (Files/getPosixFilePermissions auth-path (make-array LinkOption 0))))
      (should= "rwx------" (PosixFilePermissions/toString (Files/getPosixFilePermissions (.getParent auth-path) (make-array LinkOption 0))))
      (should-not-contain "fixture-private-key" (pr-str result))
      (spit (io/file target "auth.json") "{}")
      (should= auth-text (slurp original-auth))))

  (it "仅复制模型和网络设置，过滤资源、shell、session 和 retry 的未知字段"
    (let [allowed {"defaultProvider" "fake" "defaultModel" "demo"
                   "defaultThinkingLevel" "high" "modelThinkingLevels" {"fake/demo" "low"}
                   "thinkingBudgets" {"high" 8192} "enabledModels" ["fake/*"]
                   "transport" "sse" "httpProxy" "http://localhost:8080" "httpIdleTimeoutMs" 30000
                   "websocketConnectTimeoutMs" 10000
                   "retry" {"enabled" true "maxRetries" 2 "baseDelayMs" 20 "maxAgentDelayMs" 100
                            "provider" {"timeoutMs" 5000 "maxRetries" 0 "maxRetryDelayMs" 1000}}}
          settings (merge allowed {"packages" ["npm:evil"] "extensions" ["evil.js"] "skills" ["evil"]
                                   "prompts" ["evil"] "themes" ["evil"] "shellPath" "/evil"
                                   "shellCommandPrefix" "evil" "sessionDir" "/old-session"
                                   "defaultTools" ["bash"] "defaultProjectTrust" "always"
                                   "npmCommand" ["evil"] "selfUpdateCommand" ["evil"]})
          settings (-> settings (assoc-in ["retry" "shellPath"] "/evil")
                       (assoc-in ["retry" "provider" "extension"] "evil.js"))
          original (put-json! (:root @ws) "original-agent/settings.json" settings)
          result (provider/prepare @pi-ws ["pi"] @scratch)]
      (should= allowed (read-json (get-in result [:env "PI_CODING_AGENT_DIR"]) "settings.json"))
      (should= settings (json/read-json (slurp original)))))

  (it "不复制会话、MCP、扩展、上下文或系统提示词"
    (doseq [name ["sessions/old.jsonl" "extensions/evil.js" "skills/SKILL.md" "mcp.json"
                  "AGENTS.md" "SYSTEM.md" "APPEND_SYSTEM.md" "keybindings.json"]]
      (fixture/put! (:root @ws) (str "original-agent/" name) "untrusted"))
    (let [result (provider/prepare @pi-ws ["pi"] @scratch)
          target (io/file (get-in result [:env "PI_CODING_AGENT_DIR"]))]
      (should= #{} (set (.list target)))
      (should= #{} (set (.list (io/file (get-in result [:env "PI_CODING_AGENT_SESSION_DIR"])))))))

  (it "跳过符号链接和非普通配置文件，避免沿链接复制内容"
    (let [outside (fixture/put! (:root @ws) "outside.json" "{\"fake-secret\":true}")]
      (.mkdirs (.toFile @source))
      (doseq [name ["auth.json" "models.json" "settings.json"]]
        (Files/createSymbolicLink (.resolve @source name) (.toPath outside) (make-array FileAttribute 0)))
      (let [target (get-in (provider/prepare @pi-ws ["pi"] @scratch) [:env "PI_CODING_AGENT_DIR"])]
        (should= #{} (set (.list (io/file target)))))))

  (it "不将非普通文件夹作为 auth 或 models 复制"
    (doseq [name ["auth.json" "models.json" "settings.json"]]
      (.mkdirs (.toFile (.resolve @source name))))
    (let [target (get-in (provider/prepare @pi-ws ["pi"] @scratch) [:env "PI_CODING_AGENT_DIR"])]
      (should= #{} (set (.list (io/file target))))))

  (it "不跟随预先放在目标的符号链接"
    (.mkdirs (.toFile @source))
    (Files/createSymbolicLink (.resolve @scratch "pi-agent") @source (make-array FileAttribute 0))
    (should-throw java.nio.file.FileAlreadyExistsException (provider/prepare @pi-ws ["pi"] @scratch))
    (should= #{} (set (.list (.toFile @source))))
    (Files/delete (.resolve @scratch "pi-agent")))

  (it "非法 settings 返回不带内容的 400 错误"
    (fixture/put! (:root @ws) "original-agent/settings.json" "invalid fixture-private-key")
    (let [ex (failure #(provider/prepare @pi-ws ["pi"] @scratch))]
      (should= 400 (:status (ex-data ex)))
      (should-not-contain "fixture-private-key" (str ex))))

  (it "隔离运行目录、禁启动更新和遥测，并清除父会话标识"
    (let [env (:env (provider/prepare @pi-ws ["pi"] @scratch))]
      (should= (str (.resolve @scratch "pi-sessions")) (get env "PI_CODING_AGENT_SESSION_DIR"))
      (should= "1" (get env "PI_OFFLINE"))
      (should= "1" (get env "PI_SKIP_VERSION_CHECK"))
      (should= "0" (get env "PI_TELEMETRY"))
      (doseq [name ["PI_SESSION_ID" "PI_SESSION_FILE" "PI_PROVIDER" "PI_MODEL" "PI_REASONING_LEVEL"]]
        (should (contains? env name))
        (should-be-nil (get env name)))))

  (it "预先复制私有 shim，替换父 NODE_OPTIONS，正确引用含空格、引号及反斜杠的路径"
    (let [directory (Files/createDirectory (.resolve (:root @ws) "scratch space \"quote\" \\slash")
                                           (make-array FileAttribute 0))
          prepared (provider/prepare @pi-ws ["pi"] directory)
          preload (.toFile (.resolve directory "pi_readonly_locks.cjs"))
          env (:env prepared)
          hook (fixture/put! (:root @ws) "parent-hook.cjs" "throw Error('parent hook loaded')")
          child (run-command ["node" "-e" ""] env {"NODE_OPTIONS" (str "--require=" hook)})]
      (should (.isFile preload))
      (should= (slurp (io/resource "arch_view/context/pi_readonly_locks.cjs")) (slurp preload))
      (should= "rw-------" (PosixFilePermissions/toString
                               (Files/getPosixFilePermissions (.toPath preload) (make-array LinkOption 0))))
      (should= (str "--require=\"" (-> (.getAbsolutePath preload)
                                      (str/replace "\\" "\\\\")
                                      (str/replace "\"" "\\\"")) "\"")
               (get env "NODE_OPTIONS"))
      (should= {:exit 0 :out "" :err ""} child)))

  (it "真实沙箱内私有锁探测及多次续租成功，宿主 utimes 和 Python os.utime 仍被禁止"
    (let [session (sandbox/open! {})
          host (.resolve (:root @ws) "host-locks")]
      (try
        (Files/createDirectory host (make-array FileAttribute 0))
        (Files/createDirectory (.resolve host "outside.lock") (make-array FileAttribute 0))
        (let [prepared (provider/prepare @pi-ws ["pi"] (:directory session))
              env (merge (:env session) (:env prepared)
                         {"PI_LOCK_TEST_SANDBOX" "1" "PI_LOCK_TEST_HOST" (str host)})
              result (run-command ((:wrap session)
                                   ["node" "--test" "--test-reporter=tap"
                                    (.getAbsolutePath (io/file "spec/node/pi_readonly_locks_test.cjs"))]) env)
              python (str "import errno,os,sys\n"
                          "try:\n os.utime(sys.argv[1],(1,1))\n"
                          "except OSError as error:\n assert error.errno in (errno.EPERM,errno.EACCES)\n"
                          "else:\n raise AssertionError('host utime succeeded')\n")
              host-result (run-command ((:wrap session)
                                        ["python3" "-I" "-B" "-c" python (str (.resolve host "outside.lock"))]) env)]
          (should= [0 ""] [(:exit result) (str (:err result) (when-not (zero? (:exit result)) (:out result)))])
          (should-contain "# fail 0" (:out result))
          (should= {:exit 0 :out "" :err ""} host-result))
        (finally ((:cleanup session))))))

  (it "command 模式保持 argv 原样和空 env，不创建任何配置目录"
    (let [args ["pi" "--mode" "rpc" "--extension" "custom.js"]
          ws (assoc-in @pi-ws [:opts :agent-provider] "command")
          ws (assoc-in ws [:opts :agent-command] "pi --mode rpc")]
      (should= {:args args :env {} :provider "command"} (provider/prepare ws args @scratch))
      (should= #{} (set (.list (.toFile @scratch)))))))
