(ns arch-view.input.java.dependency-extract
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arch-view.model.graph :as graph])
  (:import [com.sun.source.tree ClassTree CompilationUnitTree Tree$Kind]
           [com.sun.source.util JavacTask TreePath TreePathScanner Trees]
           [javax.lang.model.element Element TypeElement Modifier]
           [javax.tools ToolProvider Diagnostic Diagnostic$Kind DiagnosticCollector
            JavaCompiler StandardJavaFileManager StandardLocation]
           [java.nio.charset StandardCharsets]))

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

(defn- declarations [units]
  (for [^CompilationUnitTree unit units
        tree (.getTypeDecls unit)
        :when (instance? ClassTree tree)
        :let [^ClassTree tree tree
              package (str (.getPackageName unit))]]
    {:name (str (when (seq package) (str package ".")) (.getSimpleName tree))
     :unit unit :tree tree
     :file (.getCanonicalPath (io/file (.toUri (.getSourceFile unit))))}))

(defn- source-index [types]
  (reduce (fn [index {:keys [name file]}]
            (when (contains? index name)
              (throw (ex-info (str "Duplicate Java type " name ": " (get index name) " and " file)
                              {:module name :files [(get index name) file]})))
            (assoc index name file))
          {} types))

(defn- top-level-type [^Element element]
  (loop [element element owner nil]
    (if (nil? element)
      (when owner (str (.getQualifiedName ^TypeElement owner)))
      (recur (.getEnclosingElement ^Element element)
             (if (instance? TypeElement element) element owner)))))

(defn- type-edges [^Trees trees nodes {:keys [name ^CompilationUnitTree unit ^ClassTree tree]}]
  (let [edges (atom #{})
        record-reference (fn [^TreePathScanner scanner]
                           (let [target (top-level-type (.getElement trees (.getCurrentPath scanner)))]
                             (when (and (nodes target) (not= name target))
                               (swap! edges conj {:from name :to target}))))
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

(defn- analyze-files [^JavaCompiler compiler files]
  (let [diagnostics (DiagnosticCollector.)]
    (with-open [^StandardJavaFileManager manager
                (.getStandardFileManager compiler diagnostics nil StandardCharsets/UTF_8)]
      ;; Keep the viewer's classpath and implicitly discovered source files out of analysis.
      (.setLocation manager StandardLocation/CLASS_PATH [])
      (.setLocation manager StandardLocation/SOURCE_PATH [])
      (let [^JavacTask task (.getTask compiler nil manager diagnostics
                                     ["-proc:none" "-implicit:none" "-Xlint:none"] nil
                                     (.getJavaFileObjectsFromFiles manager files))
            units (vec (.parse task))
            _ (check-syntax! diagnostics)
            types (vec (declarations units))
            index (source-index types)
            nodes (set (keys index))
            ;; Resolve symbols without generating classes or running annotation processors.
            ;; Missing third-party dependencies are allowed; only internal symbols become edges.
            _ (.analyze task)
            trees (Trees/instance task)]
        (merge (graph/make-graph nodes (mapcat #(type-edges trees nodes %) types))
               {:abstract-modules (set (map :name (filter abstract-type? types)))
                :module->source-file index})))))

(defn build-module-graph [project-path source-paths]
  (let [compiler (ToolProvider/getSystemJavaCompiler)
        files (source-files project-path source-paths)]
    (when-not compiler
      (throw (ex-info "Java analysis requires a full JDK (jdk.compiler); run the viewer with a JDK, not a JRE."
                      {:language :java})))
    (if (seq files)
      (analyze-files compiler files)
      (merge (graph/make-graph #{} #{}) {:abstract-modules #{} :module->source-file {}}))))
