;; 职责：验证解释 CLI 的中文输出、参数校验、严格退出码和移动修复落盘。
;; 核心入口：Speclj 命令规格。

(ns arch-view.notes.cli-spec
  (:require [arch-view.notes.cli :as cli]
            [arch-view.notes.core :as notes]
            [arch-view.notes.store :as store]
            [arch-view.notes.test-support :as fixture]
            [arch-view.cli-support :as support]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "解释命令"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))

  (it "帮助包含最小 JSON 示例且无需加载工作区"
    (with-redefs [support/load-workspace (fn [_] (throw (Exception. "不应分析")))]
      (let [text (with-out-str (cli/run! ["--help"]))]
        (should-contain "最小草稿 JSON" text)
        (should-contain "\"targets\"" text)
        (should-contain "--strict" text))))

  (it "save、list --target 和 show 输出正文与锚点状态"
    (let [file (io/file (.toFile (:root @ws)) "draft.json")]
      (support/write-json-file! file (assoc fixture/draft :id "web"))
      (with-redefs [support/load-workspace (constantly @ws)]
        (should-contain "解释正文" (with-out-str (cli/run! ["save" (.getPath file)])))
        (should-contain "[unanchored] 网页解释（web）— app.web.model — ai/"
                        (with-out-str (cli/run! ["list" "--target" "app.web"])))
        (should-contain "无锚点" (with-out-str (cli/run! ["show" "web"])))
        (should= 409 (:status (fixture/error-data #(with-out-str (cli/run! ["save" (.getPath file)])))))
        (with-out-str (cli/run! ["save" (.getPath file) "--force"])))))

  (it "JSON 列表返回数据本身且没有 Markdown"
    (store/write-note! @ws (notes/prepare @ws fixture/draft))
    (with-redefs [support/load-workspace (constantly @ws)]
      (let [data (support/read-json-text (with-out-str (cli/run! ["list" "--format" "json"])))]
        (should= "解释正文" (:body (first data)))
        (should= "unanchored" (:status (first data))))))

  (it "strict 对 stale、missing、targetMissing 返回 2，其它状态返回 0"
    (with-redefs [support/load-workspace (constantly @ws)]
      (doseq [[status missing? expected] [["unanchored" false 0] ["fresh" false 0]
                                         ["moved" false 0] ["stale" false 2]
                                         ["missing" false 2] ["fresh" true 2]]]
        (with-redefs [store/list-notes (constantly [fixture/draft])
                      notes/check (fn [_ note] (assoc note :status status :targetMissing missing?))]
          (let [result (atom nil)]
            (with-out-str (reset! result (cli/run! ["check" "--strict"])))
            (should= expected @result))))))

  (it "真实目标被移除时 strict 返回 2，普通 check 返回 0"
    (store/write-note! @ws (notes/prepare @ws (assoc fixture/draft :targets ["app.data"])))
    (with-redefs [support/load-workspace (constantly (assoc-in @ws [:architecture :graph :nodes] #{}))]
      (let [strict-code (atom nil) normal-code (atom nil)]
        (with-out-str (reset! strict-code (cli/run! ["check" "--strict"])))
        (with-out-str (reset! normal-code (cli/run! ["check"])))
        (should= 2 @strict-code)
        (should= 0 @normal-code))))

  (it "check --fix-moved 只修复移动锚点并保留存储格式"
    (let [note (store/write-note! @ws (notes/prepare @ws (assoc fixture/draft :anchors [{:path "src/web.clj" :startLine 2}])))]
      (spit (io/file (.toFile (:root @ws)) "src/web.clj") "新增\n头部\n(defn view [] :ok)\n尾部\n")
      (with-redefs [support/load-workspace (constantly @ws)]
        (should-contain "[moved]" (with-out-str (cli/run! ["check" "--fix-moved"]))))
      (let [saved (store/read-note @ws (:id note))]
        (should= 3 (get-in saved [:anchors 0 :startLine]))
        (should-not (contains? saved :status))
        (should-not (contains? (first (:anchors saved)) :status))
        (should= "fresh" (:status (notes/check @ws saved))))))

  (it "错误命令、参数数量和不适用开关返回 400"
    (doseq [args [["bad"] ["save"] ["list" "extra"] ["check" "--force"] ["show" "web" "--target" "app"]]]
      (should= 400 (:status (fixture/error-data #(cli/run! args)))))))
