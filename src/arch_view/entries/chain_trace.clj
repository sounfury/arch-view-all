;; 职责：把当前入口子图保存为带锚点的 trace；保留分叉与虚线原因，复用 trace prepare/verify/store，不虚构步骤间的线性跳转。
;; 核心入口：保存（save!）。只在用户点击保存后写入 .arch-view/traces。

(ns arch-view.entries.chain-trace
  (:require [arch-view.entries.source-snapshot :as snapshot]
            [arch-view.trace.store :as store]
            [arch-view.trace.verify :as verify]))

(defn save!
  "chain 由服务端按 entryId/depth/limit 重算；不会接受浏览器传来的任意节点或边。"
  [ws chain {:keys [id title]}]
  (let [indexes (into {} (map-indexed (fn [index node] [(:id node) index]) (:nodes chain)))
        draft {:id id :title (if (seq title) title (str "入口链路 · " (get-in chain [:entry :label])))
               :author "static" :question (str (get-in chain [:entry :label]) " 的处理链路")
               :summary (str "按源码静态调用展开，排列不代表执行顺序；共 " (count (:nodes chain)) " 个方法/出口。"
                             (when (pos? (:remaining chain)) (str "仅保存当前显示范围，另有 " (:remaining chain) " 个节点未展开。")))
               :origin {:kind "entry" :entryId (get-in chain [:entry :id]) :depth (:depth chain) :limit (:limit chain)}
               :steps (mapv (fn [node]
                              {:title (:label node) :anchor (:anchor node) :methodId (:id node)
                               :explanation (or (:summary node) (when (= "exit" (:kind node)) (str "出口：" (:label node))) "")})
                            (:nodes chain))
               :edges (mapv (fn [edge]
                              (assoc (select-keys edge [:style :reason]) :from (indexes (:from edge)) :to (indexes (:to edge))))
                            (:edges chain))}
        prepared (verify/prepare ws draft)
        checked (verify/verify ws prepared)]
    (snapshot/assert-current! ws (:fingerprints (meta chain)) (keys (:fingerprints (meta chain))))
    (store/write-trace! ws prepared)
    checked))
