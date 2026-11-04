;; 职责：解析 arch-view entries 子命令，按业务域列出入口、检查入口规则或统计候选，输出中文 Markdown 或 JSON。
;; 核心入口：命令入口（run!）。

(ns arch-view.entries.cli
  (:refer-clojure :exclude [run!])
  (:require [arch-view.cli-support :as support]
            [arch-view.entries.candidates :as candidates]
            [arch-view.entries.core :as entries]
            [arch-view.entries.domains :as domains]
            [arch-view.entries.report :as report]
            [arch-view.entries.rules :as rules]
            [clojure.string :as str]))

(declare ^:private help-text ^:private list-data ^:private list-markdown ^:private check-markdown
         ^:private validate! ^:private exit-code ^:private candidates-data ^:private candidates-markdown
         ^:private unclassified-markdown)

(defn run! [args]
  (let [{:keys [options positional]}
        (support/parse-args args {:values #{"--kind" "--module" "--limit" "--domain" "--group-by"} :flags #{"--strict"}})
        [command & extra] positional]
    (if (or (:help options) (nil? command))
      (do (println (help-text)) 0)
      (do
        (validate! command extra options)
        (let [ws (support/load-workspace options)]
          (case command
            "list" (do (support/emit! options (list-data (entries/scan ws) options) list-markdown) 0)
            "check" (let [data (report/check (entries/scan ws))]
                      (support/emit! options data check-markdown)
                      (exit-code data options))
            "candidates" (do (support/emit! options (candidates-data ws options) candidates-markdown) 0)))))))

;; ===== 私有方法 =====

(def ^:private kinds #{"http" "rpc" "mq" "schedule" "startup" "event"})

(defn- help-text []
  (str "用法：arch-view entries <命令> [参数]\n\n"
       "  list [--kind 类型] [--module 构建模块或模块前缀] [--domain 业务域] [--group-by 来源] [--limit N]\n"
       "                             按业务域列出入口；类型为 http、rpc、mq、schedule、startup、event；\n"
       "                             来源为 rules、architecture、annotation、inferred，默认取 check 给出的默认来源\n"
       "  check [--strict]           检查入口规则：机制总览、规则命中、警告与信息\n"
       "                             规则文件有错时退出码为 1；--strict 有警告时退出码为 2\n"
       "  candidates [--limit N]     统计规则未覆盖、可能是入口的注解、父类型与 XML 声明（默认前 30 条）\n\n"
       "内置规则覆盖 Spring MVC、JAX-RS、@Scheduled、Kafka、RabbitMQ、RocketMQ、Spring 事件、\n"
       "Dubbo、SOFA、gRPC、XXL-JOB、Quartz、ElasticJob、main 与 CommandLineRunner；\n"
       "公司自定义注解写在项目根 .arch-view/entry-rules.json，格式见 docs/entry-navigation.md。\n"
       "当前只识别 Java 项目。\n\n"
       (support/common-help)))

(defn- validate! [command extra options]
  (when-not (#{"list" "check" "candidates"} command)
    (throw (ex-info (str "未知子命令：" command "；请运行 arch-view entries --help") {:status 400})))
  (when (seq extra) (throw (ex-info (str "多余的位置参数：" (str/join " " extra)) {:status 400})))
  (when (and (:kind options) (not (kinds (:kind options))))
    (throw (ex-info (str "--kind 只能是 " (str/join "、" (sort kinds))) {:status 400})))
  (when (and (:group-by options) (not (some #{(:group-by options)} domains/sources)))
    (throw (ex-info (str "--group-by 只能是 " (str/join "、" domains/sources)) {:status 400})))
  (when (and (:limit options) (not (some-> (parse-long (str (:limit options))) pos?)))
    (throw (ex-info "--limit 必须是正整数" {:status 400}))))

(defn- module-match? [prefix entry]
  (or (= prefix (:buildModule entry)) (= prefix (:module entry))
      (str/starts-with? (str (:module entry)) (str prefix "."))))

(defn- domain-match? [wanted entry source]
  (some #(or (= wanted (:id %)) (= wanted (:label %))) (domains/domains-of entry source)))

(defn- list-data [{:keys [entries rules unsupported language grouping]} {:keys [kind module limit domain group-by]}]
  (let [source (or group-by (:default grouping) "inferred")
        items (cond->> entries
                kind (filter #(= kind (:kind %)))
                module (filter #(module-match? module %))
                domain (filter #(domain-match? domain % source)))
        total (count items)]
    {:rules rules :unsupported (boolean unsupported) :language language :total total
     :groupBy source :defaultGroupBy (:default grouping) :domain domain :grouping grouping
     :entries (vec (if limit (take (parse-long (str limit)) items) items))}))

(defn- cell [value]
  (-> (str value) (str/replace "|" "\\|") (str/replace #"[\r\n]+" " ")))

(defn- rules-line [{:keys [file status errors]}]
  (str "规则文件：" file "（"
       (case status "ok" "已读取" "missing" "未找到，仅用内置规则" "error" "有错误，暂只用内置规则")
       "）\n"
       (when (seq errors) (str "\n" (str/join "\n" (map #(str "- " %) errors)) "\n"))))

(defn- entry-row [source entry]
  (let [multi? (> (count (remove domains/special? (get-in entry [:domains (keyword source)]))) 1)]
    (str "| " (str/join " | " (map cell [(get-in entry [:channel :label]) (:label entry) (get-in entry [:anchor :symbol])
                                         (report/location entry)
                                         (str/join "、" (concat (when multi? ["多归属"]) (:tags entry) (:warnings entry)))]))
         " |")))

(defn- domain-section [source {:keys [domain entries]}]
  (str "## " (:label domain) "（" (count entries) "）\n\n"
       "| 机制 | 入口 | 处理方法 | 位置 | 标签与警告 |\n| --- | --- | --- | --- | --- |\n"
       (str/join "\n" (map #(entry-row source %)
                           (sort-by (juxt #(get-in % [:channel :label]) #(get-in % [:handler :type]) :label) entries)))))

(defn- source-line [{:keys [groupBy defaultGroupBy unsupported]}]
  (when-not unsupported
    (str "分组来源：" groupBy (when (= groupBy defaultGroupBy) "（默认）")
         "；可用 --group-by rules|architecture|annotation|inferred 切换，多归属入口在每个组下都出现。\n\n")))

(defn- list-markdown [{:keys [rules unsupported language total entries groupBy domain] :as data}]
  (str "# 入口（" total "）\n\n" (rules-line rules) "\n" (source-line data)
       (when unsupported (str "当前只识别 Java 项目，本项目语言为 " (some-> language name) "。\n\n"))
       ;; --domain 时只显示该组，多归属入口不再带出其它组。
       (str/join "\n\n" (map #(domain-section groupBy %)
                               (cond->> (domains/group-entries entries groupBy)
                                 domain (filter #(some #{domain} ((juxt :id :label) (:domain %)))))))
       (when (< (count entries) total) (str "\n\n…只显示 " (count entries) " 项，共 " total " 项。"))))

(defn- notes-section [title notes]
  (when (seq notes)
    (str "## " title "\n\n"
         (str/join "\n\n" (for [{:keys [code message count items]} notes]
                            (str "### " message "（" code "，" count "）\n\n"
                                 (str/join "\n" (map #(str "- " %) items))
                                 (when (> count (clojure.core/count items)) (str "\n- …共 " count " 项")))))
         "\n\n")))

(defn- grouping-markdown [{:keys [default groups]}]
  (str "## 业务分组（来源 " default "）\n\n| 业务域 | 入口 |\n| --- | --- |\n"
       (str/join "\n" (map #(str "| " (cell (get-in % [:domain :label])) " | " (:count %) " |") groups))
       "\n\n"))

(defn- check-markdown [{:keys [rules channels ruleHits grouping warnings infos summary]}]
  (str "# 入口规则检查\n\n" (rules-line rules) "\n"
       "## 机制总览\n\n| 机制 | 规则 | 数量 |\n| --- | --- | --- |\n"
       (str/join "\n" (map #(str "| " (cell (:channel %)) " | " (cell (str/join "、" (:rules %))) " | " (:count %) " |")
                           channels))
       "\n\n## 规则命中\n\n| 规则 | 命中 | 示例 |\n| --- | --- | --- |\n"
       (str/join "\n" (map #(str "| " (cell (:rule %)) " | " (:hits %) " | " (cell (str/join "；" (:samples %))) " |")
                           ruleHits))
       "\n\n"
       (when grouping (grouping-markdown grouping))
       (notes-section (str "警告（" (:warnings summary) "）") warnings)
       (notes-section "信息" infos)
       "入口 " (:entries summary)
       (when (seq channels) (str "（" (str/join " / " (map #(str (:channel %) " " (:count %)) channels)) "）"))
       (when grouping (str "，业务域 " (:domains summary) "，未归类 " (:unclassified summary)))
       "，警告 " (:warnings summary)
       (when (= "error" (:status rules)) "，规则文件有错误")
       "。"))

(defn- exit-code [{:keys [rules summary]} options]
  (cond (= "error" (:status rules)) 1
        (and (:strict options) (pos? (:warnings summary))) 2
        :else 0))

(def ^:private level-names {"class" "类" "method" "方法" "interfaceMethod" "接口方法"})

(defn- candidates-data [ws {:keys [limit]}]
  (let [language (get-in ws [:architecture :guidance :language])]
    (if (= :java language)
      (let [loaded (rules/load-rules ws)
            ctx (entries/analysis-context ws loaded)]
        (assoc (candidates/candidates ctx (if limit (parse-long (str limit)) 30))
               :rules (select-keys loaded [:file :status :errors])
               :unclassified (candidates/unclassified (entries/scan-with ws loaded ctx))))
      {:unsupported true :language language :annotations [] :supertypes [] :xml [] :total {}})))

(defn- annotation-lines [index {:keys [fqn levels sample attrs values with]}]
  (str (inc index) ". 注解 `" fqn "` "
       (str/join "，" (for [[level n] (sort-by key levels)] (str (level-names level) " ×" n)))
       " · 示例 " sample
       (when (seq attrs) (str "\n   属性：" (str/join ", " attrs)))
       (when (seq with) (str "\n   同时出现：" (str/join "、" (map #(str (:annotation %) "（类 ×" (:count %) "）") with))))
       (str/join (for [{:keys [path values]} values]
                   (str "\n   取值 " path "：" (str/join ", " (map (fn [[v n]] (str v " ×" n)) values)))))))

(defn- unclassified-markdown [{:keys [source paths packages] total :count}]
  (when (pos? (or total 0))
    (str "\n\n## 未归类入口（来源 " source "，" total "）\n\n起草 domains 时参考：\n\n"
         (when (seq paths) (str "- HTTP 路径：" (str/join "、" (map #(str (:value %) " ×" (:count %)) paths)) "\n"))
         "- 处理类包：" (str/join "、" (map #(str (:value %) " ×" (:count %)) packages)))))

(defn- candidates-markdown [{:keys [annotations supertypes xml total rules unsupported language unclassified]}]
  (if unsupported
    (str "当前只识别 Java 项目，本项目语言为 " (some-> language name) "。")
    (str "# 入口候选\n\n" (rules-line rules)
         "\n规则尚未覆盖、可能是入口的写法，按「方法级 → 类级 → 父类型」和次数排序；每类入口挑一个，只读示例位置写规则。\n\n"
         "## 注解（显示 " (count annotations) "，共 " (:annotations total) "）\n\n"
         (str/join "\n" (map-indexed annotation-lines annotations))
         "\n\n## 父类型（显示 " (count supertypes) "，共 " (:supertypes total) "）\n\n"
         (str/join "\n" (map-indexed #(str (inc %1) ". 父类型 `" (:fqn %2) "` 类 ×" (:count %2) " · 示例 " (:sample %2))
                                     supertypes))
         "\n\n## XML 声明（" (count xml) "）\n\n"
         (str/join "\n" (map-indexed #(str (inc %1) ". `" (:element %2) "` ×" (:count %2) " · 示例 " (:sample %2)) xml))
         (unclassified-markdown unclassified))))
