;; 职责：读取 Java 类型声明和实际引用，按可选构建模块区分节点及重名类型。
;; 核心入口：构建模块关系图（build-module-graph）；不执行目标代码。

(ns arch-view.input.java.dependency-extract
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arch-view.input.build-modules :as build-modules]
            [arch-view.model.graph :as graph])
  (:import [com.sun.source.tree ClassTree CompilationUnitTree Tree$Kind]
           [com.sun.source.util JavacTask TreePath TreePathScanner Trees]
           [javax.lang.model.element Element TypeElement Modifier]
           [javax.tools ToolProvider Diagnostic Diagnostic$Kind DiagnosticCollector
            JavaCompiler StandardJavaFileManager StandardLocation]
           [java.nio.charset StandardCharsets]))

(declare ^:private source-files ^:private analyze-files)

(defn build-module-graph
  ([project-path source-paths] (build-module-graph project-path source-paths nil))
  ([project-path source-paths modules]
   (let [compiler (ToolProvider/getSystemJavaCompiler)
         files (source-files project-path source-paths)]
     (when-not compiler
       (throw (ex-info "Java analysis requires a full JDK (jdk.compiler); run the viewer with a JDK, not a JRE."
                       {:language :java})))
     (if (seq files)
       (analyze-files compiler files (build-modules/source-owner project-path modules))
       (merge (graph/make-graph #{} #{}) {:abstract-modules #{} :module->source-file {}})))))

;; ===== 私有方法 =====

(def ^:private ignored-directories
  #{"target" "build" "out" "dist" "node_modules"})

(defn- source-files [project-path source-paths]
  (let [children (fn [^java.io.File dir]
                   (remove #(or (java.nio.file.Files/isSymbolicLink (.toPath ^java.io.File %))
                                (.startsWith (.getName ^java.io.File %) ".")
                                (and (.isDirectory ^java.io.File %)
                                     (ignored-directories (.getName ^java.io.File %))))
                           (or (.listFiles dir) [])))]
    (->> source-paths
         (map #(let [file (io/file %)]
                 (if (.isAbsolute file) file (io/file project-path %))))
         (filter #(.exists ^java.io.File %))
         (mapcat #(tree-seq (fn [^java.io.File f] (.isDirectory f)) children %))
         (filter #(and (.isFile ^java.io.File %)
                       (.endsWith (.getName ^java.io.File %) ".java")
                       (not (#{"module-info.java" "package-info.java"} (.getName ^java.io.File %)))))
         (map #(.getCanonicalFile ^java.io.File %))
         distinct
         (sort-by str)
         vec)))

(defn- declarations [units source-owner]
  (for [^CompilationUnitTree unit units
        tree (.getTypeDecls unit)
        :when (instance? ClassTree tree)
        :let [^ClassTree tree tree
              package (str (.getPackageName unit))
              file (.getCanonicalPath (io/file (.toUri (.getSourceFile unit))))
              name (str (when (seq package) (str package ".")) (.getSimpleName tree))
              owner (source-owner file)]]
    {:name name :id (build-modules/node-id owner name) :owner owner
     :unit unit :tree tree :file file}))

(defn- source-index [types]
  (reduce (fn [index {:keys [id file]}]
            (when (contains? index id)
              (throw (ex-info (str "Duplicate Java type " id ": " (get index id) " and " file)
                              {:module id :files [(get index id) file]})))
            (assoc index id file))
          {} types))

(defn- top-level-type [^Element element]
  (loop [element element owner nil]
    (if (nil? element)
      (when owner (str (.getQualifiedName ^TypeElement owner)))
      (recur (.getEnclosingElement ^Element element)
             (if (instance? TypeElement element) element owner)))))

(defn- type-edges [^Trees trees candidates node->owner {:keys [id owner ^CompilationUnitTree unit ^ClassTree tree]}]
  (let [edges (atom #{})
        record-reference (fn [^TreePathScanner scanner]
                           (let [target (top-level-type (.getElement trees (.getCurrentPath scanner)))]
                             (doseq [node (build-modules/preferred-candidates node->owner owner (get candidates target))
                                     :when (not= id node)]
                               (swap! edges conj {:from id :to node}))))
        scanner (proxy [TreePathScanner] []
                  (visitIdentifier [node data]
                    (record-reference this)
                    (proxy-super visitIdentifier node data))
                  (visitMemberSelect [node data]
                    (record-reference this)
                    (proxy-super visitMemberSelect node data))
                  (visitMemberReference [node data]
                    (record-reference this)
                    (proxy-super visitMemberReference node data)))]
    (.scan ^TreePathScanner scanner (TreePath/getPath unit tree) nil)
    @edges))

(defn- abstract-type? [{:keys [^ClassTree tree]}]
  (or (#{Tree$Kind/INTERFACE Tree$Kind/ANNOTATION_TYPE} (.getKind tree))
      (contains? (set (.getFlags (.getModifiers tree))) Modifier/ABSTRACT)))

(defn- check-syntax! [^DiagnosticCollector diagnostics]
  (let [errors (filter #(= Diagnostic$Kind/ERROR (.getKind ^Diagnostic %))
                       (.getDiagnostics diagnostics))]
    (when (seq errors)
      (throw (ex-info (str "Java syntax analysis failed:\n" (str/join "\n" (map str errors)))
                      {:diagnostics (mapv str errors)})))))

(defn- analyze-files [^JavaCompiler compiler files source-owner]
  (let [diagnostics (DiagnosticCollector.)]
    (with-open [^StandardJavaFileManager manager
                (.getStandardFileManager compiler diagnostics nil StandardCharsets/UTF_8)]
      ;; 隔离查看器自身的类路径和隐式发现的源码。
      (.setLocation manager StandardLocation/CLASS_PATH [])
      (.setLocation manager StandardLocation/SOURCE_PATH [])
      (let [^JavacTask task (.getTask compiler nil manager diagnostics
                                     ["-proc:none" "-implicit:none" "-Xlint:none"] nil
                                     (.getJavaFileObjectsFromFiles manager files))
            units (vec (.parse task))
            _ (check-syntax! diagnostics)
            types (vec (declarations units source-owner))
            index (source-index types)
            candidates (update-vals (group-by :name types) #(mapv :id %))
            node->owner (into {} (map (juxt :id :owner) types))
            nodes (set (keys index))
            ;; 一次解析所有模块；只在 parse 后检查语法错误。
            ;; analyze 的重复类和缺失外部依赖诊断允许存在，引用歧义由候选筛选处理。
            _ (.analyze task)
            trees (Trees/instance task)]
        (merge (graph/make-graph nodes (mapcat #(type-edges trees candidates node->owner %) types))
               {:abstract-modules (set (map :id (filter abstract-type? types)))
                :module->source-file index})))))
