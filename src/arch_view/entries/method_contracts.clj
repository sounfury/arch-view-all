;; 职责：按完整参数类型与泛型替换匹配源码方法契约，保留文档来源及独立的契约/实现锚点。
(ns arch-view.entries.method-contracts
  (:require [arch-view.entries.java-source :as java]
            [arch-view.workspace :as workspace]
            [clojure.string :as str]))

(defn declaration-key [type method]
  [(:fqn type) (:name method) (or (:signature-params method) (:params method))])

(defn valid-doc [text]
  (when (and (string? text) (not (str/blank? text))) text))

(def ^:private primitives #{"boolean" "byte" "char" "short" "int" "long" "float" "double" "void"})

(defn- canonical [index type bindings written]
  (let [written (str/replace (str written) "..." "[]")
        base (str/replace written #"(?:\[\])+$" "")
        suffix (subs written (count base))
        name (if (contains? bindings base) (get bindings base)
                 (or (when (primitives base) base)
                     (:fqn (java/resolve-type index (:ctx type) base))))]
    (when name (str name suffix))))

(defn- bounds [index type bindings parameters]
  (let [initial (reduce #(assoc %1 (:name %2) nil) bindings parameters)
        resolve-bounds (fn [found]
                         (reduce (fn [found {:keys [name bound]}]
                                   (assoc found name (canonical index type found bound))) found parameters))]
    ;; 先建立全部变量作用域，再逐轮解析前向界限；未知/循环界限保持未知，不能误当成同名类。
    (nth (iterate resolve-bounds initial) (inc (count parameters)))))

(defn- signature [index type method bindings]
  (let [bindings (bounds index type bindings (:type-parameters method))]
    (mapv #(canonical index type bindings %) (or (:signature-params method) (:params method)))))

(defn parameters [index type method]
  (signature index type method (bounds index type {} (:type-parameters type))))

(defn inherited-methods
  "源码阶段不建立方法图；按全名、数组、泛型实参替换及可见性匹配，不用同名同参数数猜测重载。"
  [index type method]
  (when-not (or (:static? method) (:private? method))
    (let [initial (bounds index type {} (:type-parameters type))
          wanted (signature index type method initial)]
      (when (every? some? wanted)
        (loop [pending [[type initial #{}]] found []]
          (if-let [[current bindings seen] (first pending)]
            (let [parents (for [{:keys [name args]} (:super-specs current)
                                :let [fqn (:fqn (java/resolve-type index (:ctx current) name))
                                      parent (get-in index [:types fqn])]
                                :when (and parent (not (seen fqn)))
                                :let [defaults (bounds index parent {} (:type-parameters parent))
                                      arguments (map #(canonical index current bindings %) args)
                                      substituted (merge defaults (zipmap (map :name (:type-parameters parent)) arguments))]]
                            [parent substituted (conj seen fqn)])
                  matches (for [[parent bindings] parents
                                candidate (:methods parent)
                                :when (and (= (:name method) (:name candidate))
                                           (not (:static? candidate)) (not (:private? candidate)) (not (:final? candidate))
                                           (or (:public? candidate) (:protected? candidate)
                                               (= (:package type) (:package parent)))
                                           (= wanted (signature index parent candidate bindings)))]
                            {:type parent :method candidate})]
              (recur (into (vec (rest pending)) parents) (into found matches)))
            (vec (vals (into (array-map) (map (fn [{:keys [type method] :as match}]
                                                             [(declaration-key type method) match])) found)))))))))

(defn source [ws type method kind]
  {:kind kind :declarationKind (name (:kind type)) :type (:fqn type) :method (:name method)
   :params (or (:signature-params method) (:params method))
   :anchor {:path (workspace/relative ws (:file type)) :startLine (:start (or method type)) :endLine (:end (or method type))
            :symbol (str (:simple type) (when method (str "#" (:name method))))}})

(defn- documented [index matches]
  (let [matches (filter #(valid-doc (get-in % [:method :doc])) matches)]
    (vec (remove (fn [candidate]
                   (some #(and (not= (get-in candidate [:type :fqn]) (get-in % [:type :fqn]))
                               (contains? (java/all-supers index (get-in % [:type :fqn]))
                                          (get-in candidate [:type :fqn]))) matches)) matches))))

(defn describe
  "实现自身 Javadoc 优先；入口优先使用实际命中的契约，普通方法只接受唯一/一致的最具体说明。
  多个不一致接口说明保留候选并标记 ambiguous，不按父类型名称任取一个。"
  [ws index type method inherited preferred]
  (let [own (valid-doc (:doc method))
        chosen (if preferred
                 (cons preferred (inherited-methods index (:type preferred) (:method preferred))) inherited)
        docs (documented index chosen)
        texts (distinct (map #(get-in % [:method :doc]) docs))
        sources (mapv #(source ws (:type %) (:method %) "javadoc") docs)
        implementation (source ws type method "javadoc")
        contract (or preferred (when (= 1 (count docs)) (first docs)))]
    {:summary (or own (when (= 1 (count texts)) (first texts)))
     :docSource (if own implementation (when (= 1 (count sources)) (first sources)))
     :docStatus (cond own (if (= :interface (:kind type)) "declaration" "implementation")
                      (> (count texts) 1) "ambiguous" (seq texts) "inherited" :else "missing")
     :docCandidates (if own [] sources)
     :contractSource (when contract (source ws (:type contract) (:method contract) "contract"))
     :contractAnchor (when contract (:anchor (source ws (:type contract) (:method contract) "contract")))
     :implementationAnchor (when-not (:abstract? method) (:anchor implementation))}))
