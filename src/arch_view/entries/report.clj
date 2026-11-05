;; 职责：根据入口扫描结果生成规则检查报告：机制总览、规则命中、业务分组、警告与信息，供 AI 修规则、供人复核。
;; 核心入口：检查报告（check）；报告按警告数决定 --strict 退出码。

(ns arch-view.entries.report
  (:require [arch-view.entries.domains :as domains]
            [clojure.string :as str]))

(declare ^:private warnings ^:private infos ^:private grouping-report ^:private item)

(defn location [entry]
  (str (get-in entry [:anchor :path]) ":" (get-in entry [:anchor :startLine])))

(defn sample [entry]
  (str (:label entry) " — " (get-in entry [:anchor :symbol]) " (" (location entry) ")"))

(defn channels
  "按机制统计入口数及来源规则，数量多的在前。"
  [entries]
  (->> (group-by #(get-in % [:channel :label]) entries)
       (map (fn [[label items]] {:channel label :rules (vec (sort (distinct (map :rule items)))) :count (count items)}))
       (sort-by (juxt (comp - :count) :channel)) vec))

(defn check
  "返回 {:rules :channels :ruleHits :grouping :warnings :infos :summary}；规则文件错误不计入警告，单独放在 :rules。"
  [{:keys [entries rules] :as scanned}]
  (let [warns (warnings scanned)
        by-channel (channels entries)
        grouping (grouping-report scanned)]
    {:rules rules
     :channels by-channel
     :ruleHits (->> (group-by :rule entries)
                    (map (fn [[rule items]] {:rule rule :hits (count items) :samples (mapv sample (take 3 items))}))
                    (sort-by (juxt (comp - :hits) :rule)) vec)
     :grouping grouping
     :warnings warns
     :infos (infos scanned)
     :summary {:entries (count entries) :warnings (reduce + (map :count warns))
               :channels (into {} (map (juxt :channel :count)) by-channel)
               :domains (count (remove #(domains/special? %) (map :domain (:groups grouping))))
               :unclassified (:unclassified grouping 0)}}))

;; ===== 私有方法 =====

(defn- grouping-report
  "默认来源下的分组：各组入口数（多归属在每组都计）与未归类数。"
  [{:keys [entries grouping]}]
  (when grouping
    (let [groups (domains/group-entries entries (:default grouping))
          unclassified (some #(when (= (:id domains/unclassified) (get-in % [:domain :id])) (count (:entries %))) groups)]
      {:default (:default grouping) :sources (:sources grouping)
       :groups (mapv (fn [{:keys [domain entries]}] {:domain domain :count (count entries)}) groups)
       :unclassified (or unclassified 0)})))

(defn- grouping-problems [grouping code]
  (keep #(when (= code (:code %)) (:item %)) (:problems grouping)))

(defn- unclassified-items
  "默认来源下未归类超过 20% 时列出这些入口。"
  [{:keys [entries grouping]}]
  (let [grouped (remove :aside entries)
        missing (filter #(= [domains/unclassified] (domains/domains-of % (:default grouping))) grouped)]
    (when (> (count missing) (* 0.2 (count grouped))) missing)))

(defn- grouping-warnings [{:keys [grouping] :as scanned}]
  (when grouping
    [(item "unclassified" (str "默认分组来源 " (:default grouping) " 下超过 20% 的入口未归类：补架构文档绑定、写 domains，或检查接入层判定")
           (map sample (unclassified-items scanned)))
     (item "unknown-subsystem" "entryLayers / domainSubsystems 写了架构文档里不存在的子系统" (grouping-problems grouping "unknown-subsystem"))
     (item "domain-zero-hit" "规则 domains 中的业务域没有命中任何入口" (grouping-problems grouping "domain-zero-hit"))
     (item "aside-zero-hit" "aside 分组没有命中任何入口：检查类名" (grouping-problems grouping "aside-zero-hit"))]))

(def ^:private basis-text
  {"rule" "规则文件指定" "auto" "自动判定：含入口，且数据流图中只被没有代码绑定的节点指向"
   "downstream" "接入层在数据流图中的直接下游" "no-entry-layer" "没有接入层，全部有代码绑定的子系统"})

(defn- subsystem-items [{:keys [document subsystems entryLayers domainSubsystems]}]
  (let [label (into {} (map (juxt :id :label)) subsystems)
        names #(if (seq %) (str/join "、" (map (fn [id] (if (label id) (str id "（" (label id) "）") id)) %)) "无")]
    [(str "架构文档：" (or document "未找到项目级架构文档，architecture 来源不可用"))
     (str "接入层：" (names (:ids entryLayers)) " — " (basis-text (:basis entryLayers)))
     (str "业务子系统：" (names (:ids domainSubsystems)) " — " (basis-text (:basis domainSubsystems)))]))

(defn- source-items [{:keys [default sources]}]
  (for [{:keys [id coverage domains multi unclassified]} sources]
    (str id "：覆盖 " (Math/round (* 100 (double coverage))) "%，业务域 " domains "，多归属 " multi "，未归类 " unclassified
         (when (= id default) "（默认）"))))

(defn- multi-domain [{:keys [entries grouping]}]
  (for [entry entries
        :let [found (remove domains/special? (get-in entry [:domains (keyword (:default grouping))]))]
        :when (> (count found) 1)]
    (str (sample entry) "：" (str/join "、" (map :label found)))))

(defn- item [code message items]
  (when (seq items) {:code code :message message :count (count items) :items (vec items)}))

(defn- suspect-items [suspects evidence]
  (for [suspect suspects :when (= evidence (:evidence suspect "name"))]
    (str (:type suspect) " (" (:location suspect) ")；依据：" (str/join "、" (:basis suspect)))))

(defn- zero-hit [{:keys [entries project-rules]}]
  (let [hit (set (concat (map :rule entries) (mapcat :metaRules entries)))]
    (for [id (distinct (map :id (remove #(= :exit (:role %)) project-rules))) :when (not (contains? hit id))] (str "规则 " id))))

(defn- with-warning [entries code]
  (map sample (filter #(some #{code} (:warnings %)) entries)))

(defn- duplicates
  "同一 HTTP 方法 + 路径或同一 operation 落在多个处理方法上。"
  [entries]
  (let [key-of #(case (:kind %)
                  "http" (:label %)
                  "rpc" (some->> (get-in % [:attrs "operation"]) (str "operation "))
                  nil)]
    (for [[key items] (group-by key-of entries)
          :when key
          :let [handlers (distinct (map #(get-in % [:anchor :symbol]) items))]
          :when (> (count handlers) 1)]
      (str key "：" (str/join "、" handlers)))))

(defn- problems [scanned code]
  (keep #(when (= code (:code %)) (:item %)) (get-in scanned [:declared :problems])))

(defn- with-source [entries code]
  (map #(str (sample %) "；XML " (some (fn [s] (when (= "xml" (:kind s))
                                                 (str (get-in s [:anchor :path]) ":" (get-in s [:anchor :startLine]))))
                                     (:sources %)))
       (filter #(some #{code} (:warnings %)) entries)))

(defn- declared-warnings [{:keys [entries declared] :as scanned}]
  [(item "orphan-subscription" "XML 订阅找不到 key 一致的 handler，消息到达后无人处理（入口挂在分发者上）"
         (with-source entries "orphan-subscription"))
   (item "unsubscribed-handler" "handler 的 key 不在任何 XML 订阅中，永远收不到消息" (:unsubscribed declared))
   (item "duplicate-key" "多个 handler 的 key 相同，建分发表时会覆盖或报错" (with-source entries "duplicate-key"))
   (item "key-unresolved" "handler 的 key 方法不是单个 return 或无法静态求值" (problems scanned "key-unresolved"))
   (item "bean-unresolved" "XML 引用的 bean 找不到对应的类" (problems scanned "bean-unresolved"))
   (item "xml-error" "XML 文件解析失败，已跳过" (problems scanned "xml-error"))])

(defn- warnings [{:keys [entries ambiguous suspects] :as scanned}]
  (vec (keep identity
             (concat
              [(item "zero-hit" "项目规则没有命中任何入口：检查全名、on 或 when 是否写对" (zero-hit scanned))
              (item "unresolved-attr" "属性用了无法静态求值的表达式，入口里显示源码原文" (with-warning entries "unresolved-attr"))
              (item "duplicate-route" "同一路由或 operation 出现在多个处理方法，可能前缀没取到" (duplicates entries))
              (item "multi-impl" "接口方法上的入口注解有多个实现类，每个实现各算一个入口" (with-warning entries "multi-impl"))
              (item "unresolved-declaration" "已声明发布 RPC 服务，但入口方法继承自外部依赖，无法定位；需复核，未将 protected hook 当作入口"
                    (with-warning entries "unresolved-declaration"))
              (item "ambiguous-doc" "多个接口或父类提供了不同方法说明，需复核文档来源" (with-warning entries "ambiguous-doc"))
              (item "ambiguous-contract" "同一入口匹配多个契约来源，已保留候选而未任取一个" (with-warning entries "ambiguous-contract"))
              (item "no-impl" "接口方法上的入口注解没有项目内实现类" (with-warning entries "no-impl"))
              (item "ambiguous-import" "多个通配导入都可能提供该注解，无法确定全名，未计入入口"
                    (map #(str "@" (:annotation %) " (" (:location %) ")：" (str/join "、" (:candidates %))) ambiguous))
              (item "suspect" "有入口规则证据但未识别出入口：检查方法选择或规则配置" (suspect-items suspects "framework"))]
              (declared-warnings scanned)
              (grouping-warnings scanned)))))

(defn- same-topic [entries]
  (for [[topic items] (group-by #(get-in % [:attrs "topic"]) (filter #(= "mq" (:kind %)) entries))
        :when (and topic (> (count items) 1))]
    (str topic "：" (str/join "、" (map #(str (get-in % [:anchor :symbol])
                                             (when-let [group (get-in % [:attrs "consumerGroup"])] (str "[" group "]")))
                                       items)))))

(defn- infos [{:keys [entries overrides grouping suspects] :as scanned}]
  (vec (keep identity
             [(item "suspect-name" "仅命名候选：类名像入口，尚无入口规则证据，可能是内部处理器" (suspect-items suspects "name"))
              (when grouping (item "subsystems" "接入层与业务子系统的判定" (subsystem-items grouping)))
              (when grouping (item "grouping-sources" "各业务分组来源的覆盖情况" (source-items grouping)))
              (when grouping (item "multi-domain" "默认来源下归入多个业务域的入口（每个组下都会出现）" (multi-domain scanned)))
              (item "same-topic" "同一 topic 有多个消费方" (same-topic entries))
              (item "override" "项目规则覆盖了同一方法上的内置规则"
                    (map #(str (:handler %) "：" (str/join "、" (:project %)) " 覆盖 " (str/join "、" (:builtin %)))
                         overrides))])))
