;; 职责：按入口规则匹配 Java 类型与方法（及 XML 声明），生成业务入口列表（HTTP、RPC、消息、定时、启动、事件）。
;; 核心入口：扫描入口（scan、scan-with）；分析上下文（analysis-context）；结果含入口、业务分组、规则状态与检查所需的歧义、疑似遗漏、XML 一致性数据。

(ns arch-view.entries.core
  (:require [arch-view.entries.declared :as declared]
            [arch-view.entries.domains :as domains]
            [arch-view.entries.java-source :as java]
            [arch-view.entries.java-calls :as java-calls]
            [arch-view.entries.methods :as methods]
            [arch-view.entries.method-contracts :as contracts]
            [arch-view.entries.rules :as rules]
            [arch-view.entries.source-snapshot :as snapshot]
            [arch-view.entries.xml-source :as xml]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare analysis-context scan-with ^:private java-files ^:private impl-edges ^:private type-candidates
         ^:private no-impl-candidates ^:private select-candidates ^:private finalize ^:private ambiguities ^:private suspects
         ^:private flat-values ^:private distinct-by-id ^:private element-hits ^:private sem-attrs ^:private rule-inputs)

(defn scan
  "识别项目入口。规则文件有错时仍用内置规则识别，错误放在 :rules。"
  [ws]
  (let [loaded (rules/load-rules ws)
        language (get-in ws [:architecture :guidance :language])]
    (if-not (= :java language)
      {:entries [] :rules (select-keys loaded [:file :status :errors]) :language language :unsupported true
       :project-rules (:project loaded) :overrides [] :ambiguous [] :suspects []
       :declared {:problems [] :unsubscribed []} :grouping nil}
      (scan-with ws loaded (analysis-context ws loaded)))))

(defn scan-with
  "用已读取的规则和已建立的分析上下文识别入口并计算业务分组；candidates 命令复用同一份上下文。"
  [ws loaded ctx]
  (let [candidates (mapcat #(type-candidates ctx %) (vals (get-in ctx [:index :types])))
        from-xml (declared/xml-candidates ctx)
        {:keys [kept overrides]} (select-candidates (concat candidates (no-impl-candidates ctx candidates)
                                                            (:candidates from-xml)))
        found (->> kept (mapcat #(finalize ctx %)) distinct-by-id
                   (sort-by (juxt #(get-in % [:channel :label]) :label :id)) vec)
        graph (delay
                (binding [*out* *err*] (println "入口分析：开始构建方法图"))
                (let [started (System/nanoTime)
                      analyzed (java-calls/analyze ws ctx)
                      parsed (System/nanoTime)
                      _ (binding [*out* *err*]
                          (println (str "入口分析：Java 调用解析完成（" (quot (- parsed started) 1000000) " ms）")))
                      built ((requiring-resolve 'arch-view.entries.chain/build) ctx analyzed)]
                  (binding [*out* *err*]
                    (println (str "入口分析：方法图构建完成（" (quot (- (System/nanoTime) parsed) 1000000) " ms）")))
                  built))
        root-of (fn [entry] ((requiring-resolve 'arch-view.entries.chain/handler-root) @graph entry))
        {:keys [entries grouping]} (domains/assign ws (:config loaded) found
                                                 {:impl-edges (impl-edges ctx) :supers (:supers ctx)
                                                  :method-graph graph :method-root root-of})]
    (with-meta
      {:entries entries :grouping grouping :rules (select-keys loaded [:file :status :errors])
       :language (get-in ws [:architecture :guidance :language])
       :project-rules (:project loaded) :overrides overrides
       :ambiguous (ambiguities ctx) :suspects (suspects ctx entries)
       :declared (select-keys from-xml [:problems :unsubscribed])}
      {:method-graph graph})))

(defn analysis-context
  "解析源码并按规则建立匹配索引；candidates 命令复用。"
  [ws {:keys [rules]}]
  (let [files (java-files ws)
        started (System/nanoTime)
        _ (binding [*out* *err*] (println (str "入口分析：解析入口源码（" (count files) " 个文件）")))
        fingerprints (snapshot/capture ws (concat files (rule-inputs ws rules)))
        index (java/scan-files files)
        _ (snapshot/assert-current! ws fingerprints (keys fingerprints))
        _ (binding [*out* *err*]
            (println (str "入口分析：入口源码解析完成（" (quot (- (System/nanoTime) started) 1000000) " ms）")))]
    {:ws ws :index index :rules rules :fingerprints fingerprints
     :file-modules (group-by (comp str val) (get-in ws [:architecture :graph :module->source-file]))
     :by-annotation (group-by #(get-in % [:match :annotation]) (filter #(get-in % [:match :annotation]) rules))
     :type-rules (remove #(get-in % [:match :annotation]) rules)
     :supers (memoize #(java/all-supers index %))}))

(defn annotation-matches
  "按全名、元注解、when 与所在位置匹配注解；出口识别复用同一份规则语义。"
  [ctx type annotations level]
  (element-hits ctx type annotations level))

(defn rule-attributes
  "按 attrs 映射取属性并补 defaults；入口和出口共同使用。"
  [rule attrs]
  (sem-attrs rule attrs))

;; ===== 私有方法 =====

(defn- rule-inputs [ws rules]
  (let [root (.toFile ^java.nio.file.Path (:root ws))
        patterns (mapcat #(get-in % [:match :xml :files]) rules)
        roots (when (seq patterns) (xml/resource-roots root (get-in ws [:architecture :guidance :source-paths])))]
    (cons (io/file root ".arch-view" rules/rules-file-name)
          (map :file (xml/matching-files (xml/xml-files roots) patterns)))))

(defn- java-files [ws]
  (->> (vals (get-in ws [:architecture :graph :module->source-file]))
       (map str) (filter #(str/ends-with? % ".java")) distinct sort))

(defn- level-ok? [rule level]
  (or (= :any (:on rule)) (= level (:on rule))))

(defn- scalar [value]
  (if (java/unresolved? value) (:name value) value))

(defn- path-values [attrs path]
  (reduce (fn [values key]
            (->> values
                 (mapcat #(if (sequential? %) % [%]))
                 (keep #(cond (and (map? %) (:annotation %)) (get-in % [:attrs key])
                              (map? %) (get % key)))))
          [attrs] (str/split path #"\.")))

(defn- condition-ok? [condition value]
  (if (map? condition)
    (let [[k v] (first condition)]
      (case (name k)
        "in" (contains? (set v) value)
        "not" (not (condition-ok? v value))))
    (= condition value)))

(defn- when-ok? [conditions attrs]
  (every? (fn [[path condition]]
            (let [values (map scalar (flat-values (path-values attrs path)))
                  present? (and (map? condition) (= "present" (name (key (first condition)))))]
              (if present?
                (= (val (first condition)) (boolean (seq values)))
                (boolean (some #(condition-ok? condition %) values)))))
          conditions))

(defn- annotation-hits
  "注解命中的规则；项目源码里定义的注解按元注解展开，外层属性覆盖元注解属性。"
  [ctx type ann level depth]
  (let [{:keys [index by-annotation]} ctx
        fqn (:fqn (java/annotation-type index type ann))
        direct (filter #(level-ok? % level) (get by-annotation fqn))
        meta-type (get-in index [:types fqn])
        attrs (delay (java/annotation-attrs index type ann))]
    (concat
     (for [rule direct :when (when-ok? (:when rule) @attrs)] {:rule rule :attrs @attrs})
     (when (and (= :annotation (:kind meta-type)) (pos? depth))
       (->> (:annotations meta-type)
            (mapcat #(annotation-hits ctx meta-type % level (dec depth)))
            (map #(update % :attrs merge @attrs)))))))

(defn- element-hits [ctx type anns level]
  (mapcat #(annotation-hits ctx type % level 2) anns))

(defn- simple-name [fqn]
  (last (str/split (str fqn) #"\.")))

(defn- type-rule-hit? [supers {:keys [match]}]
  (cond
    (:implements match) (contains? supers (:implements match))
    (:extends match) (contains? supers (:extends match))
    (:extendsSuffix match) (some #(str/ends-with? (simple-name %) (:extendsSuffix match)) supers)
    :else false))

(defn- class-hits [ctx type]
  (let [supers ((:supers ctx) (:fqn type))]
    (concat (element-hits ctx type (:annotations type) :class)
            (for [rule (:type-rules ctx) :when (type-rule-hit? supers rule)] {:rule rule :attrs {}}))))

(defn- inherited-hits
  "按完整签名与泛型替换匹配父声明，保留当前规则实际命中的契约。"
  [ctx type method]
  (for [{parent :type parent-method :method :as contract} (contracts/inherited-methods (:index ctx) type method)
        hit (element-hits ctx parent (:annotations parent-method) :method)]
    (assoc hit :via (contracts/declaration-key parent parent-method) :contract contract :annotation-source contract)))

(defn- method-hits [ctx type method]
  (concat (map #(assoc % :annotation-source {:type type :method method})
               (element-hits ctx type (:annotations method) :method))
          (when-not (:static? method) (inherited-hits ctx type method))))

(defn- sem-attrs
  "按规则把注解属性映射为语义键，未出现的键取 defaults。"
  [rule attrs]
  (merge (:defaults rule)
         (reduce (fn [acc [attr key]]
                   (if (and (contains? attrs attr) (not (contains? acc key))) (assoc acc key (get attrs attr)) acc))
                 {} (:attrs rule))))

(defn- by-role [hits]
  (group-by #(get-in % [:rule :role]) hits))

(defn- flat-values [value]
  (cond (sequential? value) (mapcat flat-values value)
        (nil? value) []
        :else [value]))

(defn- text [value]
  (str (java/display-value value)))

(defn- join-path [prefix path]
  (let [joined (str/replace (str "/" prefix "/" path) #"/+" "/")]
    (if (and (> (count joined) 1) (str/ends-with? joined "/")) (subs joined 0 (dec (count joined))) joined)))

(defn- hit-values [hits key]
  (mapcat #(flat-values (get (sem-attrs (:rule %) (:attrs %)) key)) hits))

(defn- preferred-rule [hits]
  (:rule (or (first (filter #(= :project (get-in % [:rule :origin])) hits)) (first hits))))

(defn- unresolved-warnings [values]
  (when (some java/unresolved? values) ["unresolved-attr"]))

(defn- http-candidates
  "控制器类中带映射注解的方法：类前缀 × 方法路径 × HTTP 方法。"
  [type class-roles method method-roles]
  (let [class-hits (concat (:http-controller class-roles) (:http-mapping class-roles))
        mappings (:http-mapping method-roles)
        prefixes (or (seq (concat (hit-values class-hits "prefix") (hit-values (:http-mapping class-roles) "path"))) [""])
        paths (or (seq (hit-values mappings "path")) [""])
        verbs (or (seq (concat (keep #(get-in % [:rule :http-method]) mappings)
                               (map scalar (hit-values mappings "httpMethod"))))
                  (seq (map scalar (hit-values class-hits "httpMethod")))
                  ["ANY"])
        rule (preferred-rule mappings)
        channel-rule (preferred-rule (concat (filter #(= :project (get-in % [:rule :origin])) class-hits) mappings))]
    (for [prefix prefixes path paths verb (distinct verbs)]
      {:type type :method method :rule rule :kind :http :channel-rule channel-rule
       :sem (merge (apply merge (map #(dissoc (sem-attrs (:rule %) (:attrs %)) "prefix" "path" "httpMethod")
                                     (concat class-hits mappings)))
                   {"httpMethod" (str verb) "path" (join-path (text prefix) (text path))})
       :warnings (unresolved-warnings [prefix path])
       :via (some :via mappings)
       :contract (some :contract (filter #(= (:id rule) (get-in % [:rule :id])) mappings))
       :annotation-source (some :annotation-source (filter #(= (:id rule) (get-in % [:rule :id])) mappings))})))

(defn- method-entry-candidates [type method hits]
  (for [hit hits]
    {:type type :method method :rule (:rule hit) :kind (get-in hit [:rule :kind])
     :sem (sem-attrs (:rule hit) (:attrs hit)) :via (:via hit)
     :contract (:contract hit) :annotation-source (:annotation-source hit)}))

(defn- class-entry-candidates [ctx type hits]
  (mapcat
   (fn [{:keys [rule attrs]}]
     (let [selected (seq (methods/select-methods ctx type (:methods rule) attrs))
           external (sort (remove #(contains? (get-in ctx [:index :types]) %)
                                  ((:supers ctx) (:fqn type))))
           base {:type type :rule rule :kind (:kind rule) :sem (sem-attrs rule attrs)
                 :annotation-source {:type type :method nil}}]
       (cond
         selected (map #(assoc base :method %) selected)
         ;; 类级 RPC 发布事实已确定，但外部依赖的公有模板方法无法从源码定位。
         (and (= :rpc (:kind rule)) (get-in rule [:match :annotation]) (seq external)
              (not= "public" (:methods rule))
              (not-any? #(contains? (:methods rule) %) ["names" "annotation"]))
         [(assoc base :declaration {:status "unresolved" :reason "external-inherited-method"
                                   :externalTypes (vec external)}
                      :warnings ["unresolved-declaration"])]
         :else [])))
   hits))

(defn- main-method? [method]
  (and (= "main" (:name method)) (:static? method) (:public? method) (:void? method)
       (= ["String[]"] (:params method))))

(defn- main-candidates [ctx type]
  (for [rule (filter #(get-in % [:match :main]) (:rules ctx))
        method (filter main-method? (:methods type))]
    {:type type :method method :rule rule :kind (:kind rule) :sem {}}))

(defn- meta-sem [hits]
  (apply merge {} (map #(sem-attrs (:rule %) (:attrs %)) hits)))

(defn- with-meta-attrs [class-meta method-roles candidate]
  (let [method-meta (:meta method-roles)]
    (-> candidate
        (update :sem #(merge (meta-sem class-meta) (meta-sem method-meta) %))
        (assoc :meta-rules (distinct (map #(get-in % [:rule :id]) (concat class-meta method-meta)))))))

(defn- concrete? [type]
  (and (= :class (:kind type)) (not (:abstract? type))))

(defn- method-candidates [ctx type class-roles method]
  (let [roles (by-role (method-hits ctx type method))
        controller? (seq (:http-controller class-roles))]
    (map #(with-meta-attrs (:meta class-roles) roles %)
         (concat (when (and controller? (seq (:http-mapping roles)))
                   (http-candidates type class-roles method roles))
                 (method-entry-candidates type method (:entry roles))))))

(defn- type-candidates [ctx type]
  (when (concrete? type)
    (let [class-roles (by-role (class-hits ctx type))
          method-meta (fn [method] (by-role (method-hits ctx type method)))]
      (concat (mapcat #(method-candidates ctx type class-roles %) (:methods type))
              (map #(with-meta-attrs (:meta class-roles) (method-meta (:method %)) %)
                   (concat (class-entry-candidates ctx type (:entry class-roles))
                           (main-candidates ctx type)))))))

(defn- no-impl-candidates
  "写在项目内接口方法上的入口注解，没有任何实现类时挂在接口方法上。"
  [ctx candidates]
  (let [implemented (set (keep :via candidates))]
    (for [type (vals (get-in ctx [:index :types]))
          :when (= :interface (:kind type))
          method (:methods type)
          :when (not (contains? implemented (contracts/declaration-key type method)))
          hit (:entry (by-role (element-hits ctx type (:annotations method) :method)))]
      {:type type :method method :rule (:rule hit) :kind (get-in hit [:rule :kind])
       :sem (sem-attrs (:rule hit) (:attrs hit)) :warnings ["no-impl"]})))

(defn- candidate-key [{:keys [type method]}]
  [(:fqn type) (:name method) (or (:signature-params method) (:params method))])

(defn- select-candidates
  "同一方法既有项目规则又有内置规则命中时只保留项目规则；接口方法被多个类实现时标 multi-impl。"
  [candidates]
  (let [impl-counts (->> candidates
                         (keep #(when (:via %) [(:via %) (get-in % [:rule :id]) (get-in % [:type :fqn])]))
                         distinct (map butlast) frequencies)
        candidates (map #(if (> (get impl-counts [(:via %) (get-in % [:rule :id])] 0) 1)
                           (update % :warnings (fnil conj []) "multi-impl") %)
                        candidates)
        groups (group-by candidate-key candidates)]
    (reduce (fn [acc [key group]]
              (let [project (filter #(= :project (get-in % [:rule :origin])) group)
                    builtin (remove #(= :project (get-in % [:rule :origin])) group)]
                (if (and (seq project) (seq builtin))
                  (-> acc (update :kept into project)
                      (update :overrides conj {:handler (str (first key) "#" (second key))
                                               :project (vec (distinct (map #(get-in % [:rule :id]) project)))
                                               :builtin (vec (distinct (map #(get-in % [:rule :id]) builtin)))}))
                  (update acc :kept into group))))
            {:kept [] :overrides []} groups)))

(defn- tag-key? [key]
  (re-matches #"(flag|extra):.+" key))

(defn- truthy? [value]
  (let [value (scalar value)]
    (not (or (nil? value) (false? value) (= "false" value) (= "" value)
             (and (sequential? value) (empty? value))))))

(defn- tags [sem]
  (vec (for [[key value] (sort-by key sem)
             :when (tag-key? key)
             :let [[kind label] (str/split key #":" 2)]
             :when (truthy? value)]
         (if (= "flag" kind) label (str label "=" (str/join "," (map text (flat-values value))))))))

(defn- expand
  "消息 topic、RPC operation 写成数组时每个值一个入口。"
  [{:keys [kind sem] :as candidate}]
  (let [key (case kind :mq "topic" :rpc "operation" nil)
        values (when key (flat-values (get sem key)))]
    (if (> (count values) 1)
      (map #(assoc-in candidate [:sem key] %) values)
      [candidate])))

(defn- symbol-name [{:keys [type method declaration]}]
  (if declaration
    (str (:simple type) "（入口方法未解析）")
    (str (:simple type) "#" (:name method))))

(defn- schedule-label [sem]
  (cond (get sem "cron") (str "cron " (get sem "cron"))
        (get sem "fixedRate") (str "fixedRate " (get sem "fixedRate"))
        (get sem "fixedDelay") (str "fixedDelay " (get sem "fixedDelay"))
        :else (get sem "name")))

(defn- rpc-label [candidate sem]
  (or (get sem "operation")
      (when-not (:declaration candidate)
        (when-let [service (get sem "service")] (str (simple-name service) "#" (get-in candidate [:method :name]))))))

(defn- label [candidate sem]
  (or (not-empty (case (:kind candidate)
                   :http (str (get sem "httpMethod") " " (get sem "path"))
                   :schedule (schedule-label sem)
                   :mq (get sem "topic")
                   :rpc (rpc-label candidate sem)
                   (get sem "name")))
      (symbol-name candidate)))

(defn- display-sem [sem]
  (into {} (for [[key value] sem :when (not (tag-key? key))]
             [key (let [shown (java/display-value value)]
                    (if (sequential? shown) (mapv str shown) (str shown)))])))

(defn- channel [{:keys [rule channel-rule kind]}]
  (let [rule (or channel-rule rule)
        label (or (:channel rule) (rules/kind-labels kind) (name kind))]
    {:id label :label label
     :source (cond (= :project (:origin rule)) "rule"
                   (:channel rule) "builtin"
                   :else "kind")}))

(defn- build-module [ws module]
  (some #(when (str/starts-with? (str module) (str (:name %) ".")) (:name %))
        (get-in ws [:architecture :guidance :build-modules])))

(defn- entry-id [kind text-label shown {:keys [type method declaration rule]}]
  (str (name kind) ":" text-label
       (when (= :mq kind) (str "|" (get shown "consumerGroup") "|" (get shown "tag")))
       "#" (:fqn type) "." (if declaration "<unresolved>" (:name method))
       "(" (str/join "," (or (:signature-params method) (:params method))) ")"
       "|rule:" (when (= :builtin (:origin rule)) "builtin:") (:id rule)))

(defn- type-module
  "处理类所属的图节点：同一源码文件里名称与顶层类型一致的模块。"
  [{:keys [file-modules]} type]
  (let [top (or (last (:outers type)) (:fqn type))
        modules (map key (get file-modules (:file type)))]
    (or (first (filter #(or (= top %) (str/ends-with? % (str "." top))) modules))
        (first (sort modules))
        top)))

(defn- entry-documentation [{:keys [ws index]} {:keys [type method contract annotation-source]} shown]
  (let [docs (contracts/describe ws index type method (contracts/inherited-methods index type method) contract)
        attribute (contracts/valid-doc (get shown "summary"))
        class-doc (when-not (= "ambiguous" (:docStatus docs)) (contracts/valid-doc (:doc type)))
        origin (or annotation-source {:type type :method method})]
    (assoc docs :businessName (contracts/valid-doc (get shown "name"))
           :methodSummary (:summary docs) :methodDocSource (:docSource docs)
           :description attribute
           :summary (or attribute (:summary docs) class-doc)
           :docSource (cond attribute (contracts/source ws (:type origin) (:method origin) "annotation")
                            (:summary docs) (:docSource docs)
                            class-doc (contracts/source ws type nil "javadoc")))))

(defn- finalize [{:keys [ws] :as ctx} candidate]
  (for [{:keys [type method kind rule sem declaration] :as candidate} (expand candidate)
        :let [shown (display-sem sem)
              text-label (or (declared/fill-template (:label-template candidate) shown) (label candidate shown))
              path (workspace/relative ws (:file type))
              module (type-module ctx type)
              docs (when-not declaration (entry-documentation ctx candidate shown))
              contract (:contract candidate)
              contract-source (when contract (contracts/source ws (:type contract) (:method contract) "contract"))]]
    (cond->
    (merge docs
    {:id (entry-id kind text-label shown candidate)
     :kind (name kind) :label text-label :channel (channel candidate)
     :module module :buildModule (build-module ws module)
     :handler (cond-> {:type (:fqn type) :method (if declaration "<unresolved>" (:name method)) :params (if declaration [] (:params method))}
                (not declaration) (assoc :signature (contracts/parameters (:index ctx) type method)))
     :anchor {:path path :startLine (:start (or method type)) :endLine (:end (or method type)) :symbol (symbol-name candidate)}
     :attrs shown :tags (tags sem) :summary (if declaration
                                             "已声明发布 RPC 服务；入口方法继承自外部依赖，无法定位，需复核。"
                                             (:summary docs))
     :rule (if (= :project (:origin rule)) (:id rule) (str "builtin:" (:id rule)))
     :metaRules (vec (:meta-rules candidate))
     :sources (cond-> (vec (:sources candidate)) contract-source (conj contract-source))
     :warnings (vec (distinct (concat (:warnings candidate)
                                      (when (= "ambiguous" (:docStatus docs)) ["ambiguous-doc"])
                                      (unresolved-warnings (mapcat flat-values (vals sem))))))})
      declaration (assoc :declaration declaration))))

(defn- impl-edges
  "项目内接口或抽象类只有一个具体实现时，补一条到实现类的边，让归属计算能从接口走到实现。"
  [{:keys [index supers] :as ctx}]
  (let [types (:types index)
        impls (reduce (fn [acc type]
                        (reduce #(update %1 %2 (fnil conj []) type) acc (filter types (supers (:fqn type)))))
                      {} (filter concrete? (vals types)))]
    (for [[super [impl & more]] impls :when (not more)]
      {:from (type-module ctx (types super)) :to (type-module ctx impl)})))

(defn- merge-entry-contracts [left right]
  (let [candidate (fn [entry] (when-let [source (:contractSource entry)]
                               (assoc source :businessName (:businessName entry) :summary (:summary entry))))
        candidates (vec (distinct (concat (or (:contractCandidates left) (when-let [source (candidate left)] [source]))
                                          (or (:contractCandidates right) (when-let [source (candidate right)] [source])))))
        origins (distinct (map :anchor candidates))
        unique-value (fn [field] (let [values (distinct (map field candidates))]
                                   (when (= 1 (count values)) (first values))))]
    (if (< (count origins) 2) left
      (assoc left :contractCandidates candidates :contractStatus "ambiguous" :docStatus "ambiguous"
             :contractSource nil :contractAnchor nil :docSource nil :methodDocSource nil
             :businessName (unique-value :businessName) :summary (unique-value :summary)
             :methodSummary nil :description nil :docCandidates candidates
             :sources (vec (distinct (concat (:sources left) (:sources right))))
             :warnings (vec (distinct (concat (:warnings left) (:warnings right) ["ambiguous-contract"])))))))

(defn- distinct-by-id [entries]
  (:items (reduce (fn [{:keys [seen] :as acc} entry]
                    (if-let [position (get seen (:id entry))]
                      (update-in acc [:items position] merge-entry-contracts entry)
                      (-> acc (assoc-in [:seen (:id entry)] (count (:items acc))) (update :items conj entry))))
                  {:seen {} :items []} entries)))

(defn- ambiguities
  "只因通配导入不能确定全名、但候选里有规则目标的注解。"
  [{:keys [ws index by-annotation]}]
  (for [type (vals (:types index))
        ann (concat (:annotations type) (mapcat :annotations (:methods type)))
        :let [{:keys [candidates]} (java/annotation-type index type ann)
              targets (filter #(contains? by-annotation %) candidates)]
        :when (seq targets)]
    {:location (str (workspace/relative ws (:file type)) ":" (:line ann))
     :annotation (:name ann) :candidates candidates}))

(def ^:private suspect-suffixes
  ["Controller" "Api" "Endpoint" "Facade" "Provider" "Job" "Task" "Listener" "Consumer" "Handler"])

(defn- suspects
  "未识别入口的具体类，区分入口规则证据和仅名称推断。"
  [{:keys [ws index] :as ctx} entries]
  (let [handlers (set (concat (map #(get-in % [:handler :type]) entries) (keep :type (mapcat :sources entries))))]
    (->> (vals (:types index))
         (filter #(and (concrete? %) (not (contains? handlers (:fqn %)))))
         (keep (fn [type]
                 (let [name (str/replace (:simple type) #"Impl$" "")
                       suffix (some #(when (str/ends-with? name %) %) suspect-suffixes)
                       hits (filter #(#{:entry :http-controller :http-mapping} (get-in % [:rule :role]))
                                    (concat (class-hits ctx type)
                                            (mapcat #(method-hits ctx type %) (:methods type))))
                       ids (vec (sort (distinct (map #(get-in % [:rule :id]) hits))))]
                   (when (or suffix (seq ids))
                     {:type (:fqn type) :location (str (workspace/relative ws (:file type)) ":" (:start type))
                      :evidence (if (seq ids) "framework" "name")
                      :basis (if (seq ids) ids [suffix])}))))
         (sort-by :type) vec)))
