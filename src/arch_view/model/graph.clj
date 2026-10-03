;; 工具函数：把模块和依赖整理为统一的关系图数据，并去除重复项。

;; mutation-tested: 2026-03-08
(ns arch-view.model.graph)

(defn make-graph
  [nodes edges]
  {:nodes (set nodes)
   :edges (set edges)})
