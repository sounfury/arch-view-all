;; 职责：提供模块、文件与包的依赖、路径、依赖环、分层和说明查询，返回数据而不打印。
;; 核心入口：dependencies、paths、cycles、layers、find-modules、module-summary。

(ns arch-view.query.core
  (:require [arch-view.web.documents :as documents]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private adjacency ^:private edge-type ^:private module-info ^:private walk
         ^:private finish-order ^:private components ^:private collect-component ^:private cycle-data
         ^:private description ^:private matches? ^:private search-paths
         ^:private edge-types ^:private positive-number ^:private dependency-items)

(defn dependencies
  "按最短距离查询上下游；包内成员不列入结果。type 为最短遍历中抵达该项的边类型，同距优先 direct。"
  [ws target {:keys [direction depth] :or {direction :out depth 1}}]
  (when-not (#{:out :in} direction)
    (throw (ex-info "依赖方向只能是 :out 或 :in" {:status 400})))
  (positive-number depth "depth")
  (let [resolved (workspace/resolve-target ws target)
        items (dependency-items ws (:modules resolved) direction depth)]
    {:target resolved :items items :total (count items)}))

(defn paths
  "返回最短优先、不重复经过模块的路径；包可从任一成员出发或到达。max-depth 按边数计算。"
  [ws from to {:keys [max max-depth] :or {max 5}}]
  (let [starts (:modules (workspace/resolve-target ws from))
        goals (set (:modules (workspace/resolve-target ws to)))
        depth (or max-depth (count (get-in ws [:architecture :graph :nodes])))]
    (positive-number max "max")
    (positive-number depth "max-depth")
    (let [adj (adjacency ws :out)
          reachable (into goals (keys (walk (adjacency ws :in) goals depth)))
          adj (into {} (map (fn [[module edges]]
                              [module (filterv #(contains? reachable (:module %)) edges)]) adj))]
      (search-paths adj (filter reachable starts) goals max depth))))

(defn cycles
  "返回所有成环强连通分量及分量内部的全部依赖边，包含自环。"
  [ws]
  (let [items (->> (components ws) (keep #(cycle-data ws %))
                   (sort-by (comp first :modules)) vec)]
    {:items items :total (count items)}))

(defn layers
  "按层号分组；prefix 为模块、源码文件或包目标。"
  [ws {:keys [prefix]}]
  (let [modules (if prefix (:modules (workspace/resolve-target ws prefix))
                    (get-in ws [:architecture :graph :nodes]))
        cyclic (set (mapcat :modules (:items (cycles ws))))
        grouped (group-by #(get-in ws [:architecture :layout :module->layer %]) modules)]
    {:layers (mapv (fn [[index members]]
                     {:index index :modules (vec (sort members))
                      :items (mapv #(module-info ws cyclic %) (sort members))})
                   (sort-by key grouped))
     :total (count modules)}))

(defn find-modules
  "按模块名、相对源码路径及文件头说明搜索；文件缺失或不可读时忽略说明。"
  [ws keyword {:keys [limit] :or {limit 20}}]
  (positive-number limit "limit")
  (when (str/blank? keyword)
    (throw (ex-info "缺少搜索关键字" {:status 400})))
  (let [needle (str/lower-case keyword)
        cyclic (set (mapcat :modules (:items (cycles ws))))
        items (->> (get-in ws [:architecture :graph :nodes]) sort
                   (map #(assoc (module-info ws cyclic %) :description (description ws %)))
                   (filter #(matches? needle %)) vec)]
    {:keyword keyword :items (vec (take limit items)) :total (count items)}))

(defn module-summary
  "返回目标、成员说明、所处层号、包外直接上下游数量以及成员参与的环。"
  [ws target]
  (let [resolved (workspace/resolve-target ws target)
        members (set (:modules resolved))
        all-cycles (:items (cycles ws))
        cyclic (set (mapcat :modules all-cycles))]
    {:target resolved :member-count (count members)
     :members (mapv #(assoc (module-info ws cyclic %) :description (description ws %))
                    (sort members))
     :layers (vec (sort (distinct (keep #(get-in ws [:architecture :layout :module->layer %]) members))))
     :dependencies {:out (:total (dependencies ws target {:direction :out}))
                    :in (:total (dependencies ws target {:direction :in}))}
     :cycles (vec (filter #(some members (:modules %)) all-cycles))}))

;; ===== 私有方法 =====

(defn- positive-number [n option]
  (when-not (and (integer? n) (pos? n))
    (throw (ex-info (str option " 必须是正整数") {:status 400}))))

(defn- edge-types [ws]
  (into {} (map (fn [{:keys [from to type]}] [[from to] type])
                (get-in ws [:architecture :classified-edges]))))

(defn- edge-type [ws classified {:keys [from to]}]
  (or (get classified [from to])
      (if (contains? (get-in ws [:architecture :graph :abstract-modules]) to) :abstract :direct)))

(defn- adjacency [ws direction]
  (let [[start end] (if (= :in direction) [:to :from] [:from :to])
        classified (edge-types ws)]
    (reduce (fn [index edge]
              (update index (start edge) (fnil conj [])
                      {:module (end edge) :type (edge-type ws classified edge)}))
            {} (sort-by (juxt :from :to) (get-in ws [:architecture :graph :edges])))))

(defn- module-info [ws cyclic module]
  {:module module :layer (get-in ws [:architecture :layout :module->layer module])
   :type (if (contains? (get-in ws [:architecture :graph :abstract-modules]) module) :abstract :direct)
   :cycle (contains? cyclic module) :path (workspace/source-file ws module)})

(defn- walk
  "多起点广度遍历；同距离的边先处理 direct，使聚合结果稳定。"
  [adj starts depth]
  (loop [front (vec starts) distance 0 seen (set starts) result {}]
    (if (or (empty? front) (>= distance depth))
      result
      (let [candidates (->> front (mapcat adj)
                            (remove #(contains? seen (:module %)))
                            (sort-by (juxt #(if (= :direct (:type %)) 0 1) :module)))
            found (reduce (fn [acc item] (if (contains? acc (:module item)) acc
                                           (assoc acc (:module item) (assoc item :distance (inc distance)))))
                          {} candidates)]
        (recur (vec (sort (keys found))) (inc distance)
               (into seen (keys found)) (merge result found))))))

(defn- dependency-items [ws starts direction depth]
  (let [cyclic (set (mapcat :modules (:items (cycles ws))))]
    (->> (walk (adjacency ws direction) starts depth) vals
         (map #(merge (module-info ws cyclic (:module %)) %))
         (sort-by (juxt :distance :module)) vec)))

(defn- search-paths [adj starts goals limit depth]
  (loop [queue (into clojure.lang.PersistentQueue/EMPTY (map vector (sort starts))) result []]
    (if (or (empty? queue) (>= (count result) limit))
      result
      (let [path (peek queue) node (peek path) remaining (pop queue)]
        (cond
          (contains? goals node) (recur remaining (conj result path))
          (>= (dec (count path)) depth) (recur remaining result)
          :else (let [visited (set path)
                      next-nodes (->> (get adj node) (map :module) distinct sort (remove visited))]
                  (recur (into remaining (map #(conj path %) next-nodes)) result)))))))

(defn- finish-order [adj nodes]
  (loop [stack (vec (map #(vector % false) (sort nodes))) seen #{} order []]
    (if (empty? stack)
      order
      (let [[node expanded?] (peek stack) remaining (pop stack)]
        (cond
          expanded? (recur remaining seen (conj order node))
          (contains? seen node) (recur remaining seen order)
          :else (recur (into (conj remaining [node true])
                             (map #(vector (:module %) false) (get adj node)))
                       (conj seen node) order))))))

(defn- collect-component [adj start seen]
  (loop [stack [start] visited seen members #{}]
    (if (empty? stack)
      members
      (let [node (peek stack) remaining (pop stack)]
        (if (contains? visited node)
          (recur remaining visited members)
          (recur (into remaining (map :module (get adj node)))
                 (conj visited node) (conj members node)))))))

(defn- components [ws]
  (let [nodes (get-in ws [:architecture :graph :nodes])
        order (reverse (finish-order (adjacency ws :out) nodes))
        incoming (adjacency ws :in)]
    (loop [remaining order seen #{} result []]
      (if-let [node (first remaining)]
        (if (contains? seen node)
          (recur (rest remaining) seen result)
          (let [component (collect-component incoming node seen)]
            (recur (rest remaining) (into seen component) (conj result component))))
        result))))

(defn- cycle-data [ws component]
  (let [classified (edge-types ws)
        edges (->> (get-in ws [:architecture :graph :edges])
                   (filter #(and (contains? component (:from %)) (contains? component (:to %))))
                   (map #(assoc % :type (edge-type ws classified %)))
                   (sort-by (juxt :from :to)) vec)]
    (when (or (> (count component) 1) (seq edges))
      {:modules (vec (sort component)) :edges edges
       :members (mapv #(module-info ws component %) (sort component))})))

(defn- description [{:keys [root] :as ws} module]
  (try
    (when-let [path (workspace/source-file ws module)]
      (let [file (documents/contained-file root (io/file (.toFile ^java.nio.file.Path root) path))]
        (documents/header-description (slurp file :encoding "UTF-8"))))
    (catch Exception _ nil)))

(defn- matches? [needle item]
  (some #(str/includes? (str/lower-case (str (get item %))) needle)
        [:module :path :description]))
