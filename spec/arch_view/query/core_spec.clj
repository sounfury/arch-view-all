;; 职责：验证查询核心的包边界、最短距离、路径、强连通分量、分层与说明检索。
;; 核心入口：Speclj 查询核心用例。

(ns arch-view.query.core-spec
  (:require [arch-view.query.core :as query]
            [arch-view.query.fixtures :as fixtures]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "精确架构查询"
  (with-all root (fixtures/temporary-root))
  (with-all ws (fixtures/workspace @root))
  (after-all (fixtures/delete-tree! @root))

  (it "依赖带层级、实际边类型、环与相对源码路径"
    (should= [{:module "app.web.b" :distance 1 :layer 3 :type :direct :cycle false :path "src/b.clj"}
              {:module "lib.port" :distance 1 :layer 4 :type :abstract :cycle true :path "src/port.clj"}]
             (:items (query/dependencies @ws "app.web.a" {}))))

  (it "文件目标经统一解析，反向查询保留实际抽象边类型"
    (let [data (query/dependencies @ws "src/port.clj" {:direction :in})]
      (should= :file (get-in data [:target :kind]))
      (should= 3 (:total data))
      (should (every? #(= :abstract (:type %)) (:items data)))))

  (it "包只列外部依赖并去重，不误匹配相邻名称前缀"
    (let [data (query/dependencies @ws "app.web" {:depth 3})]
      (should= ["app.web.a" "app.web.b"] (get-in data [:target :modules]))
      (should= [["lib.port" 1] ["lib.impl" 2]] (mapv (juxt :module :distance) (:items data)))))

  (it "上游闭包记录最短距离且在环中终止"
    (should= [["app.web.a" 1] ["app.web.b" 1] ["lib.impl" 1] ["client" 2] ["app.webby" 3]]
             (mapv (juxt :module :distance)
                   (:items (query/dependencies @ws "lib.port" {:direction :in :depth 20})))))

  (it "包的反向依赖同样排除所有成员"
    (should= ["client" "app.webby"]
             (mapv :module (:items (query/dependencies @ws "app.web" {:direction :in :depth 3})))))

  (it "同距离抵达同一模块的不同边类型优先 direct"
    (let [ws (assoc-in @ws [:architecture :classified-edges]
                       [{:from "app.web.a" :to "lib.port" :type :direct}
                        {:from "app.web.b" :to "lib.port" :type :abstract}])]
      (should= :direct (:type (first (:items (query/dependencies ws "app.web" {})))))))

  (it "缺少分类时使用抽象模块集合"
    (should= :abstract (:type (second (:items (query/dependencies
                                             (update @ws :architecture dissoc :classified-edges) "app.web.a" {}))))))

  (it "依赖选项无效或目标未知时清楚失败"
    (should-throw clojure.lang.ExceptionInfo "depth 必须是正整数" (query/dependencies @ws "client" {:depth 0}))
    (should-throw clojure.lang.ExceptionInfo (query/dependencies @ws "client" {:direction :wrong}))
    (should-throw clojure.lang.ExceptionInfo (query/dependencies @ws "missing" {})))

  (it "路径最短优先，包含较长备选且不会重复经过环"
    (should= [["app.web.a" "lib.port" "lib.impl"]
              ["app.web.a" "app.web.b" "lib.port" "lib.impl"]]
             (query/paths @ws "app.web.a" "lib.impl" {})))

  (it "路径支持包端点、最大数量、边数深度与不可达目标"
    (should= [["app.web.a" "lib.port"]] (query/paths @ws "app.web" "lib" {:max 1}))
    (should= [] (query/paths @ws "client" "lib.impl" {:max-depth 2}))
    (should= [] (query/paths @ws "orphan" "lib" {}))
    (should= [["client"]] (query/paths @ws "client" "client" {}))
    (should-throw clojure.lang.ExceptionInfo (query/paths @ws "client" "lib" {:max -1})))

  (it "环按强连通分量输出，包含自环和内部所有边，不依赖布局环路径"
    (let [data (query/cycles (assoc-in @ws [:architecture :layout :cycles] []))]
      (should= 2 (:total data))
      (should= [["lib.impl" "lib.port"] ["self"]] (mapv :modules (:items data)))
      (should= 2 (count (:edges (first (:items data)))))
      (should= :abstract (:type (first (:edges (first (:items data))))))))

  (it "分层按层号排序，并通过统一目标解析限制包"
    (let [data (query/layers @ws {:prefix "app.web"})]
      (should= 2 (:total data))
      (should= [2 3] (mapv :index (:layers data)))
      (should= [["app.web.a"] ["app.web.b"]] (mapv :modules (:layers data))))
    (should-throw clojure.lang.ExceptionInfo (query/layers @ws {:prefix "missing"})))

  (it "搜索不区分大小写，可匹配文件头与相对路径，限制结果仍保留总数"
    (should= ["orphan"] (mapv :module (:items (query/find-modules @ws "COMPLEXITY" {}))))
    (should= ["orphan"] (mapv :module (:items (query/find-modules @ws "src/orphan" {}))))
    (let [data (query/find-modules @ws "app.web" {:limit 1})]
      (should= 3 (:total data))
      (should= 1 (count (:items data))))
    (should-throw clojure.lang.ExceptionInfo (query/find-modules @ws "" {})))

  (it "源码丢失时检索仍保留模块及路径"
    (let [ws (assoc-in @ws [:architecture :graph :module->source-file "orphan"]
                       (str (.resolve @root "missing.clj")))
          item (first (:items (query/find-modules ws "orphan" {})))]
      (should= "orphan" (:module item))
      (should-be-nil (:description item))))

  (it "包概况包含成员说明和包外上下游数"
    (let [data (query/module-summary @ws "app.web")]
      (should= :package (get-in data [:target :kind]))
      (should= 2 (:member-count data))
      (should= [2 3] (:layers data))
      (should= {:out 1 :in 1} (:dependencies data))
      (should= [] (:cycles data))
      (should (every? :description (:members data))))))
