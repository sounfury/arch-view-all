;; 职责：按当前浏览的软件包汇总模块和依赖，让架构图能够逐层展开到文件。
;; 核心入口：生成当前层的架构视图（view-architecture）；按架构缓存的包树索引（architecture-index）。

;; mutation-tested: 2026-03-08
(ns arch-view.domain.architecture-projection
  (:require [clojure.string :as str]
            [arch-view.layout.layers :as layers]))

(def ^:private mixed-leaf-suffix "|file")
(def ^:dynamic *namespace-root-depth* 1)

(defn namespace-segments
  [module]
  (vec (drop *namespace-root-depth* (str/split module #"\."))))

(defn source-filename
  [path]
  (when path
    (let [normalized (-> (str path)
                         (str/replace #"[\\]+" "/")
                         (str/replace #"/+$" ""))]
      (when-not (str/blank? normalized)
        (last (str/split normalized #"/"))))))

(defn- source-filename-base
  [path]
  (some-> (source-filename path)
          (str/replace #"\.[^.]+$" "")))

(defn mixed-leaf-node-id
  [child]
  (str child mixed-leaf-suffix))

(defn mixed-leaf-node?
  [node]
  (and (string? node)
       (str/ends-with? node mixed-leaf-suffix)))

(defn node-child-name
  [node]
  (if (mixed-leaf-node? node)
    (subs node 0 (- (count node) (count mixed-leaf-suffix)))
    node))

(defn prefix?
  [prefix parts]
  (and (<= (count prefix) (count parts))
       (= prefix (subvec parts 0 (count prefix)))))

(defn namespace-layout
  [nodes edges]
  (layers/assign-layers {:nodes nodes :edges edges}))

(defn- scoped-modules
  [segments all-modules namespace-path]
  (->> all-modules
       (filter (fn [m]
                 (let [parts (segments m)]
                   (and (prefix? namespace-path parts)
                        (> (count parts) (count namespace-path))))))
       set))

(defn- module->child
  [segments scoped namespace-path]
  (into {}
        (for [m scoped
              :let [parts (segments m)]]
          [m (nth parts (count namespace-path))])))

(defn- modules-by-child
  [scoped module->child-map]
  (reduce (fn [acc module]
            (update acc (get module->child-map module) (fnil conj #{}) module))
          {}
          scoped))

(defn- child->info
  [segments modules-by-child namespace-path]
  (into {}
        (for [[child modules] modules-by-child
              :let [exact-module (some (fn [m]
                                         (when (= (segments m)
                                                  (conj (vec namespace-path) child))
                                           m))
                                       modules)
                    descendant-modules (if exact-module (disj modules exact-module) modules)]]
          [child {:exact-module exact-module
                  :descendant-modules descendant-modules}])))

(defn- module->node
  [child->info-map]
  (reduce (fn [acc [child {:keys [exact-module descendant-modules]}]]
            (let [leaf-node (if (seq descendant-modules)
                              (mixed-leaf-node-id child)
                              child)
                  with-descendants (reduce (fn [inner module]
                                             (assoc inner module child))
                                           acc
                                           descendant-modules)]
              (if exact-module
                (assoc with-descendants exact-module leaf-node)
                with-descendants)))
          {}
          child->info-map))

(defn- node->modules
  [module->node-map]
  (reduce (fn [acc [module node]]
            (update acc node (fnil conj #{}) module))
          {}
          module->node-map))

(defn- node-kind-map
  [nodes node->modules-map abstract-source]
  (into {}
        (for [n nodes]
          [n (if (some #(contains? abstract-source %)
                       (get node->modules-map n))
               :abstract
               :concrete)])))

(defn- graph-has-cycle?
  [nodes edges]
  (let [nodes (set (or nodes #{}))
        indegree (reduce (fn [acc {:keys [to]}]
                           (update acc to (fnil inc 0)))
                         (zipmap nodes (repeat 0))
                         edges)
        outgoing (reduce (fn [acc {:keys [from to]}]
                           (update acc from (fnil conj #{}) to))
                         {}
                         edges)]
    (loop [queue (into clojure.lang.PersistentQueue/EMPTY
                       (filter #(zero? (get indegree % 0)) nodes))
           indeg indegree
           seen 0]
      (if (empty? queue)
        (< seen (count nodes))
        (let [n (peek queue)
              queue' (pop queue)
              nbrs (get outgoing n #{})
              [indeg' queue'']
              (reduce (fn [[m q] nbr]
                        (let [next (dec (get m nbr 0))
                              m' (assoc m nbr next)]
                          (if (zero? next)
                            [m' (conj q nbr)]
                            [m' q])))
                      [indeg queue']
                      nbrs)]
          (recur queue'' indeg' (inc seen)))))))

;; ===== 包树索引 =====
;; 每次浏览都重扫全部模块和依赖会随文件数平方增长；索引按架构只建一次，各层直接查表。

(defn- edge-level
  "两端模块只在最长公共包前缀那一层形成同层依赖；返回 [包路径 起点子包 终点子包]。"
  [a b]
  (let [limit (min (count a) (count b))
        k (loop [k 0]
            (if (and (< k limit) (= (nth a k) (nth b k))) (recur (inc k)) k))]
    (when (< k limit)
      [(subvec a 0 k) (nth a k) (nth b k)])))

(defn- children-index
  [segments]
  (reduce (fn [acc parts]
            (reduce (fn [inner k]
                      (update inner (subvec parts 0 k) (fnil conj #{}) (nth parts k)))
                    acc
                    (range (count parts))))
          {}
          (vals segments)))

(defn- level-edges-index
  [segments classified]
  (reduce (fn [acc {:keys [from to]}]
            (let [a (get segments from)
                  b (get segments to)]
              (if-let [[path f t] (when (and a b) (edge-level a b))]
                (update acc path (fnil conj #{}) {:from f :to t})
                acc)))
          {}
          classified))

(defn tree-index
  "建立包树索引：模块分段、每个包的直接子包，以及每个包内子包之间的依赖。"
  [architecture]
  (binding [*namespace-root-depth* (get-in architecture [:guidance :namespace-root-depth] 1)]
    (let [modules (or (get-in architecture [:graph :nodes]) #{})
          segments (into {} (map (juxt identity namespace-segments)) modules)]
      {:segments segments
       :children (children-index segments)
       :level-edges (level-edges-index segments (:classified-edges architecture))
       :memo (atom {})})))

(defn- memoized
  [index key compute]
  (let [memo (:memo index)]
    (if-let [[_ value] (find @memo key)]
      value
      (let [value (compute)]
        (swap! memo assoc key value)
        value))))

(defn- deeper?
  [index path]
  (contains? (:children index) path))

(defn- subtree-cycle?
  [index path]
  (memoized index [:cycle? path]
            #(let [children (get-in index [:children path] #{})]
               (boolean
                (or (graph-has-cycle? children (get-in index [:level-edges path] #{}))
                    (some (fn [child]
                            (let [child-path (conj path child)]
                              (and (deeper? index child-path) (subtree-cycle? index child-path))))
                          children))))))

(defn- node-leaf-map
  [segments nodes node->modules-map namespace-path]
  (into {}
        (for [n nodes
              :let [modules (get node->modules-map n)
                    leaf? (every? (fn [m]
                                    (= (count (segments m))
                                       (inc (count namespace-path))))
                                  modules)]]
          [n leaf?])))

(defn- node-source-file-map
  [segments nodes node->modules-map module->source-file-all namespace-path]
  (into {}
        (for [n nodes
              :let [modules (get node->modules-map n)
                    exact-module (some (fn [m]
                                         (when (= (count (segments m))
                                                  (inc (count namespace-path)))
                                           m))
                                       modules)
                    source-file (get module->source-file-all exact-module)]]
          [n source-file])))

(defn- node-display-label-map
  [nodes node-leaf-map node-source-file-map]
  (into {}
        (for [n nodes
              :let [leaf? (true? (get node-leaf-map n))
                    source-file (get node-source-file-map n)]]
          [n (if leaf?
               (or (source-filename-base source-file)
                   (node-child-name n))
               (node-child-name n))])))

(defn- node-full-name-map
  [nodes node-leaf-map node-source-file-map namespace-path]
  (into {}
        (for [n nodes]
          [n (if-let [source-file (and (true? (get node-leaf-map n))
                                       (get node-source-file-map n))]
               (str/join "." (concat namespace-path [(source-filename source-file)]))
               (str/join "." (concat namespace-path [(node-child-name n)])))])))

(defn- format-cycle-line
  [namespace-path cycle]
  (->> cycle
       (map (fn [node]
              (str/join "." (concat namespace-path [(node-child-name node)]))))
       (str/join "->")))

(defn- subtree-cycle-lines
  [index namespace-path]
  (memoized index [:cycle-lines namespace-path]
            #(let [children (get-in index [:children namespace-path] #{})
                   level-edges (get-in index [:level-edges namespace-path] #{})
                   level-cycles (get (layers/assign-layers {:nodes children :edges level-edges}) :cycles [])
                   descendant-cycles (mapcat (fn [child]
                                               (let [child-path (conj namespace-path child)]
                                                 (when (deeper? index child-path)
                                                   (subtree-cycle-lines index child-path))))
                                             (sort children))]
               (->> (concat (map (fn [cycle] (format-cycle-line namespace-path cycle)) level-cycles)
                            descendant-cycles)
                    distinct
                    vec))))

(defn- aggregate-edge
  [old type]
  (if old
    {:type (if (or (= :abstract (:type old))
                   (= type :abstract))
             :abstract
             :direct)
     :count (inc (long (:count old)))}
    {:type type :count 1}))

(defn- edges-by-pair
  [classified module->node-map]
  (reduce (fn [acc {:keys [from to type]}]
            (let [f (get module->node-map from)
                  t (get module->node-map to)]
              (if (and f t (not= (node-child-name f) (node-child-name t)))
                (update acc [f t] #(aggregate-edge % type))
                acc)))
          {}
          classified))

(defn- display-edges-by-pair
  [classified scoped module->node-map]
  (reduce (fn [acc {:keys [from to type]}]
            (let [from-in? (contains? scoped from)
                  to-in? (contains? scoped to)]
              (if (or from-in? to-in?)
                (let [f (if from-in? (get module->node-map from) from)
                      t (if to-in? (get module->node-map to) to)]
                  (if (or (nil? f) (nil? t) (= f t))
                    acc
                    (update acc [f t] #(aggregate-edge % type))))
                acc)))
          {}
          classified))

(defn- project-architecture
  [architecture index namespace-path]
  (let [namespace-path (vec namespace-path)
        segments (:segments index)
        all-modules (or (get-in architecture [:graph :nodes]) #{})
        module->source-file-all (or (get-in architecture [:graph :module->source-file]) {})
        scoped (scoped-modules segments all-modules namespace-path)
        module->child-map (module->child segments scoped namespace-path)
        by-child (modules-by-child scoped module->child-map)
        child->info-map (child->info segments by-child namespace-path)
        module->node-map (module->node child->info-map)
        node->modules-map (node->modules module->node-map)
        nodes (set (keys node->modules-map))
        abstract-source (or (get-in architecture [:graph :abstract-modules]) #{})
        module->kind (node-kind-map nodes node->modules-map abstract-source)
        module->cycle? (into {}
                             (for [n nodes]
                               [n (subtree-cycle? index (conj namespace-path (node-child-name n)))]))
        module->leaf? (node-leaf-map segments nodes node->modules-map namespace-path)
        module->source-file (node-source-file-map segments nodes node->modules-map module->source-file-all namespace-path)
        module->display-label (node-display-label-map nodes module->leaf? module->source-file)
        module->full-name (node-full-name-map nodes module->leaf? module->source-file namespace-path)
        pairs (edges-by-pair (:classified-edges architecture) module->node-map)
        display-pairs (display-edges-by-pair (:classified-edges architecture) scoped module->node-map)
        classified-edges (set (for [[[f t] {:keys [type count]}] pairs]
                                {:from f :to t :type type :count count}))
        display-edges (set (for [[[f t] {:keys [type count]}] display-pairs]
                             {:from f :to t :type type :count count}))
        cycle-lines (subtree-cycle-lines index namespace-path)
        graph {:nodes nodes
               :edges (set (for [{:keys [from to]} classified-edges] {:from from :to to}))
               :abstract-modules (set (for [[n k] module->kind :when (= k :abstract)] n))}
        layout (namespace-layout nodes classified-edges)
        layer->label (into {}
                           (for [[module idx] (:module->layer layout)]
                             [idx (or (get module->display-label module) module)]))]
    {:namespace-path namespace-path
     :graph graph
     :layout layout
     :layer->label layer->label
     :classified-edges classified-edges
     :display-edges display-edges
     :module->kind module->kind
     :module->cycle? module->cycle?
     :module->leaf? module->leaf?
     :module->source-file module->source-file
     :module->display-label module->display-label
     :module->full-name module->full-name
     :cycle-lines cycle-lines}))

;; 架构数据不可变；网页和桌面端会对同一份架构反复下钻，只缓存最近一份的索引。
(def ^:private index-cache (atom nil))

(defn architecture-index
  "返回该架构的包树索引；同一架构对象复用上次结果。"
  [architecture]
  (let [[cached index] @index-cache]
    (if (identical? cached architecture)
      index
      (let [index (tree-index architecture)]
        (reset! index-cache [architecture index])
        index))))

(defn view-architecture
  [architecture namespace-path]
  (binding [*namespace-root-depth* (get-in architecture [:guidance :namespace-root-depth] 1)]
    (project-architecture architecture (architecture-index architecture) namespace-path)))
