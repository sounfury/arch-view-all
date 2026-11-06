;; 职责：用独立可执行脚本验证 agent 输入、并发输出、错误及超时边界。
;; 核心入口：本机 agent Speclj 示例。

(ns arch-view.context.agent-spec
  (:require [arch-view.anchor :as anchor]
            [arch-view.context.agent :as agent]
            [arch-view.context.sessions :as sessions]
            [clojure.string :as str]
            [arch-view.context.fixtures :as fixture :refer [status-of]]
            [speclj.core :refer :all]))

(describe "本机 AI 问答"
  (with ws (fixture/project))
  (after (fixture/cleanup! @ws))

  (it "未配置时返回 409，并提示工具目录的 .env 配置"
    (try (agent/ask! @ws "demo.web.model" "解释模型")
         (should-fail "应要求配置命令")
         (catch clojure.lang.ExceptionInfo ex
           (should= 409 (:status (ex-data ex)))
           (should-contain "工具目录" (.getMessage ex))
           (should-contain "ARCH_VIEW_AGENT_PROVIDER=pi-acp" (.getMessage ex)))))

  (it "在项目根运行，接收完整 Markdown 并关闭输入；支持双引号参数和有空格的可执行路径"
    (let [script (fixture/script! @ws "fake agent" "printf 'cwd=%s arg=%s\\n' \"$PWD\" \"$1\"\ncat")
          command (str "\"" script "\" \"两个 参数\"")
          result (agent/ask! (assoc @ws :opts {:agent-command command}) "demo.web.model" "模型如何构建？")]
      (should= command (:command result))
      (should (<= 0 (:elapsedMs result)))
      (should-contain (str "cwd=" (:root @ws) " arg=两个 参数") (:answer result))
      (should-contain "## 1. 目标" (:answer result))
      (should-contain "模型如何构建？" (:answer result))
      (should= [(anchor/make-anchor @ws {:path "src/demo/web/model.clj" :startLine 1 :endLine 4})]
               (:anchors result))))

  (it "截断源码的锚点仅覆盖 AI 所见的前 300 行，不包含未收录的行"
    (let [path "src/demo/web/model.clj"
          lines (mapv #(str ";; 源码第 " % " 行") (range 1 306))
          file (fixture/put! (:root @ws) path (str/join "\n" lines))
          result (agent/ask! (assoc @ws :opts {:agent-command "cat"}) "demo.web.model" "解释")
          item (first (:anchors result))]
      (should= [(anchor/make-anchor @ws {:path path :startLine 1 :endLine 300})] (:anchors result))
      (should-contain "300 | ;; 源码第 300 行" (:answer result))
      (should-not-contain "301 |" (:answer result))
      (spit file (str/join "\n" (assoc lines 300 ";; 未收录的内容已修改")))
      (should= "fresh" (:status (anchor/check-anchor @ws item)))
      (spit file (str/join "\n" (assoc lines 299 ";; 已收录的内容已修改")))
      (should= "stale" (:status (anchor/check-anchor @ws item)))))

  (it "只有成员及文件头的包目标不生成整文件锚点"
    (let [result (agent/ask! (assoc @ws :opts {:agent-command "cat"}) "demo.web" "解释包")]
      (should-contain "接口职责" (:answer result))
      (should-not-contain "(ns demo.web" (:answer result))
      (should= [] (:anchors result))))

  (it "空文件或不可用源码不生成锚点"
    (doseq [missing? [false true]]
      (let [file (fixture/put! (:root @ws) "src/demo/web/model.clj" "")]
        (when missing? (.delete file))
        (should= [] (:anchors (agent/ask! (assoc @ws :opts {:agent-command "cat"})
                                        "demo.web.model" "解释"))))))

  (it "AI 进程不能修改源码，返回错误且锚点内容保持不变"
    (let [expected (anchor/make-anchor @ws {:path "src/demo/web/model.clj" :startLine 1 :endLine 4})
          script (fixture/script! @ws "editing-agent"
                                   "cat\nprintf 'changed\\n' > src/demo/web/model.clj")]
      (should= 502 (status-of #(agent/ask! (assoc @ws :opts {:agent-command script}) "demo.web.model" "解释")))
      (should= "fresh" (:status (anchor/check-anchor @ws expected)))))

  (it "每次调用返回沙箱标记且不提供退出沙箱的配置"
    (let [result (agent/ask! (assoc @ws :opts {:agent-command "cat" :agent-sandbox "off"})
                             "demo.web.model" "解释")]
      (should= true (get-in result [:sandbox :readOnly]))
      (should (#{"linux-landlock" "macos-sandbox"} (get-in result [:sandbox :kind])))
      (should= "command" (:provider result))))

  (it "同时消费 stderr 和 stdout，避免管道缓冲区塞满而死锁"
    (let [script (fixture/script! @ws "noisy-agent"
                                   "dd if=/dev/zero bs=1024 count=96 >&2 2>/dev/null\ncat >/dev/null\nprintf '回答完成'")
          result (agent/ask! (assoc @ws :opts {:agent-command script :agent-timeout 3}) "demo.web.model" "解释")]
      (should= "回答完成" (:answer result))))

  (it "非零退出返回 stderr 摘要和退出码"
    (let [script (fixture/script! @ws "failed-agent" "cat >/dev/null\nprintf '授权失败' >&2\nexit 7")]
      (try (agent/ask! (assoc @ws :opts {:agent-command script}) "demo.web.model" "解释")
           (should-fail "应报告失败")
           (catch clojure.lang.ExceptionInfo ex
             (should= 502 (:status (ex-data ex)))
             (should= 7 (:exitCode (ex-data ex)))
             (should-contain "授权失败" (.getMessage ex))))))

  (it "超时强制结束并返回 504，输入未被读取也不会卡死"
    (let [script (fixture/script! @ws "slow-agent" "sleep 10")]
      (fixture/put! (:root @ws) "src/demo/web/model.clj" (apply str (repeat 200000 "x")))
      (let [started (System/nanoTime)]
        (should= 504 (status-of #(agent/ask! (assoc @ws :opts {:agent-command script :agent-timeout 0.1}) "demo.web.model" "解释")))
        (should (< (/ (- (System/nanoTime) started) 1e9) 3)))))

  (it "stdout 与 stderr 输出合计超过约一 MB 时停止并报错"
    (let [script (fixture/script! @ws "large-agent"
                                   (str "cat >/dev/null\n"
                                        "dd if=/dev/zero bs=1024 count=600 2>/dev/null\n"
                                        "dd if=/dev/zero bs=1024 count=600 >&2 2>/dev/null"))]
      (should= 502 (status-of #(agent/ask! (assoc @ws :opts {:agent-command script :agent-timeout 3}) "demo.web.model" "解释")))))

  (it "命令不经 shell，分号和命令替换保持普通参数"
    (let [script (fixture/script! @ws "literal-agent" "cat >/dev/null\nprintf '%s' \"$1\"")]
      (should= "$(touch injected); echo hello"
               (:answer (agent/ask! (assoc @ws :opts {:agent-command (str script " \"$(touch injected); echo hello\"")})
                                    "demo.web.model" "解释")))))

  (it "空问题返回 400"
    (should= 400 (status-of #(agent/ask! (assoc @ws :opts {:agent-command "cat"}) "demo.web.model" "  "))))

  (it "非 ACP 配置拒绝续聊"
    (should= 409 (status-of #(agent/ask! (assoc @ws :opts {:agent-command "cat"})
                                        "demo.web.model" "解释" {:session-id "x"}))))

  (it "pi-acp 把问答交给会话管理并保留源码锚点"
    (with-redefs [sessions/ask! (fn [_ target {:keys [markdown]} options]
                                  (should= "demo.web.model" target)
                                  (should-contain "模型如何构建？" markdown)
                                  (should= {:session-id "sid"} options)
                                  {:answer "ACP 回答" :sessionId "sid" :provider "pi-acp"
                                   :sandbox {:kind "linux-landlock" :readOnly true}})]
      (let [result (agent/ask! (assoc @ws :opts {:agent-provider "pi-acp"})
                               "demo.web.model" "模型如何构建？" {:session-id "sid"})]
        (should= "ACP 回答" (:answer result))
        (should= "sid" (:sessionId result))
        (should= [(anchor/make-anchor @ws {:path "src/demo/web/model.clj" :startLine 1 :endLine 4})]
                 (:anchors result)))))

  (it "系统沙箱启动失败时返回 503，不会降级直接运行 AI"
    (should= 503 (status-of #(agent/ask!
                              (assoc @ws :opts {:agent-command "cat" :python "/no-such-sandbox-python"})
                              "demo.web.model" "解释"))))

  (it "不存在的可执行文件返回可读的 502 错误"
    (should= 502 (status-of #(agent/ask! (assoc @ws :opts {:agent-command "/no-such-agent"}) "demo.web.model" "解释")))))
