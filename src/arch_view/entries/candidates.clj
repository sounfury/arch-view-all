;; 职责：统计疑似入口但当前规则没覆盖的注解、外部父类型和 XML 元素，给出次数、示例位置、属性与取值分布，供 AI 挑样例写规则。
;; 核心入口：候选统计（candidates）；未归类入口统计（unclassified），供 AI 起草 domains。

(ns arch-view.entries.candidates
  (:require [arch-view.entries.domains :as domains]
            [arch-view.entries.java-source :as java]
            [arch-view.entries.xml-source :as xml]
            [arch-view.workspace :as workspace]
            [clojure.string :as str]))

(declare ^:private annotation-candidates ^:private super-candidates ^:private xml-candidates)

(defn candidates
  "ctx 为 entries.core/analysis-context 的结果；limit 限制注解与父类型合计条数。"
  [ctx limit]
  (let [annotations (annotation-candidates ctx)
        supers (super-candidates ctx)
        ranked (take limit (concat annotations supers))]
    {:annotations (vec (filter #(= "annotation" (:kind %)) ranked))
     :supertypes (vec (filter #(= "supertype" (:kind %)) ranked))
     :xml (vec (take 15 (xml-candidates ctx)))
     :total {:annotations (count annotations) :supertypes (count supers)}}))

(defn unclassified
  "默认分组来源下未归类的入口：HTTP 路径前两段与处理类包名各取前 10。"
  [{:keys [entries grouping]}]
  (let [source (:default grouping "inferred")
        missing (filter #(= [domains/unclassified] (domains/domains-of % source)) entries)
        top (fn [values] (->> (frequencies (remove str/blank? values)) (sort-by (juxt (comp - val) key)) (take 10)
                              (mapv (fn [[value n]] {:value value :count n}))))]
    {:source source :count (count missing)
     :paths (top (for [entry missing :when (= "http" (:kind entry))]
                   (str "/" (str/join "/" (take 2 (remove str/blank? (str/split (str (get-in entry [:attrs "path"])) #"/")))))))
     :packages (top (map #(str/join "." (butlast (str/split (str (get-in % [:handler :type])) #"\."))) missing))}))

;; ===== 私有方法 =====

(def ^:private ignored-prefixes
  ["java." "javax.annotation." "javax.validation." "javax.inject." "jakarta.annotation." "jakarta.validation."
   "jakarta.inject." "lombok." "com.fasterxml.jackson." "com.alibaba.fastjson" "org.slf4j." "io.swagger."
   "org.junit" "org.mockito" "org.jetbrains.annotations." "edu.umd.cs.findbugs." "org.hibernate.validator."
   "org.mapstruct." "org.springframework.beans.factory.annotation." "org.springframework.context.annotation."
   "org.springframework.stereotype." "org.springframework.transaction.annotation." "org.springframework.web.bind.annotation."
   "org.springframework.validation.annotation." "org.springframework.cache.annotation." "org.springframework.retry.annotation."
   "org.springframework.scheduling.annotation." "org.springframework.boot." "org.springframework.cloud.openfeign."])

(def ^:private ignored-super-prefixes
  ["java." "javax." "jakarta." "kotlin." "org.springframework.beans." "org.springframework.context."
   "org.springframework.core." "org.springframework.boot.autoconfigure."])

(defn- ignored? [prefixes fqn]
  (some #(str/starts-with? fqn %) prefixes))

(defn- rel [ctx file]
  (workspace/relative (:ws ctx) file))

(defn- consumed-annotations
  "规则直接匹配的注解，以及项目内定义、元注解已被规则匹配的注解。"
  [{:keys [index by-annotation]}]
  (let [direct (set (keys by-annotation))]
    (into direct
          (for [type (vals (:types index))
                :when (and (= :annotation (:kind type))
                           (some #(contains? direct (:fqn (java/annotation-type index type %))) (:annotations type)))]
            (:fqn type)))))

(defn- occurrences
  "全部类上与方法上的注解出现：{:fqn :level :type :ann :line}。"
  [index]
  (for [type (vals (:types index))
        :when (not= :annotation (:kind type))
        [level ann] (concat (map #(vector "class" %) (:annotations type))
                            (for [method (:methods type) ann (:annotations method)]
                              [(if (= :interface (:kind type)) "interfaceMethod" "method") ann]))
        :let [fqn (:fqn (java/annotation-type index type ann))]
        :when fqn]
    {:fqn fqn :level level :type type :ann ann}))

(defn- scalar-paths
  "属性求值后的标量路径：顶层属性和一层嵌套注解属性（如 bindings.bindingType）。"
  [attrs]
  (for [[key value] attrs
        item (if (sequential? value) value [value])
        [path leaf] (if (and (map? item) (:annotation item))
                      (for [[sub v] (:attrs item) leaf (if (sequential? v) v [v])] [(str key "." sub) leaf])
                      [[key item]])
        :when (or (string? leaf) (number? leaf) (boolean? leaf))
        :when (<= (count (str leaf)) 40)]
    [path (str leaf)]))

(defn- value-distribution [index items]
  (->> items
       (mapcat #(scalar-paths (java/annotation-attrs index (:type %) (:ann %))))
       (group-by first)
       (keep (fn [[path pairs]]
               (let [counts (frequencies (map second pairs))]
                 ;; 只列有重复取值的低基数属性，路径、名称这类各不相同的值没有分布意义。
                 (when (and (<= (count counts) 8) (< (count counts) (count pairs)))
                   {:path path :values (into (sorted-map) counts)}))))
       (sort-by :path) (take 3) vec))

(defn- with-annotations
  "方法级注解所在类上最常见的其它注解。"
  [index items]
  (->> items
       (filter #(not= "class" (:level %)))
       (map :type) distinct
       (mapcat (fn [type] (distinct (keep #(:fqn (java/annotation-type index type %)) (:annotations type)))))
       frequencies (sort-by (comp - val)) (take 2)
       (mapv (fn [[fqn n]] {:annotation fqn :count n}))))

(defn- sample [ctx {:keys [type ann]}]
  (str (rel ctx (:file type)) ":" (:line ann)))

(defn- annotation-summary [ctx [fqn items]]
  (let [index (:index ctx)
        levels (frequencies (map :level items))]
    {:kind "annotation" :fqn fqn :count (count items) :levels levels
     :sample (sample ctx (first (sort-by #(sample ctx %) items)))
     :attrs (vec (sort (distinct (mapcat #(map first (:args (:ann %))) items))))
     :values (value-distribution index items)
     :with (with-annotations index items)
     :method? (boolean (some #(not= "class" %) (keys levels)))}))

(defn- annotation-candidates [ctx]
  (let [consumed (consumed-annotations ctx)]
    (->> (occurrences (:index ctx))
         (remove #(or (contains? consumed (:fqn %)) (ignored? ignored-prefixes (:fqn %))))
         (group-by :fqn)
         (map #(annotation-summary ctx %))
         (sort-by (juxt #(if (:method? %) 0 1) (comp - :count) :fqn)))))

(defn- super-candidates
  "具体类直接声明、项目里没有源码的父类型。"
  [{:keys [index type-rules] :as ctx}]
  (let [consumed (set (keep #(or (get-in % [:match :implements]) (get-in % [:match :extends])) type-rules))]
    (->> (for [type (vals (:types index))
               :when (and (= :class (:kind type)) (not (:abstract? type)))
               super (:supers type)
               :when (not (or (get-in index [:types super]) (contains? consumed super)
                              (ignored? ignored-super-prefixes super)))]
           [super type])
         (group-by first)
         (map (fn [[fqn pairs]]
                (let [types (map second pairs)]
                  {:kind "supertype" :fqn fqn :count (count types)
                   :sample (first (sort (map #(str (rel ctx (:file %)) ":" (:start %)) types)))})))
         (sort-by (juxt (comp - :count) :fqn)))))

(def ^:private ignored-xml-prefixes #{"context" "aop" "tx" "util" "beans" "mvc" "p" "c" "jee" "lang"})

(defn- declarations
  "声明级元素：根元素的子元素，遇到不带前缀的容器（如 <beans profile>）继续向下。"
  [node]
  (mapcat #(if (str/includes? (:tag %) ":") [%] (declarations %)) (:children node)))

(defn- xml-candidates
  "资源目录 XML 中带命名空间前缀的元素次数，排除 Spring 基础命名空间和已被 xml 规则读取的元素。"
  [{:keys [ws rules] :as ctx}]
  (let [roots (xml/resource-roots (.toFile ^java.nio.file.Path (:root ws)) (get-in ws [:architecture :guidance :source-paths]))
        used (set (mapcat #(str/split (str (get-in % [:match :xml :each])) #"/") (filter #(get-in % [:match :xml]) rules)))]
    (->> (for [{:keys [file]} (xml/xml-files roots)
               :let [root (try (xml/parse-file file) (catch Exception _ nil))]
               :when root
               node (declarations root)
               :let [[prefix] (str/split (:tag node) #":" 2)]
               :when (and (str/includes? (:tag node) ":") (not (ignored-xml-prefixes prefix))
                          (not (contains? used (:tag node))))]
           [(:tag node) (str (rel ctx file) ":" (:start node))])
         (group-by first)
         (map (fn [[tag pairs]] {:element tag :count (count pairs) :sample (first (sort (map second pairs)))}))
         (sort-by (juxt (comp - :count) :element)))))
