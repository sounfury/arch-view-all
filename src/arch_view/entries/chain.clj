;; 职责：把 Java 调用解析结果按类层次展开，区分实线与不确定边，并补消息分发、事件发布、反射及业务出口。
;; 核心入口：构建方法图（build）；按入口提取有界子图（slice）。

(ns arch-view.entries.chain
  (:refer-clojure :exclude [remove])
  (:require [arch-view.entries.exits :as exits]
            [arch-view.entries.java-calls :as calls]
            [arch-view.entries.java-source :as java]
            [clojure.string :as str]))

(declare ^:private resolved-targets ^:private event-targets ^:private call-result ^:private root-of
         ^:private dispatch-root ^:private traverse ^:private result-data ^:private method-index ^:private fallback-targets)

(defn build
  "按项目源码类型计算方法调用与出口。入口扫描与 JavacTask 共享规则/源码快照，方法图只在首次请求链路时建立。"
  [ctx analyzed]
  (let [analyzed (assoc analyzed :method-index (method-index (:nodes analyzed)))
        fallback (memoize (fn [receiver method arity] (fallback-targets ctx analyzed receiver method arity)))
        ctx (assoc ctx :fallback-targets fallback)
        exit-of (exits/matcher ctx analyzed)
        results (mapv #(call-result ctx analyzed exit-of %) (:calls analyzed))
        nodes (merge (:nodes analyzed) (into {} (keep :terminal results)))
        edges (vec (distinct (mapcat :edges results)))]
    {:nodes nodes :method-index (:method-index analyzed)
     :edges edges :adj (group-by :from edges) :fingerprints (:fingerprints ctx)
     :unresolved (group-by :from (keep :unresolved results))}))

(defn handler-root
  "入口处理方法在方法图中的标识；业务分组从 handler 计算，不从 MsgBroker 公共分发者计算。"
  [graph entry]
  (root-of graph entry))

(defn slice
  "返回入口对应的方法泳道、节点、调用边和截断信息。默认深度 6、节点上限 60；最多 30 层、500 节点。"
  [graph entry {:keys [depth limit] :or {depth 6 limit 60}}]
  (when-not (and (integer? depth) (<= 1 depth 30) (integer? limit) (<= 1 limit 500))
    (throw (ex-info "depth 必须为 1–30，limit 必须为 1–500。" {:status 400})))
  (when-let [declaration (:declaration entry)]
    (throw (ex-info "已声明发布入口，但方法继承自外部依赖，当前源码无法定位；请复核外部接口或模板实现。"
                    {:status 422 :reason (:reason declaration) :declaration declaration})))
  (let [handler (root-of graph entry)
        _ (when-not handler (throw (ex-info "未找到入口对应的方法，请重新加载入口或重新分析项目。" {:status 404})))
        {:keys [graph root]} (dispatch-root graph entry handler)
        all (traverse graph root Integer/MAX_VALUE Integer/MAX_VALUE)
        visible (traverse graph root depth limit)]
    (with-meta (result-data graph entry root visible all depth limit) {:fingerprints (:fingerprints graph)})))

;; ===== 私有方法 =====

(def ^:private async-annotations #{"org.springframework.scheduling.annotation.Async"})

(defn- short-type [type]
  (last (str/split (str type) #"\.")))

(defn- inherited? [ctx child parent]
  (or (= child parent) (contains? ((:supers ctx) child) parent)))

(defn- method-index [nodes]
  (update-vals (group-by (juxt :type :method #(count (:params %))) (vals nodes))
               #(mapv :id (sort-by :id %))))

(defn- fallback-targets [ctx graph receiver method arity]
  (some #(not-empty (get-in graph [:method-index [% method arity]]))
        (cons receiver (sort ((:supers ctx) receiver)))))

(defn- resolved-targets [ctx graph {:keys [target receiver exact] :as call}]
  (let [node (get-in graph [:nodes target])
        resolved (if (and node (:virtual node) (not exact))
                   (filter (fn [id]
                             ;; 模板形参的 CHA 不能把全仓匿名实现混进每个入口；源码回调参数已有显式不确定边。
                             (and (or (not (get-in ctx [:index :types (get-in graph [:nodes id :type]) :local?]))
                                      (get-in ctx [:index :types receiver :local?]))
                                  (some #(inherited? ctx % receiver)
                                        (or (seq (get-in graph [:implementationOwners [target id]]))
                                            [(get-in graph [:nodes id :type])]))))
                           (get-in graph [:implementations target]))
                   (when node [target]))
        chosen (or (seq resolved) (when node [target])
                   ((:fallback-targets ctx) receiver (:method call) (count (:args call))))]
    (for [id chosen :let [candidate (get-in graph [:nodes id])
                          reasons (clojure.core/remove nil?
                                                       [(:reason call)
                                                        (when-not node "按声明类型匹配，调用符号未完整解析")
                                                        (when (> (count chosen) 1) (str (if node "多实现" "未解析重载") "（" (count chosen) "）"))
                                                        (when (:abstract candidate) "未找到具体实现")
                                                        (when (some async-annotations (:annotations candidate)) "@Async 异步调用")])]]
      {:from (:from call) :to id :anchor (:anchor call) :call (:written call)
       :style (if (seq reasons) "dashed" "solid") :reason (str/join "；" reasons)})))

(defn- listener-types [ctx node]
  (let [type (get-in ctx [:index :types (:type node)])
        method (java/method-for type (:method node) (:params node))
        classes (for [ann (:annotations method)
                      :let [fqn (:fqn (java/annotation-type (:index ctx) type ann))]
                      :when (#{"org.springframework.context.event.EventListener" "org.springframework.transaction.event.TransactionalEventListener"} fqn)
                      value (vals (java/annotation-attrs (:index ctx) type ann))
                      value (if (sequential? value) value [value]) :when (:class value)] (:class value))]
    (or (seq classes) (take 1 (:params node)))))

(defn- event-targets [ctx graph call]
  (when (and (= "publishEvent" (:method call))
             (or (inherited? ctx (:receiver call) "org.springframework.context.ApplicationEventPublisher")
                 (inherited? ctx (:receiver call) "org.springframework.context.ApplicationContext")))
    (let [event (first (:argumentTypes call))]
      (vec (for [node (vals (:nodes graph))
                  :when (and (not (:abstract node))
                             (some #{"org.springframework.context.event.EventListener" "org.springframework.transaction.event.TransactionalEventListener"}
                                   (:annotations node))
                             (some #(inherited? ctx event %) (listener-types ctx node)))]
             {:from (:from call) :to (:id node) :style "dashed" :reason (str "Spring 事件发布 " event)
              :anchor (:anchor call) :call (:written call)})))))

(defn- reflection? [{:keys [receiver method]}]
  (or (and (= "java.lang.reflect.Method" receiver) (= "invoke" method))
      (and (= "java.lang.reflect.Constructor" receiver) (= "newInstance" method))
      (and (= "java.lang.Class" receiver) (#{"forName" "newInstance"} method))))

(defn- terminal-result [call node reason]
  {:terminal [(:id node) node]
   :edges [{:from (:from call) :to (:id node) :style (if reason "dashed" "solid")
            :reason (or reason "") :anchor (:anchor call) :call (:written call)}]})

(defn- call-result [ctx graph exit-of call]
  (let [exit (exit-of call)
        event (event-targets ctx graph call)]
    (cond
      exit (terminal-result call exit (:reason call))
      (seq event) {:edges event}
      (reflection? call)
      (let [id (str "unresolved:" (:from call) ":" (get-in call [:anchor :startLine]) ":" (:written call))]
        (terminal-result call {:id id :type (:receiver call) :method (:method call) :kind "unresolved"
                               :label "反射调用：目标未解析" :anchor (:anchor call)} "反射，无法确定运行时目标"))
      :else (let [edges (vec (resolved-targets ctx graph call))]
              (if (seq edges) {:edges edges}
                {:unresolved (select-keys call [:from :receiver :method :anchor :written])})))))

(defn- simple-param [param]
  (last (str/split (str/replace param "..." "[]") #"\.")))

(defn- root-of [graph entry]
  (let [{:keys [type method params signature]} (:handler entry)
        candidates (if-let [index (:method-index graph)]
                     (map #(get-in graph [:nodes %]) (get index [type method (count params)]))
                     (filter #(and (= type (:type %)) (= method (:method %)) (= (count params) (count (:params %))))
                             (vals (:nodes graph))))
        exact (filter #(if (vector? signature) (= signature (:params %))
                           (= (mapv simple-param params) (mapv simple-param (:params %)))) candidates)
        located (filter #(= (select-keys (:anchor entry) [:path :startLine])
                            (select-keys (:anchor %) [:path :startLine])) candidates)]
    (cond (= 1 (count located)) (:id (first located))
          (= 1 (count exact)) (:id (first exact))
          (= 1 (count candidates)) (:id (first candidates)))))

(defn- dispatch-root [graph entry handler]
  (let [dispatcher (some #(when (= "dispatcher" (:kind %)) %) (:sources entry))
        key (some #(when (= "dispatch-key" (:kind %)) (:key %)) (:sources entry))
        node (when dispatcher
               (first (sort-by :id (filter #(and (= (:type dispatcher) (:type %))
                                                                 (= (:method dispatcher) (:method %))) (vals (:nodes graph))))))]
    (if (and node (not= (:id node) handler))
      (let [edge {:from (:id node) :to handler :style "dashed" :reason (str "按 key 分发：" key)
                  :key key :anchor (get-in dispatcher [:anchor])}]
        {:graph (-> graph (update :edges conj edge) (update-in [:adj (:id node)] (fnil conj []) edge)) :root (:id node)})
      {:graph graph :root handler})))

(defn- traverse
  "BFS：入队即标记，保留最短深度；先按调用行、目标 id 排序，同源码重复分析保持顺序。"
  [graph root max-depth max-nodes]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [root 0]) seen #{root} ordered []]
    (if (or (empty? queue) (>= (count ordered) max-nodes)) ordered
      (let [[id depth] (peek queue)
            nexts (when (< depth max-depth)
                    (->> (get-in graph [:adj id])
                         (sort-by (juxt #(get-in % [:anchor :startLine] 0) :to))
                         (map :to) distinct (clojure.core/remove seen)))
            nexts (take (max 0 (- max-nodes (count seen))) nexts)]
        (recur (into (pop queue) (map #(vector % (inc depth)) nexts))
               (into seen nexts) (conj ordered {:id id :depth depth}))))))

(defn- entry-node
  "仅修饰当前切片中的处理节点，不改写缓存的共享方法图或其它入口。"
  [node entry]
  (let [own? (= "implementation" (:docStatus node))
        entry-summary (:summary entry)]
    (merge node
           (select-keys entry [:businessName :description :contractStatus :contractCandidates])
           {:contractAnchor (when-not (= "ambiguous" (:contractStatus entry))
                              (or (:contractAnchor entry) (:contractAnchor node)))
            :contractSource (when-not (= "ambiguous" (:contractStatus entry))
                              (or (:contractSource entry) (:contractSource node)))
            :docCandidates (if (or (:contractAnchor entry) (:contractCandidates entry)) (:docCandidates entry) (:docCandidates node))
            :summary (if own? (:summary node) (or entry-summary (:summary node)))
            :methodSummary (if own? (:summary node) (or (:methodSummary entry) (:summary node)))
            :entrySummary entry-summary :operation (get-in entry [:attrs "operation"])
            :docSource (cond own? (:docSource node) entry-summary (:docSource entry) :else (:docSource node))
            :methodDocSource (if own? (:docSource node) (or (:methodDocSource entry) (:docSource node)))
            :docStatus (if own? "implementation"
                           (if (or (:contractAnchor entry) (:contractCandidates entry) entry-summary) (:docStatus entry) (:docStatus node)))
            :attrs (merge (:attrs node) (:attrs entry))
            :sources (vec (distinct (concat (:sources node) (:sources entry))))})))

(defn- result-data [graph entry root visible all depth limit]
  (let [handler (root-of graph entry)
        ids (set (map :id visible))
        edges (vec (filter #(and (ids (:from %)) (ids (:to %))) (:edges graph)))
        nodes (mapv (fn [index {:keys [id depth]}]
                      (let [node (get-in graph [:nodes id])
                            node (if (= id handler) (entry-node node entry) node)]
                        (assoc node :depth depth :order index
                               :label (or (:label node) (str (short-type (:type node)) "#" (:method node))))))
                    (range) visible)
        lanes (vec (for [[type items] (sort-by #(apply min (map :order (val %))) (group-by :type nodes))]
                     {:id type :label (short-type type) :nodes (mapv :id items)}))
        unknown (vec (mapcat #(get-in graph [:unresolved %]) ids))]
    {:entry (select-keys entry [:id :label :kind :module :channel :handler :anchor :summary :sources :attrs
                               :businessName :description :contractAnchor :contractSource :contractStatus :contractCandidates
                               :implementationAnchor :docSource])
     :root root :nodes nodes :edges edges :lanes lanes
     :modules (vec (sort (distinct (keep :module nodes))))
     :depth depth :limit limit :remaining (- (count all) (count visible))
     :frontier (vec (distinct (keep #(when (and (ids (:from %)) (not (ids (:to %)))) (:from %)) (:edges graph))))
     :summary {:nodes (count nodes) :edges (count edges) :exits (count (filter #(= "exit" (:kind %)) nodes))
               :uncertain (count (filter #(= "dashed" (:style %)) edges)) :unresolvedCalls (count unknown)}
     :unresolved (vec (take 30 unknown))}))
