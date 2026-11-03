;; 职责：验证 CLI 保存、覆盖、输出、移动修正和严格模式退出码。
;; 核心入口：Speclj 链路命令测试。

(ns arch-view.trace.cli-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.trace.cli :as cli]
            [arch-view.trace.fixtures :as fixtures]
            [arch-view.trace.store :as store]
            [arch-view.trace.verify :as verify]
            [speclj.core :refer :all]))

(defn- invoke [ws args]
  (let [code (atom nil)
        output (with-out-str
                 (with-redefs [cli-support/load-workspace (constantly ws)]
                   (reset! code (cli/run! args))))]
    {:code @code :output output}))

(describe "链路 CLI"
  (with ws (fixtures/create!))
  (after (fixtures/cleanup! @ws))

  (it "帮助包含最小草稿并且不加载工作区"
    (with-redefs [cli-support/load-workspace (fn [_] (throw (Exception. "不应加载")))]
      (let [output (with-out-str (should= 0 (cli/run! ["--help"])))]
        (should-contain "最小草稿 JSON" output)
        (should-contain "\"startLine\":1" output)
        (should-contain "--strict" output))))

  (it "save 补全并输出校验结果，覆盖必须 --force"
    (let [file (fixtures/file @ws "draft.json")]
      (cli-support/write-json-file! file (fixtures/draft "a" "b"))
      (let [{:keys [code output]} (invoke @ws ["save" (str file) "--format" "json"])
            result (cli-support/read-json-text output)]
        (should= 0 code)
        (should= "direct" (get-in result [:hops 0 :status]))
        (should= "ai" (get-in result [:trace :author]))
        (should= (:trace result) (store/read-trace @ws "example")))
      (should= 409 (get-in (fixtures/exception-data #(invoke @ws ["save" (str file)])) [:data :status]))
      (cli-support/write-json-file! file (assoc (fixtures/draft "a") :title "覆盖标题"))
      (should= 0 (:code (invoke @ws ["save" (str file) "--force"])))
      (should= "覆盖标题" (:title (store/read-trace @ws "example")))))

  (it "list 和 show 支持 JSON，列表包含即时摘要"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a" "c")))
    (let [entries (cli-support/read-json-text (:output (invoke @ws ["list" "--format" "json"])))
          result (cli-support/read-json-text (:output (invoke @ws ["show" "example" "--format" "json"])))]
      (should= 2 (:stepCount (first entries)))
      (should= 1 (get-in entries [0 :summary :indirect]))
      (should= "example" (get-in result [:trace :id]))
      (should= "ok" (:verdict result))))

  (it "strict 在无证据时返回 2，普通 verify 返回 0"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a" "u")))
    (should= 0 (:code (invoke @ws ["verify" "example"])))
    (let [result (invoke @ws ["verify" "example" "--strict"])]
      (should= 2 (:code result))
      (should-contain "有 1 跳缺少静态证据，请人工复核" (:output result))))

  (it "strict 在 stale 和 missing 时返回 2"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a")))
    (spit (fixtures/file @ws "a.clj") "过期\n" :encoding "UTF-8")
    (should= 2 (:code (invoke @ws ["verify" "example" "--strict"])))
    (.delete (fixtures/file @ws "a.clj"))
    (should= 2 (:code (invoke @ws ["verify" "example" "--strict"]))))

  (it "strict 接受 moved，--fix-moved 更新存储并返回 fresh"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a")))
    (let [file (fixtures/file @ws "a.clj")]
      (spit file (str "新增\n" (slurp file :encoding "UTF-8")) :encoding "UTF-8"))
    (should= 0 (:code (invoke @ws ["verify" "example" "--strict"])))
    (should= 2 (get-in (store/read-trace @ws "example") [:steps 0 :anchor :startLine]))
    (let [result (invoke @ws ["verify" "example" "--fix-moved" "--strict" "--format" "json"])]
      (should= 0 (:code result))
      (should= 1 (get-in (cli-support/read-json-text (:output result)) [:summary :fresh]))
      (should= 3 (get-in (store/read-trace @ws "example") [:steps 0 :anchor :startLine]))))

  (it "--all 和省略 id 都校验所有链路，并汇总严格退出码"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a")))
    (store/write-trace! @ws (verify/prepare @ws (assoc (fixtures/draft "a" "u") :id "review")))
    (doseq [args [["verify" "--all" "--strict" "--format" "json"]
                  ["verify" "--strict" "--format" "json"]]]
      (let [result (invoke @ws args)]
        (should= 2 (:code result))
        (should= 2 (count (cli-support/read-json-text (:output result)))))))

  (it "空列表和空的全量严格校验成功"
    (should= 0 (:code (invoke @ws ["verify" "--all" "--strict"])))
    (should-contain "暂无链路" (:output (invoke @ws ["list"]))))

  (it "Markdown 包含逐步位置、模块、状态及间接路径"
    (let [result (verify/verify @ws (verify/prepare @ws (fixtures/draft "a" "c")))
          output (cli/markdown result)]
      (should-contain "1. 步骤-a — a.clj:2-2（a）[fresh]" output)
      (should-contain "≈ indirect" output)
      (should-contain "路径：a → b → c" output)
      (should-contain "汇总：" output)
      (should-contain "下一步：" output)))

  (it "每种跳状态输出契约图标"
    (doseq [[modules fragment] [[["a" "b"] "✓ direct"] [["b" "a"] "↩ reverse"]
                              [["a" "u"] "? unverified"] [["a" "asset"] "· unmapped"]
                              [["a" "a"] "= same-module"]]]
      (should-contain fragment (cli/markdown (verify/verify @ws (verify/prepare @ws (apply fixtures/draft modules)))))))

  (it "拒绝错误参数组合"
    (doseq [args [["unknown"] ["save"] ["show"] ["list" "example"]
                  ["verify" "example" "--all"] ["show" "example" "--fix-moved"]
                  ["list" "--force"] ["save" "draft.json" "--strict"]]]
      (should= 400 (get-in (fixtures/exception-data #(invoke @ws args)) [:data :status])))))
