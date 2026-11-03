;; 职责：验证查询 CLI 的参数错误、中文帮助、Markdown 截断及 JSON 数据输出。
;; 核心入口：Speclj 查询命令用例。

(ns arch-view.query.cli-spec
  (:require [arch-view.cli-support :as support]
            [arch-view.query.cli :as cli]
            [arch-view.query.core :as query]
            [arch-view.query.fixtures :as fixtures]
            [arch-view.query.impact :as impact]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(describe "查询命令"
  (with-all root (fixtures/temporary-root))
  (with-all ws (fixtures/workspace @root))
  (after-all (fixtures/delete-tree! @root))

  (it "顶层和各子命令均有中文帮助且不加载工作区"
    (with-redefs [support/load-workspace (fn [_] (throw (Exception. "不应加载")))]
      (doseq [args [[] ["--help"] ["deps" "--help"] ["rdeps" "--help"]
                    ["path" "--help"] ["cycles" "--help"] ["layers" "--help"]
                    ["find" "--help"] ["module" "--help"] ["impact" "--help"]]]
        (let [text (with-out-str (cli/run! args))]
          (should-contain "用法：arch-view" text)
          (should-contain "--format" text)))
      (should-contain "git merge-base" (with-out-str (cli/run! ["impact" "--help"])))))

  (it "缺目标、缺参数值、非法数字、多余参数和未知命令都明确失败"
    (should-throw clojure.lang.ExceptionInfo #"缺少参数" (cli/run! ["deps"]))
    (should-throw clojure.lang.ExceptionInfo #"缺少参数" (cli/run! ["path" "client"]))
    (should-throw clojure.lang.ExceptionInfo #"缺少参数值" (cli/run! ["deps" "client" "--depth"]))
    (should-throw clojure.lang.ExceptionInfo #"正整数" (cli/run! ["deps" "client" "--depth" "0"]))
    (should-throw clojure.lang.ExceptionInfo #"正整数" (cli/run! ["path" "client" "lib" "--max" "bad"]))
    (should-throw clojure.lang.ExceptionInfo #"多余" (cli/run! ["cycles" "client"]))
    (should-throw clojure.lang.ExceptionInfo #"未知查询子命令" (cli/run! ["bad"]))
    (should-throw clojure.lang.ExceptionInfo #"不支持参数" (cli/run! ["cycles" "--depth" "1"]))
    (should-throw clojure.lang.ExceptionInfo #"不能同时使用" (cli/run! ["impact" "--since" "HEAD" "--staged"])))

  (it "每类查询都能输出合法 JSON，标准输出没有分析进度"
    (with-redefs [support/load-workspace (fn [_] @ws)]
      (doseq [args [["deps" "app.web.a"] ["rdeps" "lib.port" "--depth" "2"]
                    ["path" "client" "lib.impl"] ["cycles"] ["layers"]
                    ["find" "complexity"] ["module" "app.web"]]]
        (let [text (with-out-str (cli/run! (into args ["--format" "json"])))
              data (support/read-json-text text)]
          (should (map? data))
          (should-not-contain "Architecture loaded" text)))))

  (it "Markdown 包含源码路径、类型、层和距离"
    (with-redefs [support/load-workspace (fn [_] @ws)]
      (let [text (with-out-str (cli/run! ["deps" "app.web.a"]))]
        (doseq [piece ["lib.port" "abstract" "src/port.clj" "距离" "层" "共 2 项"]]
          (should-contain piece text)))))

  (it "Markdown 表格截断后注明总数，JSON 保留全部数据"
    (let [items (vec (repeat 60 {:module "模块" :path "src/a.clj" :type :direct}))
          data {:target {:target "目标"} :items items :total 60}]
      (with-redefs [support/load-workspace (fn [_] @ws) query/dependencies (fn [& _] data)]
        (let [text (with-out-str (cli/run! ["deps" "client"]))]
          (should-contain "显示 50 项" text)
          (should-contain "总数 60" text))
        (should= 60 (count (:items (support/read-json-text
                                   (with-out-str (cli/run! ["deps" "client" "--format" "json"])))))))))

  (it "find 的 limit 截断保留总命中数"
    (with-redefs [support/load-workspace (fn [_] @ws)]
      (let [text (with-out-str (cli/run! ["find" "app.web" "--limit" "1"]))]
        (should-contain "共 3 项，显示 1 项" text)
        (should-contain "总数 3" text))))

  (it "impact 通过共享工具加载并输出结构化结果"
    (with-redefs [support/load-workspace (fn [_] @ws)
                  impact/impact (fn [_ opts] {:staged (:staged opts) :files [] :modules [] :unmapped []
                                             :upstream [] :upstream-count 0 :cycles []})]
      (let [data (support/read-json-text (with-out-str (cli/run! ["impact" "--staged" "--format" "json"])))]
        (should= true (:staged data)))
      (should-contain "仅暂存区" (with-out-str (cli/run! ["impact" "--staged"]))))))
