;; 职责：分析源码（Kotlin）中的包、声明和引用，生成文件之间的依赖关系。
;; 核心入口：构建模块关系图（build-module-graph）；分析过程中不编译或运行目标项目。

(ns arch-view.input.kotlin.dependency-extract
  "Kotlin PSI syntax analysis without compiling or executing the target project."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [arch-view.model.graph :as graph])
  (:import [org.jetbrains.kotlin.cli.jvm.compiler KotlinCoreEnvironment EnvironmentConfigFiles]
           [org.jetbrains.kotlin.config CompilerConfiguration]
           [org.jetbrains.kotlin.com.intellij.openapi.util Disposer]
           [org.jetbrains.kotlin.com.intellij.psi PsiErrorElement]
           [org.jetbrains.kotlin.com.intellij.psi.util PsiTreeUtil]
           [org.jetbrains.kotlin.lexer KtTokens]
           [org.jetbrains.kotlin.psi KtPsiFactory KtFile KtClass KtObjectDeclaration
            KtNamedDeclaration KtNameReferenceExpression KtUserType KtDotQualifiedExpression
            KtCallExpression KtPackageDirective KtImportDirective KtDeclarationWithBody
            KtClassOrObject KtBlockExpression KtTypeParameterListOwner KtTypeParameter
            KtTypeAlias KtForExpression KtCatchClause KtDestructuringDeclaration]))

(def ^:private ignored-directories
  #{"build" "out" "target" "dist" "node_modules" "vendor" "buildSrc"})

(defn- kotlin-file? [file]
  (and (.isFile file)
       (re-find #"\.(kt|kts)$" (.getName file))
       (not (contains? #{"build.gradle.kts" "settings.gradle.kts"} (.getName file)))))

(defn- discover-files [project-path source-paths]
  (->> source-paths
       (mapcat (fn [path]
                 (let [root (.getCanonicalFile (io/file project-path path))]
                   (when-not (.isDirectory root)
                     (throw (ex-info (str "Kotlin source directory does not exist: " root)
                                     {:source-path (str root)})))
                   (tree-seq (fn [file]
                               (and (.isDirectory file)
                                    (or (= file root)
                                        (not (or (str/starts-with? (.getName file) ".")
                                                 (contains? ignored-directories (.getName file)))))))
                             (fn [file] (seq (.listFiles file))) root))))
       (filter kotlin-file?)
       (map #(.getCanonicalPath %))
       distinct
       sort))

(defn- psi-descendants [element kind]
  (PsiTreeUtil/collectElementsOfType element (into-array Class [kind])))

(defn- psi-ancestors [element]
  (take-while some? (iterate #(.getParent %) (.getParent element))))

(defn- header-reference? [element]
  (some #(or (instance? KtPackageDirective %) (instance? KtImportDirective %))
        (psi-ancestors element)))

(defn- qualified-name [package name]
  (if (str/blank? package) name (str package "." name)))

(defn- declaration-symbols [^KtNamedDeclaration declaration]
  (when-let [fq-name (.getFqName declaration)]
    (when-not (some #(and (instance? KtNamedDeclaration %)
                         (.hasModifier ^KtNamedDeclaration % KtTokens/PRIVATE_KEYWORD))
                    (cons declaration (psi-ancestors declaration)))
      (let [name (.asString fq-name)
            companion (some #(when (and (instance? KtObjectDeclaration %)
                                        (.isCompanion ^KtObjectDeclaration %)) %)
                            (psi-ancestors declaration))]
        (cond-> [name]
          companion (conj (str/replace name
                                       (str "." (.getName ^KtObjectDeclaration companion) ".")
                                       ".")))))))

(defn- imports [^KtFile file]
  (vec (for [^KtImportDirective directive (.getImportDirectives file)
             :let [fq-name (.getImportedFqName directive)]
             :when fq-name]
         {:name (.asString fq-name)
          :alias (.getAliasName directive)
          :wildcard? (.isAllUnder directive)})))

(defn- parse-file [^KtPsiFactory factory path]
  (let [file (.createFile factory (.getName (io/file path)) (slurp path :encoding "UTF-8"))
        errors (psi-descendants file PsiErrorElement)]
    (when-let [^PsiErrorElement error (first errors)]
      (let [offset (.getTextOffset error)
            line (inc (count (filter #{\newline} (take offset (.getText file)))))]
        (throw (ex-info (str "Kotlin syntax error in " path ":" line ": " (.getErrorDescription error))
                        {:file path :line line :offset offset}))))
    (let [package (.asString (.getPackageFqName file))
          declarations (psi-descendants file KtNamedDeclaration)]
      {:path path
       :file file
       :package package
       :module (qualified-name package (str/replace (.getName (io/file path)) #"\.(kt|kts)$" ""))
       :imports (imports file)
       :symbols (set (mapcat declaration-symbols declarations))
       :own-names (set (keep #(.getName ^KtNamedDeclaration %) (.getDeclarations file)))
       :abstract? (boolean (some (fn [^KtClass klass]
                                  (or (.isInterface klass)
                                      (.hasModifier klass KtTokens/ABSTRACT_KEYWORD)))
                                (psi-descendants file KtClass)))})))

(defn- unique-modules! [files]
  (doseq [[module sources] (group-by :module files)
          :when (> (count sources) 1)]
    (throw (ex-info (str "Duplicate Kotlin file module " module ": "
                         (str/join ", " (map :path sources)))
                    {:module module :files (mapv :path sources)})))
  files)

(defn- symbol-index [files]
  (reduce (fn [index {:keys [module symbols]}]
            (reduce #(update %1 %2 (fnil conj #{}) module) index symbols))
          {} files))

(defn- symbol-modules [index name]
  ;; Imports can name nested types, object members or companion members.
  (loop [name name]
    (if-let [modules (get index name)]
      modules
      (if-let [dot (str/last-index-of name ".")]
        (recur (subs name 0 dot))
        #{}))))

(defn- expression-name [expression]
  (cond
    (instance? KtNameReferenceExpression expression)
    (.getReferencedName ^KtNameReferenceExpression expression)

    (instance? KtDotQualifiedExpression expression)
    (let [^KtDotQualifiedExpression expression expression
          receiver (expression-name (.getReceiverExpression expression))
          selector (expression-name (.getSelectorExpression expression))]
      (when (and receiver selector) (str receiver "." selector)))

    (instance? KtCallExpression expression)
    (expression-name (.getCalleeExpression ^KtCallExpression expression))))

(defn- type-name [^KtUserType user-type]
  (when-let [reference (.getReferenceExpression user-type)]
    (if-let [qualifier (.getQualifier user-type)]
      (str (type-name qualifier) "." (.getReferencedName reference))
      (.getReferencedName reference))))

(defn- simple-reference? [^KtNameReferenceExpression reference]
  (let [parent (.getParent reference)]
    (not (and (instance? KtUserType parent)
              (or (.getQualifier ^KtUserType parent)
                  (instance? KtUserType (.getParent parent)))))))

(defn- scope-declarations [scope reference]
  (concat
    (when (instance? KtTypeParameterListOwner scope)
      (.getTypeParameters ^KtTypeParameterListOwner scope))
    (when (instance? KtDeclarationWithBody scope)
      (.getValueParameters ^KtDeclarationWithBody scope))
    (when (instance? KtClassOrObject scope)
      (concat (.getPrimaryConstructorParameters ^KtClassOrObject scope)
              (.getDeclarations ^KtClassOrObject scope)))
    (when (instance? KtForExpression scope)
      (keep identity [(.getLoopParameter ^KtForExpression scope)]))
    (when (instance? KtCatchClause scope)
      (keep identity [(.getCatchParameter ^KtCatchClause scope)]))
    (when (instance? KtBlockExpression scope)
      (mapcat (fn [statement]
                (when (<= (.getEndOffset (.getTextRange statement)) (.getTextOffset reference))
                  (cond
                    (instance? KtNamedDeclaration statement) [statement]
                    (instance? KtDestructuringDeclaration statement)
                    (.getEntries ^KtDestructuringDeclaration statement))))
              (.getStatements ^KtBlockExpression scope)))))

(defn- locally-bound? [^KtNameReferenceExpression reference]
  (let [name (.getReferencedName reference)
        type? (instance? KtUserType (.getParent reference))]
    (some (fn [scope]
            (some (fn [declaration]
                    (and (instance? KtNamedDeclaration declaration)
                         (= name (.getName ^KtNamedDeclaration declaration))
                         (or (not type?)
                             (instance? KtClassOrObject declaration)
                             (instance? KtTypeAlias declaration)
                             (instance? KtTypeParameter declaration))))
                  (scope-declarations scope reference)))
          (psi-ancestors reference))))

(defn- reference-modules [index {:keys [file package imports own-names]}]
  (let [explicit (remove :wildcard? imports)
        bound-names (set (map #(or (:alias %) (last (str/split (:name %) #"\."))) explicit))
        wildcard-packages (map :name (filter :wildcard? imports))
        references (remove header-reference? (psi-descendants file KtNameReferenceExpression))
        simple-names (set (map #(.getReferencedName ^KtNameReferenceExpression %)
                              (filter #(and (simple-reference? %) (not (locally-bound? %))) references)))
        qualified (concat (map expression-name (remove header-reference? (psi-descendants file KtDotQualifiedExpression)))
                          (map type-name (psi-descendants file KtUserType)))]
    (set (concat
           (mapcat #(symbol-modules index (:name %)) explicit)
           (mapcat #(get index % #{}) (remove nil? qualified))
           (mapcat (fn [name]
                     (when-not (or (contains? own-names name) (contains? bound-names name))
                       (let [same-package (get index (qualified-name package name))]
                         (or same-package
                             (mapcat #(get index (qualified-name % name) #{}) wildcard-packages)))))
                   simple-names)))))

(defn- module-graph [files]
  (let [files (unique-modules! files)
        index (symbol-index files)
        nodes (set (map :module files))
        edges (set (for [{:keys [module] :as file} files
                         target (reference-modules index file)
                         :when (not= module target)]
                     {:from module :to target}))]
    (merge (graph/make-graph nodes edges)
           {:abstract-modules (set (map :module (filter :abstract? files)))
            :module->source-file (into {} (map (juxt :module :path) files))})))

(defn build-module-graph [project-path source-paths]
  ;; PSI and its project are confined to one analysis, including GUI reloads.
  (let [disposable (Disposer/newDisposable "arch-view Kotlin")]
    (try
      (let [environment (KotlinCoreEnvironment/createForProduction
                          disposable (CompilerConfiguration.) EnvironmentConfigFiles/JVM_CONFIG_FILES)
            factory (KtPsiFactory. (.getProject environment) false)]
        (module-graph (mapv #(parse-file factory %) (discover-files project-path source-paths))))
      (finally (Disposer/dispose disposable)))))
