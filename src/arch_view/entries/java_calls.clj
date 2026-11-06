;; 职责：用隔离的 JavacTask 解析项目内方法声明与调用位置，保留静态接收者、参数、Lambda/方法引用上下文，供入口链路按类型层次展开。
;; 核心入口：分析调用（analyze）；方法标识（method-id）。不运行注解处理器或目标代码，不生成 class 文件。

(ns arch-view.entries.java-calls
  (:require [arch-view.entries.java-source :as java]
            [arch-view.entries.method-contracts :as contracts]
            [arch-view.entries.module :as module]
            [arch-view.entries.source-snapshot :as snapshot]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.sun.source.tree ClassTree CompilationUnitTree IdentifierTree LambdaExpressionTree MemberSelectTree MethodTree
            MethodInvocationTree MemberReferenceTree NewClassTree ParenthesizedTree Tree Tree$Kind TypeCastTree]
           [com.sun.source.util JavacTask TreePath TreePathScanner Trees]
           [javax.lang.model.element Element ElementKind ExecutableElement Modifier TypeElement VariableElement]
           [javax.lang.model.type TypeKind]
           [javax.lang.model.util Types]
           [javax.tools DiagnosticCollector StandardLocation ToolProvider]
           [java.nio.charset StandardCharsets]))

(declare ^:private analyze-task ^:private scan-unit ^:private declaration ^:private invocation)

(defn method-id [owner method params]
  (str owner "#" method "(" (str/join "," params) ")"))

(defn analyze
  "ctx 为入口识别上下文。返回 {:nodes {方法标识 节点} :calls [{:from :target :receiver :method :args :anchor…}]
  :implementations {抽象/可重写方法标识 [实现标识…]}}。缺失外部依赖的诊断允许存在，不能解析的调用不会变成实线。"
  [ws ctx]
  (let [files (->> (vals (get-in ctx [:index :types])) (map :file) distinct sort)
        compiler (ToolProvider/getSystemJavaCompiler)
        diagnostics (DiagnosticCollector.)]
    (snapshot/assert-current! ws (:fingerprints ctx) (keys (:fingerprints ctx)))
    (when-not compiler (throw (ex-info "入口调用链需要完整 JDK（jdk.compiler）。" {:status 400})))
    (if (empty? files) {:nodes {} :calls [] :implementations {}}
      (with-open [manager (.getStandardFileManager compiler diagnostics nil StandardCharsets/UTF_8)]
        (.setLocation manager StandardLocation/CLASS_PATH [])
        (.setLocation manager StandardLocation/SOURCE_PATH [])
        (let [^JavacTask task (.getTask compiler nil manager diagnostics ["-proc:none" "-implicit:none" "-Xlint:none"] nil
                                       (.getJavaFileObjectsFromFiles manager (map io/file files)))]
          (let [result (analyze-task ws ctx task)]
            (snapshot/assert-current! ws (:fingerprints ctx) (keys (:fingerprints ctx)))
            result))))))

;; ===== 私有方法 =====

(defn- erased [^Types types mirror]
  (when mirror
    (try (str (.erasure types mirror)) (catch Exception _ (str mirror)))))

(defn- executable-id [types owners ^ExecutableElement method]
  (let [owner (.getEnclosingElement method)]
    (when (instance? TypeElement owner)
      (method-id (get owners owner (str (.getQualifiedName ^TypeElement owner))) (str (.getSimpleName method))
                 (map #(erased types (.asType ^VariableElement %)) (.getParameters method))))))

(defn- tree-element [^Trees trees ^TreePath path]
  (try (.getElement trees path) (catch Exception _ nil)))

(defn- tree-type [^Trees trees ^Types types owners ^TreePath path]
  (try
    (let [mirror (.getTypeMirror trees path)]
      (when (and mirror (not (#{TypeKind/NONE TypeKind/VOID TypeKind/NULL TypeKind/OTHER} (.getKind mirror))))
        (or (get owners (.asElement types mirror)) (erased types mirror))))
    (catch Exception _ nil)))

(defn- qualified [ctx owner written]
  (when (seq written)
    (let [written (str/replace written #"<.*>" "")]
      (when (seq written)
        (if (or (str/includes? written ".") (re-matches #"(?:void|int|long|short|byte|float|double|char|boolean)(?:\[\])*" written))
          written
          (:fqn (java/resolve-type (:index ctx) (:ctx owner) written)))))))

(defn- tree-anchor [{:keys [ws trees]} ^CompilationUnitTree unit tree]
  (let [positions (.getSourcePositions ^Trees trees)
        start (.getStartPosition positions unit tree)
        end (.getEndPosition positions unit tree)
        lines (.getLineMap unit)]
    (when (and (>= start 0) (>= end start))
      {:path (workspace/relative ws (io/file (.toUri (.getSourceFile unit))))
       :startLine (.getLineNumber lines start) :endLine (.getLineNumber lines (max start (dec end)))})))

(defn- annotation-names [ctx owner annotations]
  (set (keep #(:fqn (java/annotation-type (:index ctx) owner %)) annotations)))

(defn- declaration [{:keys [ctx types trees owners] :as env} unit owner ^TreePath path ^MethodTree tree]
  (let [element (tree-element trees path)
        anchor (tree-anchor env unit tree)]
    (when (and (instance? ExecutableElement element) anchor)
      (let [name (str (.getName tree))
            params (mapv #(erased types (.asType ^VariableElement %)) (.getParameters ^ExecutableElement element))
            start (.getStartPosition (.getSourcePositions ^Trees trees) unit tree)
            raw (some #(when (= start (:source-start %)) %) (:methods owner))
            flags (set (.getModifiers ^ExecutableElement element))]
        {:id (executable-id types owners element) :type (:fqn owner) :method name :params params
         :module (module/for-type ctx owner) :kind "method" :summary (:doc raw)
         :anchor (assoc anchor :symbol (str (:simple owner) "#" name))
         :abstract (nil? (.getBody tree))
         :virtual (not (or (= "<init>" name) (some flags [Modifier/STATIC Modifier/PRIVATE Modifier/FINAL])))
         :annotations (annotation-names ctx owner (concat (:annotations owner) (:annotations raw)))
         :raw raw :element element :owner-element (.getEnclosingElement ^ExecutableElement element)}))))

(defn- deferred-reason [^TreePath path]
  (loop [parent (.getParentPath path)]
    (when parent
      (let [kind (.getKind (.getLeaf parent))]
        (cond
          (= kind Tree$Kind/LAMBDA_EXPRESSION) "Lambda 回调，执行时机未确定"
          (= kind Tree$Kind/METHOD) nil
          :else (recur (.getParentPath parent)))))))

(defn- receiver-tree [tree]
  (cond
    (instance? MethodInvocationTree tree)
    (let [select (.getMethodSelect ^MethodInvocationTree tree)]
      (when (instance? MemberSelectTree select) (.getExpression ^MemberSelectTree select)))
    (instance? MemberReferenceTree tree) (.getQualifierExpression ^MemberReferenceTree tree)
    :else nil))

(defn- called-name [tree]
  (cond
    (instance? NewClassTree tree) "<init>"
    (instance? MemberReferenceTree tree) (str (.getName ^MemberReferenceTree tree))
    :else (let [select (.getMethodSelect ^MethodInvocationTree tree)]
            (if (instance? MemberSelectTree select) (str (.getIdentifier ^MemberSelectTree select)) (str select)))))

(defn- arguments [tree]
  (cond
    (instance? MethodInvocationTree tree) (.getArguments ^MethodInvocationTree tree)
    (instance? NewClassTree tree) (.getArguments ^NewClassTree tree)
    :else []))

(defn- receiver-field [ctx owner expression]
  (let [name (cond
               (instance? IdentifierTree expression) (str (.getName ^IdentifierTree expression))
               (and (instance? MemberSelectTree expression) (= "this" (str (.getExpression ^MemberSelectTree expression))))
               (str (.getIdentifier ^MemberSelectTree expression)))]
    (when name
      (some (fn [fqn] (when-let [field (get-in ctx [:index :types fqn :fields name])]
                        (assoc field :owner fqn)))
            (cons (:fqn owner) (sort ((:supers ctx) (:fqn owner))))))))

(defn- anonymous-body [tree]
  (cond
    (instance? NewClassTree tree) (.getClassBody ^NewClassTree tree)
    (instance? ParenthesizedTree tree) (recur (.getExpression ^ParenthesizedTree tree))
    (instance? TypeCastTree tree) (recur (.getExpression ^TypeCastTree tree))))

(defn- argument-type [{:keys [ctx trees types owners]} unit owner arg]
  (or (when-let [body (anonymous-body arg)]
        (let [path (TreePath/getPath unit body)
              fqn (when path (java/class-id (.getSourcePositions ^Trees trees) unit path owner))]
          ;; 缺外部父类时 diamond 匿名表达式可能没有 TypeMirror，但源码类型标识仍可定位。
          (when (get-in ctx [:index :types fqn]) fqn)))
      (qualified ctx owner (tree-type trees types owners (TreePath/getPath unit arg)))))

(defn- invocation-receiver [{:keys [ctx trees types owners]} unit owner tree]
  (let [expression (receiver-tree tree)
        expression-path (when expression (TreePath/getPath unit expression))
        receiver-element (when expression-path (tree-element trees expression-path))
        field (when (or (nil? receiver-element) (= ElementKind/FIELD (.getKind ^Element receiver-element)))
                (receiver-field ctx owner expression))
        receiver (or (some->> expression-path (tree-type trees types owners) (qualified ctx owner))
                     (some->> (:type-name field) (qualified ctx (get-in ctx [:index :types (:owner field)] owner)))
                     (when (instance? NewClassTree tree) (qualified ctx owner (str (.getIdentifier ^NewClassTree tree))))
                     (:fqn owner))]
    {:expression expression :field field :receiver receiver}))

(defn- invocation [{:keys [ctx trees types owners] :as env} {:keys [unit owner caller path tree]}]
  (let [element (tree-element trees path)
        executable (when (instance? ExecutableElement element) element)
        {:keys [expression field receiver]} (invocation-receiver env unit owner tree)
        target (when executable (executable-id types owners executable))
        declared (when executable (some-> (.getEnclosingElement ^ExecutableElement executable) str))
        args (arguments tree)]
    {:from (:id caller) :target target :declaringType declared :receiver receiver :method (called-name tree)
     :args (mapv #(java/eval-tree (:index ctx) owner %) args)
     :argumentTypes (mapv #(argument-type env unit owner %) args)
     :field field
     :anchor (tree-anchor env unit tree)
     :reason (or (when (instance? MemberReferenceTree tree) "方法引用，执行时机未确定") (deferred-reason path))
     :exact (or (instance? NewClassTree tree) (= "super" (str expression))
                (instance? NewClassTree expression))
     :written (str tree)}))

(defn- scan-unit
  "先保存语法树上的方法和调用位置，再进行 attribution；缺依赖时 Javac 可能把 Lambda 体改写为错误节点。"
  [{:keys [ctx trees]} ^CompilationUnitTree unit]
  (let [declarations (atom []) invocations (atom []) classes (atom [])
        current-type (atom nil) current-method (atom nil)
        record (fn [path tree]
                 (when @current-method
                   (swap! invocations conj {:unit unit :owner @current-type :caller @current-method
                                            :path path :tree tree :written (str tree) :reason (deferred-reason path)})))
        scanner (proxy [TreePathScanner] []
                  (visitClass [tree data]
                    (let [previous @current-type previous-method @current-method
                          path (.getCurrentPath this)
                          fqn (java/class-id (.getSourcePositions ^Trees trees) unit path previous)]
                      (reset! current-type (get-in ctx [:index :types fqn]))
                      (when @current-type (swap! classes conj {:path path :owner @current-type}))
                      (reset! current-method nil)
                      (try (.scan ^TreePathScanner this ^Iterable (.getMembers ^ClassTree tree) data)
                           (finally (reset! current-type previous) (reset! current-method previous-method)))))
                  (visitMethod [tree data]
                    (let [previous @current-method]
                      (when @current-type
                        (swap! declarations conj {:unit unit :owner @current-type :path (.getCurrentPath this) :tree tree}))
                      (reset! current-method (when @current-type tree))
                      (try (.scan ^TreePathScanner this ^Tree (.getBody ^MethodTree tree) data)
                           (finally (reset! current-method previous)))))
                  (visitLambdaExpression [tree data]
                    (.scan ^TreePathScanner this (.getBody ^LambdaExpressionTree tree) data))
                  (visitMethodInvocation [tree data]
                    (record (.getCurrentPath this) tree)
                    ;; 用 scan(Tree) 保留父路径；proxy-super 还会在递归期间跳过嵌套的同名 visitor。
                    (.scan ^TreePathScanner this ^Tree (.getMethodSelect ^MethodInvocationTree tree) data)
                    (.scan ^TreePathScanner this ^Iterable (.getTypeArguments ^MethodInvocationTree tree) data)
                    (.scan ^TreePathScanner this ^Iterable (.getArguments ^MethodInvocationTree tree) data))
                  (visitNewClass [tree data]
                    (record (.getCurrentPath this) tree)
                    (.scan ^TreePathScanner this ^Tree (.getEnclosingExpression ^NewClassTree tree) data)
                    (.scan ^TreePathScanner this ^Tree (.getIdentifier ^NewClassTree tree) data)
                    (.scan ^TreePathScanner this ^Iterable (.getTypeArguments ^NewClassTree tree) data)
                    (.scan ^TreePathScanner this ^Iterable (.getArguments ^NewClassTree tree) data)
                    (.scan ^TreePathScanner this ^Tree (.getClassBody ^NewClassTree tree) data))
                  (visitMemberReference [tree data]
                    (record (.getCurrentPath this) tree)
                    (.scan ^TreePathScanner this ^Tree (.getQualifierExpression ^MemberReferenceTree tree) data)
                    (.scan ^TreePathScanner this ^Iterable (.getTypeArguments ^MemberReferenceTree tree) data)))]
    (.scan ^TreePathScanner scanner unit nil)
    {:declarations @declarations :invocations @invocations :classes @classes}))

(defn- implementations
  "按 Javac overrides 计算 CHA，遍历具体类型继承的方法，支持子类实现接口但方法定义在父类的情况。"
  [ctx ^JavacTask task owners nodes]
  (let [elements (.getElements task)
        types (.getTypes task)
        owner-elements (into {} (map (fn [[element fqn]] [fqn element])) owners)
        inherited (for [type (vals (get-in ctx [:index :types]))
                        :when (and (= :class (:kind type)) (not (:abstract? type)))
                        :let [owner (get owner-elements (:fqn type))] :when owner
                        member (try (.getAllMembers elements owner) (catch Exception _ []))
                        :when (instance? ExecutableElement member)
                        :let [id (executable-id types owners member) node (get nodes id)]
                        :when (and node (not (:abstract node)))]
                    {:owner owner :type (:fqn type) :node node})
        by-name (group-by #(get-in % [:node :method]) inherited)
        pairs (for [[id {:keys [type method virtual element]}] nodes :when virtual
                    {:keys [owner node] actual :type} (get by-name method)
                    :when (and (or (= type actual) (contains? ((:supers ctx) actual) type))
                               (or (= id (:id node))
                                   (try (.overrides elements (:element node) element owner) (catch Exception _ false))))]
                {:target id :method (:id node) :owner actual})]
    {:implementations (into {} (for [[id items] (group-by :target pairs)] [id (vec (sort (distinct (map :method items))))]))
     :implementationOwners (into {} (for [[pair items] (group-by (juxt :target :method) pairs)]
                                     [pair (set (map :owner items))]))}))

(defn- method-documentation
  "方法图使用 Javac 的真实 overrides 关系，泛型替换和同参重载不依赖简单名猜测。"
  [{:keys [ws ctx elements]} nodes]
  (let [by-type-name (group-by (juxt :type :method) (vals nodes))]
    (update-vals
     nodes
     (fn [{:keys [type method raw element owner-element] :as node}]
       (let [owner (get-in ctx [:index :types type])
             inherited (for [parent ((:supers ctx) type)
                             candidate (get by-type-name [parent method])
                             :when (and (:raw candidate)
                                        (try (.overrides elements element (:element candidate) owner-element)
                                             (catch Exception _ false)))]
                         {:type (get-in ctx [:index :types parent]) :method (:raw candidate)})
             docs (contracts/describe ws (:index ctx) {:type owner :method raw :inherited inherited})]
         (merge node docs {:implementationAnchor (when-not (:abstract node) (:anchor node))}))))))

(defn- callback-method?
  "只连接匿名/局部类重写的方法；普通 helper 和构造器不代表回调入口。"
  [{:keys [ctx types elements]} {:keys [type raw abstract element owner-element]}]
  (let [owner (get-in ctx [:index :types type])
        flags (set (.getModifiers ^ExecutableElement element))]
    (and (:local? owner) (not abstract) (not-any? flags [Modifier/STATIC Modifier/PRIVATE])
         (or (:override? raw)
             (some (fn [parent]
                     (try
                       (some (fn [member]
                               (and (instance? ExecutableElement member)
                                    (.overrides elements element member owner-element)))
                             (.getAllMembers elements (.asElement ^Types types parent)))
                       (catch Exception _ false)))
                   (.directSupertypes ^Types types (.asType ^TypeElement owner-element)))))))

(defn- callback-calls
  "保留传入模板的源码回调实现；参数传递不能证明实际调用或执行顺序，因此显式标为不确定。"
  [callbacks call]
  (for [type (distinct (:argumentTypes call)) node (get callbacks type)]
    (assoc call :target (:id node) :declaringType type :receiver type :method (:method node)
           :args (vec (repeat (count (:params node)) nil)) :argumentTypes (:params node)
           :field nil :exact true
           :reason (str (when (:reason call) (str (:reason call) "；"))
                        "匿名/局部类回调参数，是否调用及执行时机未确定"))))

(defn- analyze-task [ws ctx ^JavacTask task]
  (let [units (vec (.parse task))
        trees (Trees/instance task)
        scanned (mapv #(scan-unit {:ctx ctx :trees trees} %) units)
        _ (try (.analyze task) (catch RuntimeException ex
                               (throw (ex-info (str "Java 调用解析失败：" (.getMessage ex)) {:status 422} ex))))
        owners (into {} (for [{:keys [path owner]} (mapcat :classes scanned)
                              :let [element (tree-element trees path)] :when (instance? TypeElement element)]
                          [element (:fqn owner)]))
        env {:ws ws :ctx ctx :trees trees :types (.getTypes task) :elements (.getElements task) :owners owners}
        by-tree (into {} (for [{:keys [unit owner path tree]} (mapcat :declarations scanned)
                              :let [node (declaration env unit owner path tree)] :when node] [tree node]))
        nodes (method-documentation env (into {} (map (juxt :id identity)) (vals by-tree)))
        implementations (implementations ctx task owners nodes)
        callbacks (group-by :type (filter #(callback-method? env %) (vals nodes)))
        callback-ids (set (map :id (mapcat val callbacks)))
        invocations (for [{:keys [caller written reason] :as site} (mapcat :invocations scanned)
                          :let [node (get by-tree caller)] :when node
                          :let [call (assoc (invocation env (assoc site :caller node)) :written written)]]
                      (cond-> call
                        reason (assoc :reason (str/join "；" (distinct (remove nil? [(:reason call) reason]))))))
        invocations (map (fn [call]
                           (if (and (not (:exact call))
                                    (not (get-in ctx [:index :types (:receiver call) :local?]))
                                    (some callback-ids (get-in implementations [:implementations (:target call)])))
                             (update call :reason #(str (when % (str % "；"))
                                                       "匿名/局部类回调分派，实际接收者及执行时机未确定"))
                             call)) invocations)]
    (merge {:nodes (update-vals nodes #(dissoc % :element :owner-element :raw))
            :calls (vec (distinct (concat invocations (mapcat #(callback-calls callbacks %) invocations))))}
           implementations)))
