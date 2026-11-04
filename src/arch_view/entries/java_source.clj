;; 职责：只做语法解析，把 Java 源码整理成入口识别所需的类型、注解、方法和常量；按导入规则解析类型全名，并静态求值注解属性。
;; 核心入口：解析源码（scan-files）；类型全名（resolve-type）；注解属性（annotation-attrs）；所有父类型（all-supers）。

(ns arch-view.entries.java-source
  (:require [arch-view.web.javadoc :as javadoc]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.sun.source.tree AnnotatedTypeTree AnnotationTree AssignmentTree BinaryTree BlockTree ClassTree
            CompilationUnitTree IdentifierTree ImportTree LiteralTree MemberSelectTree MethodTree
            NewArrayTree ParameterizedTypeTree ParenthesizedTree ReturnTree Tree Tree$Kind VariableTree]
           [com.sun.source.util DocTrees JavacTask SourcePositions TreePath Trees]
           [javax.lang.model.element Modifier]
           [javax.tools DiagnosticCollector JavaCompiler StandardJavaFileManager StandardLocation ToolProvider]
           [java.nio.charset StandardCharsets]))

(declare ^:private parse-units ^:private unit-types ^:private resolve-simple ^:private eval-expr
         ^:private build-index)

(defn scan-files
  "解析 Java 源码，返回 {:types {全名 类型} :packages {包 #{简单名}}}；不做类型推导，也不执行目标代码。"
  [files]
  (if (empty? files)
    {:types {} :packages {}}
    (let [{:keys [units positions docs]} (parse-units files)]
      (build-index (mapcat #(unit-types positions docs %) units)))))

(defn resolve-type
  "按源码写法解析类型全名：单类型导入、同文件类型、同包类型、java.lang 常用类型、通配导入，最后回退到同包。
  返回 {:fqn 全名}；多个通配导入都可能时返回 {:candidates [...]}。"
  [index ctx type-name]
  (let [[head & more] (str/split (str type-name) #"\.")]
    (cond
      (empty? more) (resolve-simple index ctx head)
      (Character/isUpperCase ^char (first head))
      (let [base (resolve-simple index ctx head)]
        (if (:fqn base) {:fqn (str/join "." (cons (:fqn base) more))} {:fqn type-name}))
      :else {:fqn type-name})))

(defn annotation-type
  "注解在源码中的类型解析结果。"
  [index {:keys [ctx]} {:keys [name]}]
  (resolve-type index ctx name))

(defn annotation-attrs
  "静态求值注解参数，返回 {属性名 值}；省略属性名时为 value。"
  [index type {:keys [args]}]
  (let [env {:type (:fqn type) :seen #{}}]
    (into {} (map (fn [[attr tree]] [attr (eval-expr index env tree)])) args)))

(defn eval-tree
  "在类型 type 的作用域内静态求值表达式树，规则同注解属性。"
  [index type tree]
  (eval-expr index {:type (:fqn type) :seen #{}} tree))

(defn all-supers
  "项目内沿父类和接口向上收集的全部父类型全名（含外部 jar 类型，外部类型不再向上展开）。"
  [index fqn]
  (loop [pending (vec (get-in index [:types fqn :supers])) seen #{}]
    (if-let [current (first pending)]
      (if (contains? seen current)
        (recur (subvec pending 1) seen)
        (recur (into (subvec pending 1) (get-in index [:types current :supers])) (conj seen current)))
      seen)))

(defn method-for
  "按参数类型匹配原始方法元数据；仅在同名同参数数只有一个候选时允许退回计数匹配。"
  [type method params]
  (let [simple #(last (str/split (str/replace (str %) "..." "[]") #"\."))
        candidates (filter #(and (= method (:name %)) (= (count params) (count (:params %)))) (:methods type))
        exact (filter #(= (mapv simple params) (mapv simple (:params %))) candidates)]
    (cond (= 1 (count exact)) (first exact)
          (= 1 (count candidates)) (first candidates))))

(defn unresolved? [value]
  (and (map? value) (contains? value :unresolved)))

(defn display-value
  "把求值结果转成展示用文本：类字面量取全名，解析不了的保留源码文本。"
  [value]
  (cond
    (unresolved? value) (:unresolved value)
    (and (map? value) (:class value)) (:class value)
    (and (map? value) (:annotation value)) (str "@" (get-in value [:annotation :fqn]))
    (sequential? value) (mapv display-value value)
    :else value))

;; ===== 私有方法 =====

(def ^:private java-lang
  #{"Override" "Deprecated" "SuppressWarnings" "FunctionalInterface" "SafeVarargs"
    "String" "Object" "Integer" "Long" "Boolean" "Double" "Runnable" "Exception"})

(defn- parse-units [files]
  (let [^JavaCompiler compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)]
    (when-not compiler
      (throw (ex-info "入口识别需要完整 JDK（jdk.compiler），请用 JDK 而不是 JRE 运行。" {:language :java})))
    (with-open [^StandardJavaFileManager manager
                (.getStandardFileManager compiler diagnostics nil StandardCharsets/UTF_8)]
      (.setLocation manager StandardLocation/CLASS_PATH [])
      (.setLocation manager StandardLocation/SOURCE_PATH [])
      (let [^JavacTask task (.getTask compiler nil manager diagnostics
                                      ["-proc:none" "-implicit:none" "-Xlint:none"] nil
                                      (.getJavaFileObjectsFromFiles manager (map io/file files)))
            units (vec (.parse task))]
        {:units units :positions (.getSourcePositions (Trees/instance task)) :docs (DocTrees/instance task)}))))

(defn- last-segment [name]
  (subs name (inc (str/last-index-of name "."))))

(defn- parent-name [name]
  (if-let [index (str/last-index-of name ".")] (subs name 0 index) ""))

(defn- qualify [package name]
  (if (str/blank? package) name (str package "." name)))

(defn- add-import [ctx ^ImportTree tree]
  (let [name (str (.getQualifiedIdentifier tree))
        wildcard? (str/ends-with? name ".*")
        base (if wildcard? (subs name 0 (- (count name) 2)) name)]
    (case [(.isStatic tree) wildcard?]
      [true true] (update ctx :static-wildcards conj base)
      [true false] (assoc-in ctx [:static-imports (last-segment base)] (parent-name base))
      [false true] (update ctx :wildcards conj base)
      [false false] (assoc-in ctx [:imports (last-segment base)] base))))

(defn- import-context [^CompilationUnitTree unit]
  (reduce add-import
          {:package (str (.getPackageName unit)) :imports {} :wildcards []
           :static-imports {} :static-wildcards []}
          (.getImports unit)))

(defn- type-name
  "类型树的写法，去掉泛型参数和类型注解。"
  [^Tree tree]
  (cond
    (instance? ParameterizedTypeTree tree) (type-name (.getType ^ParameterizedTypeTree tree))
    (instance? AnnotatedTypeTree tree) (type-name (.getUnderlyingType ^AnnotatedTypeTree tree))
    :else (str tree)))

(defn- raw-annotation [line ^AnnotationTree tree]
  {:name (type-name (.getAnnotationType tree))
   :line (line tree)
   :args (mapv (fn [arg]
                 (if (instance? AssignmentTree arg)
                   [(str (.getVariable ^AssignmentTree arg)) (.getExpression ^AssignmentTree arg)]
                   ["value" arg]))
               (.getArguments tree))})

(defn- annotations [line modifiers]
  (mapv #(raw-annotation line %) (.getAnnotations modifiers)))

(defn- type-kind [^ClassTree tree]
  (condp = (.getKind tree)
    Tree$Kind/INTERFACE :interface
    Tree$Kind/ANNOTATION_TYPE :annotation
    Tree$Kind/ENUM :enum
    :class))

(defn- param-type [^VariableTree param]
  (let [written (str (.getType param))
        base (first (str/split written #"<"))]
    (str (last-segment (str "." base)) (when (str/includes? written "...") "[]"))))

(defn- single-return
  "方法体只有一条 return 语句时的返回表达式及其行号。"
  [line ^MethodTree tree]
  (let [^BlockTree body (.getBody tree)
        statements (when body (.getStatements body))]
    (when (and (= 1 (count statements)) (instance? ReturnTree (first statements)))
      (let [^ReturnTree statement (first statements)]
        (when (.getExpression statement) {:tree (.getExpression statement) :line (line statement)})))))

(defn- method-data [line interface? ^MethodTree tree]
  (let [flags (set (.getFlags (.getModifiers tree)))
        anns (annotations line (.getModifiers tree))]
    {:name (str (.getName tree))
     :params (mapv param-type (.getParameters tree))
     :public? (or interface? (contains? flags Modifier/PUBLIC))
     :static? (contains? flags Modifier/STATIC)
     :abstract? (nil? (.getBody tree))
     :void? (= "void" (str (.getReturnType tree)))
     :override? (boolean (some #(#{"Override" "java.lang.Override"} (:name %)) anns))
     :annotations anns
     :return (single-return line tree)
     :start (line tree) :end (line tree :end)}))

(defn- constant? [interface? ^VariableTree tree]
  (let [flags (set (.getFlags (.getModifiers tree)))]
    (and (.getInitializer tree)
         (or interface? (and (contains? flags Modifier/STATIC) (contains? flags Modifier/FINAL))))))

(defn- class-members [line doc interface? ^ClassTree tree]
  (reduce (fn [acc member]
            (cond
              (and (instance? MethodTree member) (not= "<init>" (str (.getName ^MethodTree member))))
              (update acc :methods conj (assoc (method-data line interface? member) :doc (doc member)))
              (instance? VariableTree member)
              (let [^VariableTree field member
                    field-name (str (.getName field))
                    acc (assoc-in acc [:fields field-name]
                                  {:name field-name :type-name (type-name (.getType field))
                                   :annotations (annotations line (.getModifiers field))})]
                (if (constant? interface? field)
                  (assoc-in acc [:constants field-name] (.getInitializer field)) acc))
              :else acc))
          {:methods [] :fields {} :constants {}}
          (.getMembers tree)))

(defn- class-data [{:keys [line doc]} file ctx outers fqn ^ClassTree tree]
  (let [kind (type-kind tree)
        flags (set (.getFlags (.getModifiers tree)))
        members (class-members line doc (boolean (#{:interface :annotation} kind)) tree)]
    (assoc members :doc (doc tree)
           :fqn fqn :simple (str (.getSimpleName tree)) :package (:package ctx) :file file
           :kind kind :abstract? (or (contains? flags Modifier/ABSTRACT) (not= :class kind))
           :annotations (annotations line (.getModifiers tree))
           :super-names (mapv type-name (remove nil? (cons (.getExtendsClause tree) (.getImplementsClause tree))))
           :ctx ctx :outers outers :start (line tree) :end (line tree :end))))

(defn- collect-classes
  "展开顶层类型和嵌套类型，返回 [全名 外层全名列表 树] 序列。"
  [package ^CompilationUnitTree unit]
  (letfn [(walk [outers fqn ^ClassTree tree]
            (cons [fqn outers tree]
                  (mapcat (fn [member]
                            (when (instance? ClassTree member)
                              (walk (cons fqn outers) (str fqn "." (.getSimpleName ^ClassTree member)) member)))
                          (.getMembers tree))))]
    (mapcat #(when (instance? ClassTree %) (walk () (qualify package (str (.getSimpleName ^ClassTree %))) %))
            (.getTypeDecls unit))))

(defn- line-fn [^SourcePositions positions ^CompilationUnitTree unit]
  (let [lines (.getLineMap unit)]
    (fn line
      ([tree] (line tree :start))
      ([tree edge]
       (let [pos (if (= edge :end) (.getEndPosition positions unit tree) (.getStartPosition positions unit tree))]
         (when (>= pos 0) (.getLineNumber lines pos)))))))

(defn- doc-fn [^DocTrees docs ^CompilationUnitTree unit]
  (fn [tree]
    (when-let [path (TreePath/getPath unit ^Tree tree)]
      (javadoc/summary (.getDocComment docs path)))))

(defn- unit-types [positions docs ^CompilationUnitTree unit]
  (let [base (import-context unit)
        classes (collect-classes (:package base) unit)
        ctx (assoc base :local-types (into {} (map (fn [[fqn]] [(last-segment fqn) fqn])) classes))
        file (.getCanonicalPath (io/file (.toUri (.getSourceFile unit))))
        env {:line (line-fn positions unit) :doc (doc-fn docs unit)}]
    (map (fn [[fqn outers tree]] (class-data env file ctx (vec outers) fqn tree)) classes)))

(defn- build-index [types]
  (let [index {:types (into {} (map (juxt :fqn identity)) types)
               :packages (reduce (fn [acc {:keys [package simple outers]}]
                                   (if (empty? outers) (update acc package (fnil conj #{}) simple) acc))
                                 {} types)}]
    (update index :types update-vals
            (fn [type] (assoc type :supers (vec (keep #(:fqn (resolve-type index (:ctx type) %)) (:super-names type))))))))

(defn- wildcard-type [index ctx simple]
  (let [candidates (mapv #(str % "." simple) (:wildcards ctx))
        known (filter #(contains? (:types index) %) candidates)]
    (cond
      (empty? candidates) nil
      (= 1 (count known)) {:fqn (first known)}
      (= 1 (count candidates)) {:fqn (first candidates)}
      :else {:candidates candidates})))

(defn- resolve-simple [index ctx simple]
  (or (some->> (get-in ctx [:imports simple]) (hash-map :fqn))
      (some->> (get-in ctx [:local-types simple]) (hash-map :fqn))
      (when (contains? (get-in index [:packages (:package ctx)]) simple) {:fqn (qualify (:package ctx) simple)})
      (when (contains? java-lang simple) {:fqn (str "java.lang." simple)})
      (wildcard-type index ctx simple)
      {:fqn (qualify (:package ctx) simple)}))

(defn- unresolved [tree name]
  {:unresolved (str tree) :name name})

(defn- constant-value [index fqn field seen]
  (when-let [tree (get-in index [:types fqn :constants field])]
    (if (contains? seen [fqn field])
      (unresolved field field)
      (eval-expr index {:type fqn :seen (conj seen [fqn field])} tree))))

(defn- scopes
  "标识符查找范围：当前类型、外层类型、项目内父类型。"
  [index fqn]
  (let [type (get-in index [:types fqn])]
    (distinct (concat [fqn] (:outers type) (all-supers index fqn)))))

(defn- static-owners [ctx field]
  (concat (some-> (get-in ctx [:static-imports field]) vector) (:static-wildcards ctx)))

(defn- identifier-value [index {:keys [type seen]} ^IdentifierTree tree]
  (let [field (str (.getName tree))
        ctx (get-in index [:types type :ctx])
        owners (concat (scopes index type) (static-owners ctx field))]
    (or (some #(constant-value index % field seen) owners)
        (unresolved tree field))))

(defn- member-value [index {:keys [type seen]} ^MemberSelectTree tree]
  (let [field (str (.getIdentifier tree))
        ctx (get-in index [:types type :ctx])
        owner (resolve-type index ctx (str (.getExpression tree)))]
    (if (= "class" field)
      {:class (or (:fqn owner) (str (.getExpression tree)))}
      (let [value (some-> (:fqn owner) (#(constant-value index % field seen)))]
        (if (or (nil? value) (unresolved? value)) (unresolved tree field) value)))))

(defn- concat-value [index env ^BinaryTree tree]
  (let [left (eval-expr index env (.getLeftOperand tree))
        right (eval-expr index env (.getRightOperand tree))]
    (cond
      (or (coll? left) (coll? right)) (unresolved tree nil)
      (and (number? left) (number? right)) (+ left right)
      :else (str left right))))

(defn- literal-value [^LiteralTree tree]
  (let [value (.getValue tree)]
    (if (char? value) (str value) value)))

(defn- eval-expr
  "静态求值：字面量、数组、嵌套注解、类字面量、项目内常量及其拼接；其余保留源码文本。"
  [index env ^Tree tree]
  (cond
    (instance? LiteralTree tree) (literal-value tree)
    (instance? NewArrayTree tree) (mapv #(eval-expr index env %) (or (.getInitializers ^NewArrayTree tree) []))
    (instance? ParenthesizedTree tree) (eval-expr index env (.getExpression ^ParenthesizedTree tree))
    (instance? AnnotationTree tree)
    (let [raw (raw-annotation (constantly nil) tree)
          owner (get-in index [:types (:type env)])]
      {:annotation (annotation-type index owner raw) :attrs (annotation-attrs index owner raw)})
    (and (instance? BinaryTree tree) (= Tree$Kind/PLUS (.getKind tree))) (concat-value index env tree)
    (instance? MemberSelectTree tree) (member-value index env tree)
    (instance? IdentifierTree tree) (identifier-value index env tree)
    :else (unresolved tree nil)))
