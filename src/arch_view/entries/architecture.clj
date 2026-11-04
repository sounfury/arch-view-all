;; 职责：从项目架构文档读出子系统绑定与数据流连线，判定接入层和业务子系统，并给出模块所属子系统。
;; 核心入口：架构模型（model）；模块归属（owners）。

(ns arch-view.entries.architecture
  (:require [arch-view.context.architecture :as doc]
            [arch-view.web.documents :as documents]
            [clojure.string :as str]))

(declare ^:private documents-content ^:private flow-graph ^:private bound-subsystems ^:private auto-entry-layers
         ^:private downstream ^:private module-path ^:private contains-path?)

(defn model
  "handler-modules 为全部入口处理类的图节点。返回
  {:document :subsystems {id {:id :label :scopes}} :order [id…] :edges [{:from :to}] :known #{…}
   :entryLayers {:ids :basis} :domainSubsystems {:ids :basis} :unknown [{:field :id}] :module-path fn}。"
  [ws handler-modules config]
  (let [{:keys [path contents]} (documents-content ws)
        roles (mapcat doc/subsystem-roles contents)
        {:keys [nodes edges]} (flow-graph (mapcat doc/flow-sources contents))
        path-of (module-path ws)
        paths (map path-of (keys (get-in ws [:architecture :graph :module->source-file])))
        subsystems (bound-subsystems roles nodes paths)
        known (into (set (map :id roles)) (keys nodes))
        base {:document path :subsystems subsystems :edges edges :known known :module-path path-of
              :order (vec (distinct (concat (map :id roles) (keys nodes))))}
        layers (if (contains? config :entryLayers)
                 {:ids (vec (:entryLayers config)) :basis "rule"}
                 {:ids (auto-entry-layers base (set (map path-of handler-modules))) :basis "auto"})
        business (cond
                   (contains? config :domainSubsystems) {:ids (vec (:domainSubsystems config)) :basis "rule"}
                   (seq (:ids layers)) {:ids (downstream base (set (:ids layers))) :basis "downstream"}
                   :else {:ids (vec (filter subsystems (:order base))) :basis "no-entry-layer"})]
    (assoc base :entryLayers layers :domainSubsystems business
           :unknown (vec (for [field [:entryLayers :domainSubsystems] id (get config field)
                               :when (not (contains? known id))]
                           {:field (name field) :id id})))))

(defn owners
  "模块所属的子系统 id 集合：取覆盖范围最具体（路径段最多）的子系统，同样具体时全部返回。"
  [{:keys [subsystems module-path]} module]
  (let [path (module-path module)
        scored (for [{:keys [id scopes]} (vals subsystems)
                     scope scopes :when (contains-path? scope path)]
                 [id (count (str/split scope #"/"))])
        best (reduce max -1 (map second scored))]
    (set (keep (fn [[id score]] (when (= score best) id)) scored))))

;; ===== 私有方法 =====

(defn- documents-content
  "项目级架构文档（不含包级 ARCHITECTURE.md）的内容，多份时合并。"
  [ws]
  (let [docs (filter #(= :project (:scope %))
                     (documents/discover (:root ws) (:architecture ws) (get-in ws [:opts :architecture-doc])))]
    {:path (some-> (first docs) :path)
     :contents (map #(:content (documents/read-document (:root ws) (:id %))) docs)}))

(defn- flow-graph
  "与网页 parseFlowEdges 一致：只认 `a -->|文字| b` 形式的标准连线；节点取声明和连线两端的标识。"
  [sources]
  (let [stripped (map #(-> % (str/replace #"%%[^\n]*" "") (str/replace #"\"(?:\\.|[^\"\\])*\"" "\"\"")) sources)
        edges (for [source stripped
                    [_ from to] (re-seq #"([A-Za-z][\w]*)\s+-->\s*(?:\|[^|]*\|)?\s*([A-Za-z][\w]*)" source)]
                {:from from :to to})
        declared (for [source stripped
                       [_ id] (re-seq #"(?m)^\s*([A-Za-z][\w]*)\s*[\[({]" source)]
                   id)]
    {:nodes (into {} (map #(vector % %)) (concat declared (mapcat (juxt :from :to) edges)))
     :edges (vec (distinct edges))}))

(defn- contains-path? [scope path]
  (or (= scope path) (str/starts-with? path (str scope "/"))))

(defn- module-path
  "图节点名去掉命名空间根层后的浏览路径，与网页 modulePath 一致。"
  [ws]
  (let [depth (or (get-in ws [:architecture :guidance :namespace-root-depth]) 0)]
    (memoize #(str/join "/" (drop depth (str/split (str %) #"\."))))))

(defn- bound-subsystems
  "有代码绑定的子系统：文档小节的探索链接与覆盖范围；没有小节的流程节点只在标识正好是代码路径时绑定。"
  [roles nodes paths]
  (let [bound? (fn [scopes] (some (fn [scope] (some #(contains-path? scope %) paths)) scopes))
        by-role (for [{:keys [id label scopes]} roles
                      :let [scopes (mapv #(str/join "/" %) scopes)]
                      :when (bound? scopes)]
                  {:id id :label label :scopes scopes})
        role-ids (set (map :id roles))
        by-node (for [id (keys nodes) :when (and (not (role-ids id)) (bound? [id]))]
                  {:id id :label id :scopes [id]})]
    (into {} (map (juxt :id identity)) (concat by-role by-node))))

(defn- auto-entry-layers
  "含有入口处理类，且流程图里指向它的连线全部来自没有代码绑定的节点（外部调用方、上游消息）。"
  [{:keys [subsystems edges order]} handler-paths]
  (let [in-flow (set (mapcat (juxt :from :to) edges))
        has-entry? (fn [{:keys [scopes]}] (some (fn [scope] (some #(contains-path? scope %) handler-paths)) scopes))]
    (vec (for [id order
               :let [subsystem (subsystems id)]
               :when (and subsystem (in-flow id) (has-entry? subsystem)
                          (every? #(not (subsystems (:from %))) (filter #(= id (:to %)) edges)))]
           id))))

(defn- downstream
  "接入层在流程图中的直接下游里，有代码绑定、本身不是接入层的子系统。"
  [{:keys [subsystems edges order]} layers]
  (let [targets (set (keep #(when (layers (:from %)) (:to %)) edges))]
    (vec (filter #(and (targets %) (subsystems %) (not (layers %))) order))))
