;; 职责：把方法调用中的下游客户端、数据库和消息发布识别为出口；支持项目 role=exit 规则、内置规则与 XML publisher 声明。
;; 核心入口：构造出口匹配器（matcher）；返回的函数接受一次调用并返回出口节点描述或 nil。

(ns arch-view.entries.exits
  (:require [arch-view.entries.core :as core]
            [arch-view.entries.declared :as declared]
            [arch-view.entries.java-source :as java]
            [arch-view.entries.xml-source :as xml]
            [arch-view.workspace :as workspace]
            [clojure.string :as str]))

(declare ^:private xml-declarations ^:private call-hits ^:private xml-hits ^:private exit-node)

(defn matcher [ctx graph]
  (let [rules (filter #(= :exit (:role %)) (:rules ctx))
        xml (xml-declarations ctx rules)]
    (fn [call]
      (when-let [hit (first (sort-by #(get-in % [:rule :order])
                                     (concat (call-hits ctx graph rules call) (xml-hits ctx xml call))))]
        (exit-node ctx graph call hit)))))

;; ===== 私有方法 =====

(defn- raw-method [type node]
  (java/method-for type (:method node) (:params node)))

(defn- type-set [ctx call]
  (let [types (remove nil? [(:receiver call) (:declaringType call)])]
    (set (mapcat #(cons % ((:supers ctx) %)) types))))

(defn- annotated-hits [ctx graph call]
  (let [owner (get-in ctx [:index :types (get-in call [:field :owner])])
        field-hits (when owner (core/annotation-matches ctx owner (get-in call [:field :annotations]) :field))
        target (get-in graph [:nodes (:target call)])
        type-hits (for [fqn (type-set ctx call)
                        :let [type (get-in ctx [:index :types fqn])] :when type
                        hit (concat (core/annotation-matches ctx type (:annotations type) :class)
                                    (when-let [method (raw-method type target)]
                                      (core/annotation-matches ctx type (:annotations method) :method)))] hit)]
    (filter #(= :exit (get-in % [:rule :role])) (concat field-hits type-hits))))

(defn- call-hits [ctx graph rules call]
  (let [types (type-set ctx call)
        direct (for [rule rules
                     :let [{:keys [type implements extends]} (:match rule)]
                     :when (some types (remove nil? [type implements extends]))]
                 {:rule rule :attrs {}})]
    (for [{:keys [rule attrs] :as hit} (concat (annotated-hits ctx graph call) direct)
          :when (or (nil? (:method rule)) (= (:method rule) (:method call)))]
      (assoc hit :sem (merge (core/rule-attributes rule attrs)
                            (into {} (for [[index semantic] (:args rule)
                                           :let [value (get (:args call) (parse-long index))] :when (some? value)]
                                       [semantic value])))))))

(defn- xml-declarations [ctx rules]
  (let [ws (:ws ctx)
        roots (xml/resource-roots (.toFile ^java.nio.file.Path (:root ws)) (get-in ws [:architecture :guidance :source-paths]))
        files (xml/xml-files roots)]
    (vec (for [rule rules :let [spec (get-in rule [:match :xml])] :when spec
               {:keys [file]} (xml/matching-files files (:files spec))
               :let [root (try (xml/parse-file file) (catch Exception _ nil))] :when root
               {:keys [chain declaration]} (xml/units root (:each spec))
               :let [fields (merge (:defaults rule) (into {} (for [[field path] (:fields spec)] [field (xml/field-value chain path)])))]]
           {:rule rule :sem (dissoc fields "bean") :bean (get fields "bean")
            :source {:kind "xml" :anchor {:path (workspace/relative ws file) :startLine (:start declaration) :endLine (:end declaration)}}}))))

(defn- bean-name [ctx call]
  (let [field (:field call)
        owner (get-in ctx [:index :types (:owner field)])
        annotations (for [ann (:annotations field)
                          :let [fqn (:fqn (java/annotation-type (:index ctx) owner ann))]
                          :when (#{"org.springframework.beans.factory.annotation.Qualifier" "javax.annotation.Resource" "jakarta.annotation.Resource"} fqn)]
                      (java/annotation-attrs (:index ctx) owner ann))]
    (or (some #(or (get % "name") (get % "value")) annotations) (:name field))))

(defn- xml-hits [ctx declarations call]
  (let [bean (bean-name ctx call)]
    (filter #(and bean (= bean (:bean %))
                  (or (nil? (get-in % [:rule :method])) (= (:method call) (get-in % [:rule :method])))) declarations)))

(defn- shown-attrs [sem]
  (into {} (for [[key value] sem :when (some? value)] [key (java/display-value value)])))

(defn- exit-label [kind shown call]
  (case kind
    :db (str "DB · " (or (get shown "name") (last (str/split (:receiver call) #"\."))))
    :mq (str "topic " (or (get shown "topic") "未解析"))
    (str "下游服务 " (or (get shown "service") (get shown "name") (:receiver call)))))

(defn- receiver-module [ctx fqn]
  (let [type (get-in ctx [:index :types fqn])
        top (or (last (:outers type)) fqn)
        modules (map key (get (:file-modules ctx) (:file type)))]
    (first (filter #(or (= top %) (str/ends-with? % (str "." top))) modules))))

(defn- exit-node [ctx graph call {:keys [rule sem source]}]
  (let [shown (shown-attrs sem)
        label (or (declared/fill-template (:label rule) shown) (exit-label (:kind rule) shown call))
        id (str "exit:" (:from call) ":" (get-in call [:anchor :startLine]) ":" (:receiver call) "#" (:method call) ":" label)]
    {:id id :kind "exit" :exitKind (name (:kind rule)) :label label
     :type (:receiver call) :method (:method call)
     :module (or (get-in graph [:nodes (:target call) :module]) (receiver-module ctx (:receiver call)))
     :anchor (:anchor call)
     :attrs shown :rule (str (when (= :builtin (:origin rule)) "builtin:") (:id rule))
     :warnings (vec (when (some java/unresolved? (vals sem)) ["unresolved-attr"]))
     :sources (vec (when source [source]))}))
