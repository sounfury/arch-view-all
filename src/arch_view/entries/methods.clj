;; 职责：按规则的 methods 写法从处理类中选出入口方法：实现的接口方法、指定接口、带某注解的方法或公有方法。
;; 核心入口：选择入口方法（select-methods）；方法签名键（method-key）。

(ns arch-view.entries.methods
  (:require [arch-view.entries.java-source :as java]))

(declare ^:private interface-methods ^:private annotated-methods)

(defn method-key [method]
  [(:name method) (count (:params method))])

(defn public-methods [type]
  (filter #(and (:public? %) (not (:static? %)) (not (:abstract? %))) (:methods type)))

(defn select-methods
  "类级规则的入口方法；未写 methods 时取实现的接口方法，没有接口则取公有方法。
  ctx 需含 :index 与 :supers（全名 → 全部父类型）。"
  [ctx type methods attrs]
  (let [[k v] (when (map? methods) (first methods))]
    (cond
      (= "public" methods) (public-methods type)
      (= "interfaceFromAttr" k) (interface-methods ctx type (let [value (get attrs v)]
                                                              (when (string? (:class value)) (:class value))))
      (= "implementsOf" k) (interface-methods ctx type v)
      (= "annotation" k) (annotated-methods ctx type v)
      :else (or (seq (interface-methods ctx type nil)) (public-methods type)))))

;; ===== 私有方法 =====

(def ^:private object-methods #{"toString" "equals" "hashCode" "clone" "finalize"})

(defn- interface-keys [ctx fqn]
  (let [types (get-in ctx [:index :types])]
    (set (for [t (cons fqn ((:supers ctx) fqn))
               :let [type (get types t)]
               :when (= :interface (:kind type))
               method (:methods type)]
           (method-key method)))))

(defn- override-methods [type]
  (filter #(and (:override? %) (:public? %) (not (:static? %)) (not (object-methods (:name %)))) (:methods type)))

(defn- interface-methods
  "实现的项目内接口声明的方法，加上 @Override 的公有方法（覆盖外部 jar 接口）；only 限定某个接口。"
  [ctx type only]
  (let [keys (interface-keys ctx (or only (:fqn type)))
        external? (and only (not (get-in ctx [:index :types only])))]
    (distinct (concat (filter #(and (contains? keys (method-key %)) (not (:static? %))) (:methods type))
                      (when (or external? (nil? only)) (override-methods type))))))

(defn- annotated-methods [ctx type annotation]
  (filter (fn [method] (some #(= annotation (:fqn (java/annotation-type (:index ctx) type %))) (:annotations method)))
          (:methods type)))
