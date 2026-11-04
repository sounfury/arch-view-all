;; 职责：给入口计算业务域（规则、架构文档、注解分组、推断四种来源）与旁置分组，选出默认来源并统计覆盖率和分组问题。
;; 核心入口：业务分组（assign）；特殊分组（infra、unclassified）。

(ns arch-view.entries.domains
  (:require [arch-view.entries.architecture :as arch]
            [clojure.string :as str]))

(declare ^:private aside-matcher ^:private architecture-attributor ^:private method-attributor ^:private rule-domains ^:private annotation-domains
         ^:private inferred-domains ^:private source-stats ^:private problems ^:private model-summary)

(def sources ["rules" "architecture" "annotation" "inferred"])

(def infra {:id "_infra" :label "基础设施/其它"})

(def unclassified {:id "_unclassified" :label "未归类"})

(defn special? [domain]
  (str/starts-with? (str (:id domain)) "_"))

(defn assign
  "config 为规则文件顶层分组配置；impl-edges 为接口到唯一实现的补充依赖边，supers 为类型全名 → 全部父类型全名。
  返回 {:entries（补 :domains 或 :aside） :grouping {:default :sources :entryLayers :domainSubsystems :subsystems :problems}}。"
  [ws config entries {:keys [impl-edges supers method-graph method-root]}]
  (let [aside-of (aside-matcher (:aside config) supers)
        model (arch/model ws (map :module (remove aside-of entries)) config)
        by-class (architecture-attributor ws model impl-edges)
        by-method (when (and method-graph (seq (:subsystems model)))
                    (delay (method-attributor model @method-graph method-root)))
        by-architecture (if by-method (fn [entry] (@by-method entry)) (fn [entry] (by-class (:module entry))))
        assigned (mapv (fn [entry]
                         (if-let [label (aside-of entry)]
                           (assoc entry :aside label)
                           (assoc entry :domains
                                  (into {} (remove (comp empty? val))
                                        {:rules (rule-domains (:domains config) entry)
                                         :architecture (by-architecture entry)
                                         :annotation (annotation-domains entry)
                                         :inferred (inferred-domains entry)}))))
                       entries)
        stats (mapv #(source-stats assigned %) sources)]
    {:entries assigned
     :grouping (merge (model-summary model)
                      {:default (or (some #(when (and (>= (:coverage %) 0.5) (>= (:domains %) 2)) (:id %)) stats)
                                    "inferred")
                       :sources stats
                       :problems (problems config model assigned)})}))

(defn domains-of
  "入口在某来源下的业务域；旁置入口为其分组，没有结果为「未归类」。"
  [entry source]
  (if-let [label (:aside entry)]
    [{:id (str "_aside:" label) :label label}]
    (or (not-empty (get-in entry [:domains (keyword source)])) [unclassified])))

(defn group-entries
  "按来源把入口分组：业务域按入口数降序，其后依次是基础设施/其它、旁置分组、未归类。
  多归属入口在每个组下重复出现。返回 [{:domain {:id :label} :entries [...]}]。"
  [entries source]
  (let [rank (fn [{:keys [id]}]
               (cond (= id (:id infra)) 1
                     (str/starts-with? id "_aside:") 2
                     (= id (:id unclassified)) 3
                     :else 0))]
    (->> (for [entry entries domain (domains-of entry source)] [domain entry])
         (group-by first)
         (map (fn [[domain pairs]] {:domain domain :entries (mapv second pairs)}))
         (sort-by (juxt (comp rank :domain) (comp - count :entries) (comp :label :domain)))
         vec)))

;; ===== 私有方法 =====

(defn- wildcard [pattern]
  (re-pattern (str/join ".*" (map #(java.util.regex.Pattern/quote %) (str/split pattern #"\*" -1)))))

(defn- aside-matcher
  "按处理类及其父类型（Facade 接口等）的简单名或全名匹配 aside；返回 entry → 分组名。"
  [asides supers]
  (let [matchers (for [{:keys [label classes]} asides pattern classes] [label (wildcard pattern)])]
    (fn [entry]
      (let [fqn (str (get-in entry [:handler :type]))
            names (mapcat (fn [name] [name (last (str/split name #"\."))]) (cons fqn (sort (supers fqn))))]
        (some (fn [[label regex]] (when (some #(re-matches regex %) names) label)) matchers)))))

(defn- adjacency [ws impl-edges]
  (reduce (fn [adj {:keys [from to]}] (update adj from (fnil conj []) to))
          {} (concat (get-in ws [:architecture :graph :edges]) impl-edges)))

(defn- classifier
  "模块在归属计算中的角色：业务子系统（带 id）、可穿过（接入层或无归属）、其它有绑定的子系统。"
  [model]
  (let [business (set (get-in model [:domainSubsystems :ids]))
        layers (set (get-in model [:entryLayers :ids]))]
    (memoize (fn [module]
               (let [owned (arch/owners model module)
                     hit (filter business owned)]
                 (cond (seq hit) {:role :business :ids hit}
                       (every? layers owned) {:role :pass}
                       :else {:role :other}))))))

(defn- walk
  "从处理类沿依赖边向下最多 3 跳，只穿过可穿过的模块；记录遇到的业务子系统和是否碰到其它子系统。"
  [adj classify start]
  (loop [frontier [start] seen #{start} depth 0 found #{} other? false]
    (if (or (empty? frontier) (= depth 3))
      {:found found :other? other?}
      (let [nexts (vec (distinct (for [module frontier next (adj module) :when (not (seen next))] next)))
            roles (map classify nexts)]
        (recur (keep-indexed #(when (= :pass (:role %2)) (nexts %1)) roles)
               (into seen nexts) (inc depth)
               (into found (mapcat :ids roles))
               (or other? (boolean (some #(= :other (:role %)) roles))))))))

(defn- architecture-attributor [ws model impl-edges]
  (let [adj (adjacency ws impl-edges)
        classify (classifier model)
        rank (into {} (map-indexed (fn [i id] [id i])) (:order model))
        domain (fn [id] {:id id :label (get-in model [:subsystems id :label] id)})]
    (memoize
     (fn [module]
       (let [{:keys [role ids]} (classify module)
             {:keys [found other?]} (when (= :pass role) (walk adj classify module))]
         (cond
           (= :business role) (mapv domain (sort-by rank ids))
           (seq found) (mapv domain (sort-by rank found))
           (or other? (= :other role) (seq (arch/owners model module))) [infra]
           :else []))))))

(defn- method-attributor
  "E1 按入口方法的调用边计算归属，最多 3 跳；保留文档边界，遇到业务子系统或其它绑定即停。"
  [model graph root-of]
  (let [classify (classifier model)
        method-classify (memoize #(classify (get-in graph [:nodes % :module])))
        adj (update-vals (:adj graph) #(mapv :to %))
        rank (into {} (map-indexed (fn [i id] [id i])) (:order model))
        domain (fn [id] {:id id :label (get-in model [:subsystems id :label] id)})]
    (fn [entry]
      (let [module (:module entry)
            {:keys [role ids]} (classify module)
            root (root-of entry)
            {:keys [found other?]} (when (and root (= :pass role)) (walk adj method-classify root))]
        (cond
          (= :business role) (mapv domain (sort-by rank ids))
          (seq found) (mapv domain (sort-by rank found))
          (or other? (= :other role) (seq (arch/owners model module))) [infra]
          :else [])))))

(defn- segments [text separator]
  (vec (remove str/blank? (str/split (str text) separator))))

(defn- prefix-length [prefix value separator]
  (let [prefix (segments prefix separator) value (segments value separator)]
    (when (and (seq prefix) (<= (count prefix) (count value)) (= prefix (subvec value 0 (count prefix))))
      (count prefix))))

(defn- rule-scores [{:keys [paths packages topics]} {:keys [kind attrs handler]}]
  (concat (when (= "http" kind) (keep #(prefix-length % (get attrs "path") #"/") paths))
          (keep #(prefix-length % (:type handler) #"\.") packages)
          ;; topic 全等比任何前缀都具体。
          (keep #(when (= % (get attrs "topic")) 1000) topics)))

(defn- rule-domains
  "规则 domains：路径前缀、包前缀、topic 全等任一命中；取最具体的，同样具体时多归属。"
  [domains entry]
  (let [scored (for [domain domains score (rule-scores domain entry)]
                 [{:id (:id domain) :label (or (:label domain) (:id domain))} score])
        best (reduce max -1 (map second scored))]
    (vec (distinct (keep (fn [[domain score]] (when (= score best) domain)) scored)))))

(defn- annotation-domains [entry]
  (let [group (get-in entry [:attrs "group"])]
    (vec (for [name (distinct (if (sequential? group) group [group])) :when (not (str/blank? name))]
           {:id name :label name}))))

(def ^:private technical-words
  #{"web" "controller" "controllers" "api" "rest" "rpc" "facade" "facades" "impl" "provider" "providers" "endpoint"
    "job" "jobs" "task" "tasks" "schedule" "scheduler" "mq" "msg" "message" "consumer" "consumers" "listener"
    "listeners" "handler" "handlers" "service" "services"})

(defn- inferred-domains
  "HTTP 取路径中去掉 api、版本号、路径变量后的第一段；其它取处理类包名去掉技术词后的最后一段。"
  [{:keys [kind attrs handler]}]
  (let [from-path (when (= "http" kind)
                    (first (remove #(or (= "api" (str/lower-case %)) (re-matches #"(?i)v\d+" %) (str/starts-with? % "{"))
                                   (segments (get attrs "path") #"/"))))
        package (take-while #(not (Character/isUpperCase (.charAt ^String % 0))) (segments (:type handler) #"\."))
        from-package (last (remove #(technical-words (str/lower-case %)) package))
        name (or from-path from-package)]
    (if name [{:id name :label name}] [])))

(defn- source-stats [entries source]
  (let [grouped (remove :aside entries)
        domains (map #(remove special? (get-in % [:domains (keyword source)])) grouped)
        covered (count (filter seq domains))]
    {:id source
     :coverage (if (seq grouped) (/ (Math/round (* 100.0 (/ covered (count grouped)))) 100.0) 0.0)
     :domains (count (distinct (map :id (apply concat domains))))
     :multi (count (filter #(> (count %) 1) domains))
     :unclassified (count (remove #(seq (get-in % [:domains (keyword source)])) grouped))}))

(defn- model-summary [model]
  (let [label #(get-in model [:subsystems % :label] %)]
    {:document (:document model)
     :subsystems (vec (for [id (:order model) :when (get-in model [:subsystems id])]
                        {:id id :label (label id)}))
     :entryLayers (:entryLayers model)
     :domainSubsystems (:domainSubsystems model)}))

(defn- problems [config model entries]
  (let [hit (set (mapcat #(map :id (get-in % [:domains :rules])) entries))
        asides (set (keep :aside entries))]
    (vec (concat
          (for [{:keys [field id]} (:unknown model)]
            {:code "unknown-subsystem" :item (str field " 中的 " id)})
          (for [{:keys [id]} (:domains config) :when (not (hit id))]
            {:code "domain-zero-hit" :item id})
          (for [{:keys [label]} (:aside config) :when (not (asides label))]
            {:code "aside-zero-hit" :item label})))))
