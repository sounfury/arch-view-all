;; 职责：验证上下文包的职责匹配、静态依赖、复杂度、已有知识与源码输出。
;; 核心入口：上下文包 Speclj 示例。

(ns arch-view.context.pack-spec
  (:require [arch-view.anchor :as anchor]
            [arch-view.cli-support :as support]
            [arch-view.context.fixtures :as fixture]
            [arch-view.context.pack :as pack]
            [arch-view.web.json :as json]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "架构上下文包"
  (with ws (fixture/project))
  (after (fixture/cleanup! @ws))

  (it "按模块及祖先包匹配项目文档，并相对包目录匹配探索链接"
    (let [{:keys [target data markdown]} (pack/build @ws "demo.web.model" {})
          entries (:architecture data)]
      (should= "demo.web.model" target)
      (should= ["web · 网页" "service · 模型服务"] (mapv :title entries))
      (should= ["已完成" "进行中"] (mapv :status entries))
      (should= ["web" "web/model"] (mapv :module entries))
      (should-contain "构建模型。" (:body (second entries)))
      (should-not-contain "#module=" markdown)
      (should= "模型职责" (get-in data [:headers 0 :description]))
      (should= "src/demo/web/model.clj" (get-in data [:source 0 :path]))))

  (it "路径目标保留文件类型，源码带行号并明确截断数量"
    (let [{:keys [data markdown]} (pack/build @ws "src/demo/web/model.clj" {:max-source-lines 2 :question "如何构建模型？"})]
      (should= :file (get-in data [:target :kind]))
      (should= 4 (get-in data [:source 0 :totalLines]))
      (should= true (get-in data [:source 0 :truncated]))
      (should-contain "共 4 行，已截取前 2 行" markdown)
      (should-contain "2 | (ns demo.web.model)" markdown)
      (should-not-contain "3 |" markdown)
      (should-contain "## 8. 请回答的问题" markdown)
      (should-contain "路径:行号" markdown)
      (should-contain "标明为推断" markdown)
      (should-contain "不确定" markdown)))

  (it "按契约顺序输出八个部分，无问题时不附加第八部分"
    (let [markdown (:markdown (pack/build @ws "demo.web.model" {:question "解释"}))
          titles (mapv second (re-seq #"(?m)^## (\d)\." markdown))]
      (should= ["1" "2" "3" "4" "5" "6" "7" "8"] titles)
      (should-not-contain "请回答的问题" (:markdown (pack/build @ws "demo.web.model" {})))))

  (it "包目标只列包外依赖并去重，源码仅列成员和文件头"
    (let [{:keys [data markdown]} (pack/build @ws "demo.web" {})]
      (should= :package (get-in data [:target :kind]))
      (should= ["demo.util"] (mapv :module (get-in data [:dependencies :upstream])))
      (should= [] (get-in data [:dependencies :downstream]))
      (should= 3 (count (:source data)))
      (should-not (some :content (:source data)))
      (should-contain "接口职责" markdown)
      (should-not-contain "(ns demo.web" markdown)
      (should= ["web · 网页"] (mapv :title (:architecture data)))))

  (it "从原图及分类边标记一层上下游、抽象依赖与环"
    (let [deps (get-in (pack/build @ws "demo.web.model" {}) [:data :dependencies])]
      (should= ["demo.util" "demo.web.api"] (mapv :module (:upstream deps)))
      (should= ["demo.web.api" "demo.web.port"] (mapv :module (:downstream deps)))
      (should= :abstract (:type (second (:downstream deps))))
      (should= true (:cycle (first (:downstream deps))))
      (should= false (:cycle (second (:downstream deps))))))

  (it "缺少分类边时仍能读取原始依赖并识别抽象模块"
    (let [result (pack/build (update @ws :architecture dissoc :classified-edges) "demo.web.model" {})
          port (last (get-in result [:data :dependencies :downstream]))]
      (should= "demo.web.port" (:module port))
      (should= :abstract (:type port))
      (should= true (:abstract port))
      (should= :abstract (get-in (pack/build (update @ws :architecture dissoc :classified-edges) "demo.web.port" {})
                                 [:data :dependencies :upstream 0 :type]))))

  (it "依赖 Markdown 最多列二十项，JSON 保留总集合"
    (let [extra (mapv #(str "demo.other" %) (range 25))
          ws (-> @ws
                 (update-in [:architecture :graph :nodes] into extra)
                 (update-in [:architecture :graph :edges] into (map #(hash-map :from "demo.web.model" :to %) extra)))
          result (pack/build ws "demo.web.model" {})]
      (should= 27 (count (get-in result [:data :dependencies :downstream])))
      (should-contain "共 27 项，已列出前 20 项" (:markdown result))))

  (it "复用网页复杂度索引，最高复杂度优先且路径相对项目根"
    (let [path (.getCanonicalPath (io/file (.toFile (:root @ws)) "src/demo/web/model.clj"))
          result (pack/build @ws "demo.web.model"
                             {:complexity {:status "ready" :limit 10
                                           :files {path [{:symbol "easy" :line 2 :complexity 1}
                                                         {:symbol "hard" :line 3 :complexity 8}]}}})]
      (should= 8 (get-in result [:data :complexity :metrics :max]))
      (should= "hard" (get-in result [:data :complexity :metrics :functions 0 :symbol]))
      (should-contain "src/demo/web/model.clj:3" (:markdown result))))

  (it "auto 复用 crap 的项目发现与结果解析"
    (fixture/put! (:root @ws) "crap-result.json"
                  (json/write-json {:projects [{:project_path (str (:root @ws))
                                                :entries [{:file "src/demo/web/model.clj" :line 3
                                                           :symbol "load-model" :complexity 7}]}]}))
    (let [command (fixture/script! @ws "fake-crap" "cat \"$2/crap-result.json\"")
          result (pack/analyze-complexity (assoc @ws :opts {:crap-command command}))]
      (should= "ready" (:status result))
      (should= 7 (-> result :files vals first first :complexity))))

  (it "引用祖先包解释与步骤锚点涉及目标的链路，检查整条记录的最差新鲜度"
    (let [fresh (anchor/make-anchor @ws {:path "src/demo/web/model.clj" :startLine 1 :endLine 2})
          stale (assoc fresh :hash "sha256:old")]
      (support/write-json-file! (io/file (workspace/data-dir @ws) "notes/overview.json")
                                {:id "overview" :title "网页解释" :targets ["demo.web"] :anchors [fresh]})
      (support/write-json-file! (io/file (workspace/data-dir @ws) "notes/other.json")
                                {:id "other" :title "工具解释" :targets ["demo.util"] :anchors []})
      (support/write-json-file! (io/file (workspace/data-dir @ws) "traces/request.json")
                                {:id "request" :title "请求链路" :steps [{:anchor fresh} {:anchor stale}]})
      (let [refs (get-in (pack/build @ws "demo.web.model" {}) [:data :references])]
        (should= ["overview"] (mapv :id (:notes refs)))
        (should= "fresh" (get-in refs [:notes 0 :status]))
        (should= "stale" (get-in refs [:traces 0 :status]))
        (should= ["fresh" "stale"] (mapv :status (get-in refs [:traces 0 :anchors]))))
      (should= ["overview"] (mapv :id (get-in (pack/build @ws "demo.web" {}) [:data :references :notes])))))

  (it "拒绝无效记录 id 并保留读取提示；不存在的数据目录不报错"
    (should= [] (get-in (pack/build @ws "demo.web.model" {}) [:data :references :notes]))
    (support/write-json-file! (io/file (workspace/data-dir @ws) "notes/bad.json")
                              {:id "../bad" :title "无效" :targets ["demo.web.model"]})
    (let [refs (get-in (pack/build @ws "demo.web.model" {}) [:data :references])]
      (should= [] (:notes refs))
      (should= 1 (count (:warnings refs)))))

  (it "源码超限参数必须为正整数"
    (should-throw clojure.lang.ExceptionInfo (pack/build @ws "demo.web.model" {:max-source-lines 0}))))
