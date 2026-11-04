;; 职责：验证 entries 命令的帮助、参数校验、列表过滤与检查报告的退出码。
;; 核心入口：Speclj 命令测试。

(ns arch-view.entries.cli-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.entries.cli :as sut]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [speclj.core :refer :all]))

(def ^:private files
  {"src/main/java/p/Web.java"
   (java "p" "import org.springframework.web.bind.annotation.*;\n"
         "@RestController public class Web { @GetMapping(\"/w\") public void w() {} }")
   "src/main/java/p/Job.java"
   (java "p" "import org.springframework.scheduling.annotation.Scheduled;\n"
         "public class Job { @Scheduled(cron = \"0 * * * * ?\") public void run() {} }")})

(defn- invoke [rules args]
  (fixtures/with-project files rules
    (fn [root]
      (let [ws (fixtures/workspace-of root)
            code (atom nil)
            output (with-out-str
                     (with-redefs [cli-support/load-workspace (constantly ws)]
                       (reset! code (sut/run! args))))]
        {:code @code :output output}))))

(describe "entries 命令"
  (it "帮助不加载工作区"
    (with-redefs [cli-support/load-workspace (fn [_] (throw (Exception. "不应加载")))]
      (let [output (with-out-str (should= 0 (sut/run! ["--help"])))]
        (should-contain "entries <命令>" output)
        (should-contain "--strict" output))))

  (it "未知子命令与无效参数报错"
    (should-throw clojure.lang.ExceptionInfo "未知子命令：show；请运行 arch-view entries --help" (sut/run! ["show"]))
    (should-throw clojure.lang.ExceptionInfo "--kind 只能是 event、http、mq、rpc、schedule、startup"
                  (sut/run! ["list" "--kind" "web"]))
    (should-throw clojure.lang.ExceptionInfo "--limit 必须是正整数" (sut/run! ["list" "--limit" "0"])))

  (it "list 按类型过滤并输出 JSON"
    (let [{:keys [code output]} (invoke nil ["list" "--kind" "schedule" "--format" "json"])
          data (cli-support/read-json-text output)]
      (should= 0 code)
      (should= 1 (:total data))
      (should= "cron 0 * * * * ?" (get-in data [:entries 0 :label]))
      (should= "missing" (get-in data [:rules :status]))))

  (it "list 的 Markdown 按业务域分节，行首为机制"
    (let [{:keys [output]} (invoke nil ["list"])]
      (should-contain "# 入口（2）" output)
      (should-contain "分组来源：inferred（默认）" output)
      (should-contain "## w（1）" output)
      (should-contain "| HTTP | GET /w | Web#w | src/main/java/p/Web.java:3 |" output)))

  (it "--group-by 只接受四种来源"
    (should-throw clojure.lang.ExceptionInfo "--group-by 只能是 rules、architecture、annotation、inferred"
                  (invoke nil ["list" "--group-by" "package"])))

  (it "check 输出机制总览；--strict 有警告时退出码 2"
    (let [rules {:version 1 :rules [{:id "never" :annotation "x.Nope" :as "kind:rpc"}]}
          normal (invoke rules ["check"])
          strict (invoke rules ["check" "--strict"])]
      (should= 0 (:code normal))
      (should-contain "| HTTP | builtin:spring-mvc | 1 |" (:output normal))
      (should-contain "zero-hit" (:output normal))
      (should-contain "入口 2（HTTP 1 / 定时 1），业务域 2，未归类 0，警告 1。" (:output normal))
      (should= 2 (:code strict))))

  (it "规则文件有错时 check 退出码 1，仍用内置规则列出入口"
    (let [{:keys [code output]} (invoke {:version 1 :rules [{:id "bad" :as "kind:rpc"}]} ["check"])]
      (should= 1 code)
      (should-contain "有错误，暂只用内置规则" output)
      (should-contain "入口 2" output))))
