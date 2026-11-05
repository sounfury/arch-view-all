;; 职责：处理写在 Spring XML 里的入口声明：每个声明单元生成入口，按 key 分发时对接 handler，并找出订阅与 handler key 对不上的地方。
;; 核心入口：XML 规则候选（xml-candidates）；模板填充（fill-template）。

(ns arch-view.entries.declared
  (:require [arch-view.entries.java-source :as java]
            [arch-view.entries.methods :as methods]
            [arch-view.entries.xml-source :as xml]
            [arch-view.workspace :as workspace]
            [clojure.string :as str])
  (:import [java.beans Introspector]))

(declare ^:private rule-candidates ^:private annotated-beans)

(defn fill-template
  "把 {字段} 换成字段值；有字段缺失时返回 nil。"
  [template fields]
  (when template
    (let [missing (atom false)
          text (str/replace template #"\{([^}]+)\}"
                            (fn [[_ key]] (let [value (get fields key)]
                                            (if (and (string? value) (seq value)) value (do (reset! missing true) "")))))]
      (when-not @missing text))))

(defn xml-candidates
  "所有 xml 规则的入口候选，以及 :problems（bean 或 key 解析不了、XML 解析失败）与 :unsubscribed（没有订阅的 handler）。"
  [ctx]
  (let [xml-rules (filter #(and (get-in % [:match :xml]) (not= :exit (:role %))) (:rules ctx))]
    (if (empty? xml-rules)
      {:candidates [] :problems [] :unsubscribed []}
      (let [ws (:ws ctx)
            root (.toFile ^java.nio.file.Path (:root ws))
            roots (xml/resource-roots root (get-in ws [:architecture :guidance :source-paths]))
            ctx (assoc ctx :xml-files (xml/xml-files roots)
                       :beans (delay (merge (xml/bean-classes roots) (annotated-beans ctx))))
            results (map #(rule-candidates ctx %) xml-rules)]
        {:candidates (vec (mapcat :candidates results))
         :problems (vec (mapcat :problems results))
         :unsubscribed (vec (mapcat :unsubscribed results))}))))

;; ===== 私有方法 =====

(def ^:private bean-annotations
  #{"Service" "Component" "Repository" "Controller" "RestController" "Configuration"})

(defn- explicit-bean-name [index type]
  (some (fn [ann]
          (let [fqn (:fqn (java/annotation-type index type ann))]
            (when (and (str/starts-with? (str fqn) "org.springframework.")
                       (contains? bean-annotations (last (str/split fqn #"\."))))
              (let [value (get (java/annotation-attrs index type ann) "value")]
                (when (and (string? value) (seq value)) value)))))
        (:annotations type)))

(defn- annotated-beans
  "源码中的 bean 名 → 类：显式命名优先，其次 Spring 默认名（类简单名首字母小写）。"
  [{:keys [index]}]
  (let [types (filter #(and (= :class (:kind %)) (not (:abstract? %))) (vals (:types index)))]
    (merge (into {} (map (juxt #(Introspector/decapitalize (:simple %)) :fqn)) types)
           (into {} (keep #(when-let [name (explicit-bean-name index %)] [name (:fqn %)])) types))))

(defn- rel [ctx file]
  (workspace/relative (:ws ctx) file))

(defn- type-anchor [ctx type]
  {:path (rel ctx (:file type)) :startLine (:start type) :endLine (:end type) :symbol (:simple type)})

(defn- handler-key
  "handler 的 key：keyMethod 方法体为单个 return 时静态求值。"
  [ctx type key-method]
  (let [method (first (filter #(and (= key-method (:name %)) (empty? (:params %)) (:return %)) (:methods type)))
        value (when method (java/eval-tree (:index ctx) type (get-in method [:return :tree])))]
    {:type type :method method
     :key (when (string? value) value)
     :line (get-in method [:return :line])}))

(defn- dispatch-handlers [ctx {:keys [implements keyMethod]}]
  (for [type (vals (get-in ctx [:index :types]))
        :when (and (= :class (:kind type)) (not (:abstract? type))
                   (contains? ((:supers ctx) (:fqn type)) implements))]
    (handler-key ctx type keyMethod)))

(defn- unit-fields [{:keys [chain]} fields]
  (into {} (for [[name path] fields] [name (xml/field-value chain path)])))

(defn- xml-source [ctx file {:keys [declaration]}]
  {:kind "xml" :anchor {:path (rel ctx file) :startLine (:start declaration) :endLine (:end declaration)}})

(defn- base-candidate [rule fields source]
  (let [sem (into {} (for [[k v] fields :when (not= "bean" k)]
                       [k (if (nil? v) {:unresolved "" :name nil} v)]))]
    {:rule rule :kind (:kind rule) :sem sem
     :label-template (:label rule) :sources [source]
     :warnings (when (some nil? (vals fields)) ["unresolved-attr"])}))

(defn- method-candidates [ctx type spec base]
  (for [method (methods/select-methods ctx type spec {})]
    (assoc base :type type :method method)))

(defn- dispatched [ctx rule {:keys [handlers]} key base dispatcher]
  (let [matched (filter #(= key (:key %)) handlers)
        warnings (when (> (count matched) 1) ["duplicate-key"])]
    (if (seq matched)
      (mapcat (fn [{:keys [type line method]}]
                (method-candidates ctx type (get-in rule [:dispatch :handlers :entryMethods])
                                   (-> base
                                       (update :warnings concat warnings)
                                       (update :sources concat
                                               (cond-> [{:kind "dispatch-key" :key key
                                                         :anchor {:path (rel ctx (:file type)) :startLine line :endLine line
                                                                  :symbol (str (:simple type) "#" (:name method))}}]
                                                 dispatcher (conj {:kind "dispatcher" :type (:fqn dispatcher)
                                                                   :method (some-> (first (methods/select-methods ctx dispatcher nil {})) :name)
                                                                   :anchor (type-anchor ctx dispatcher)}))))))
              matched)
      (when dispatcher
        (method-candidates ctx dispatcher nil (update base :warnings concat ["orphan-subscription"]))))))

(defn- unit-candidates [ctx rule dispatch file unit]
  (let [fields (unit-fields unit (get-in rule [:match :xml :fields]))
        base (base-candidate rule fields (xml-source ctx file unit))
        bean (get fields "bean")
        bean-type (some->> (get @(:beans ctx) bean) (get (get-in ctx [:index :types])))
        location (str (rel ctx file) ":" (get-in unit [:declaration :start]))
        problems (when (and bean (not bean-type)) [{:code "bean-unresolved" :item (str bean " (" location ")")}])]
    (if dispatch
      (let [key (fill-template (get-in rule [:dispatch :key]) fields)]
        {:keys [key]
         :candidates (dispatched ctx rule dispatch key base bean-type)
         :problems problems})
      {:candidates (when bean-type (method-candidates ctx bean-type (:methods rule) base))
       :problems problems})))

(defn- parse-units [ctx rule]
  (for [{:keys [file]} (xml/matching-files (:xml-files ctx) (get-in rule [:match :xml :files]))
        :let [parsed (try {:root (xml/parse-file file)} (catch Exception ex {:error (.getMessage ex)}))]]
    (assoc parsed :file file)))

(defn- rule-candidates [ctx rule]
  (let [handlers (when-let [spec (get-in rule [:dispatch :handlers])] (vec (dispatch-handlers ctx spec)))
        dispatch (when handlers {:handlers handlers})
        parsed (parse-units ctx rule)
        results (for [{:keys [root file]} parsed :when root
                      unit (xml/units root (get-in rule [:match :xml :each]))]
                  (unit-candidates ctx rule dispatch file unit))
        subscribed (set (mapcat :keys results))]
    {:candidates (mapcat :candidates results)
     :problems (concat (for [{:keys [error file]} parsed :when error]
                         {:code "xml-error" :item (str (rel ctx file) "：" error)})
                       (mapcat :problems results)
                       (for [{:keys [type key line]} handlers :when (nil? key)]
                         {:code "key-unresolved" :item (str (:fqn type) " (" (rel ctx (:file type)) ":" (or line (:start type)) ")")}))
     :unsubscribed (for [{:keys [type key line]} handlers
                         :when (and key (not (contains? subscribed key)))]
                     (str key " — " (:fqn type) " (" (rel ctx (:file type)) ":" line ")"))}))
