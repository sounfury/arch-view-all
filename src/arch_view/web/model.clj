;; 职责：把完整架构整理为网页数据（含 Maven/Gradle 构建模块标注），并把用户选择的子系统实现状态保存到架构文档。
;; 核心入口：生成当前层数据（view-data）；生成项目概览（project-data）。

(ns arch-view.web.model
  (:require [arch-view.domain.architecture-projection :as projection]
            [arch-view.context.provider :as provider]
            [arch-view.web.complexity :as complexity]
            [arch-view.web.documents :as documents]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private members-by-child ^:private members-for ^:private source-info ^:private node-data
         ^:private build-module-data ^:private document-headings ^:private status-fields
         ^:private canonical-sources)

(defn create-state [architecture project-path opts]
  (let [root (documents/root-path project-path)]
    {:architecture architecture :root root :opts opts
     ;; 规范路径每个文件只算一次，避免每次浏览都对全部源码发起文件系统调用。
     :canonical-sources (canonical-sources architecture)
     :documents (documents/discover root architecture (:architecture-doc opts))
     ;; 业务入口在首次打开入口导航时计算，同一次分析内复用。
     :entries-cache (atom nil)}))

(defn project-data [{:keys [architecture root documents can-reanalyze opts] :as state}]
  {:name (.getName (.toFile ^java.nio.file.Path root))
   :root (str root)
   :language (get-in architecture [:guidance :language] :clojure)
   :namespaceRootDepth (get-in architecture [:guidance :namespace-root-depth] 1)
   :sourcePaths (get-in architecture [:guidance :source-paths])
   :buildModules (mapv build-module-data (get-in architecture [:guidance :build-modules]))
   :moduleCount (count (get-in architecture [:graph :nodes]))
   :dependencyCount (count (get-in architecture [:graph :edges]))
   :canReanalyze (boolean can-reanalyze)
   :edgeScope (get opts :edge-scope "focus")
   :agentConfigured (or (contains? #{"pi" "pi-acp"} (:agent-provider opts))
                        (not (str/blank? (str (:agent-command opts)))))
   :agentSessions (try (= "pi-acp" (:provider (provider/configuration state)))
                       (catch Exception _ false))
   :complexity (complexity/summary (:complexity state))
   :documents documents})

(defn view-data [{:keys [architecture root documents] :as state} path]
  (let [path (vec path)
        view (projection/view-architecture architecture path)
        canonical @(or (:canonical-sources state) (canonical-sources architecture))
        context {:state state :view view :path path :canonical canonical
                 :groups (members-by-child architecture path)
                 ;; 构建模块固定是根层第一段，进入模块内部后就是普通软件包。
                 :build-modules (when (empty? path)
                                  (into {} (map (juxt :name build-module-data))
                                        (get-in architecture [:guidance :build-modules])))}
        nodes (mapv #(node-data context %) (sort (get-in view [:graph :nodes])))
        sources (keep canonical (mapcat :members nodes))
        local-docs (filter (fn [{:keys [scope directory]}]
                            (and (= scope :package)
                                 (let [dir (.toPath (io/file (.toFile ^java.nio.file.Path root) directory))]
                                   (some #(.startsWith (.toPath (io/file ^String %)) dir) sources))))
                          documents)]
    {:path path :nodes nodes
     :edges (vec (sort-by (juxt :from :to) (:classified-edges view)))
     :displayEdges (vec (sort-by (juxt :from :to) (:display-edges view)))
     :cycles (:cycle-lines view)
     :documents (vec local-docs)}))

(defn source-data [{:keys [architecture root]} module]
  (let [path (get-in architecture [:graph :module->source-file module])]
    (when-not path (throw (ex-info "未找到源码模块" {:status 404})))
    (let [file (documents/contained-file root path)
          content (slurp file :encoding "UTF-8")]
      {:module module :path (documents/relative-path root file)
       :description (documents/header-description content) :content content})))

(def ^:private max-file-bytes (* 2 1024 1024))

(defn file-data
  "读取项目内任意文本文件（链路步骤可能落在前端、配置等未分析的文件上）。"
  [{:keys [root]} path]
  (when (str/blank? path) (throw (ex-info "缺少文件路径" {:status 400})))
  (let [file (documents/contained-file root (io/file (.toFile ^java.nio.file.Path root) ^String path))]
    (when-not (.isFile file) (throw (ex-info "文件不存在" {:status 404})))
    (when (> (.length file) max-file-bytes) (throw (ex-info "文件过大，无法在网页中查看" {:status 413})))
    (let [content (slurp file :encoding "UTF-8")]
      {:path (documents/relative-path root file)
       :description (documents/header-description content) :content content})))

(defn document-data [{:keys [root documents]} id]
  (when-not (some #(= id (:id %)) documents)
    (throw (ex-info "未找到说明文档" {:status 404})))
  (documents/read-document root id))

(defn update-subsystem-status! [{:keys [root] :as state} id heading status]
  (when-not (and (string? id) (string? heading) (seq heading))
    (throw (ex-info "缺少架构文档或子系统标题" {:status 400})))
  (when-not (#{"已完成" "未完成" "进行中"} status)
    (throw (ex-info "实现状态只能选择已完成、未完成或进行中" {:status 400})))
  (document-data state id)
  ;; 只修改已收录文档中唯一的小节；其余正文、换行方式和人工编辑保留。
  (let [file (documents/contained-file root (io/file (.toFile ^java.nio.file.Path root) id))]
    (locking update-subsystem-status!
      (let [content (:content (document-data state id))
            headings (document-headings content)
            matches (filter #(and (> (:depth %) 2) (= heading (:title %))) headings)]
        (when-not (= 1 (count matches))
          (throw (ex-info "未找到唯一的子系统小节，请重新加载文档" {:status 409})))
        (let [section (first matches)
              end (or (:start (first (filter #(> (:start %) (:start section)) headings))) (count content))
              body (subs content (:body-start section) end)
              fields (status-fields body)
              newline (if (str/includes? content "\r\n") "\r\n" "\n")]
          (when (> (count fields) 1)
            (throw (ex-info "子系统存在多个实现状态，请先整理文档" {:status 409})))
          (let [line (str "- **实现状态**：" status "。")
                updated (if-let [field (first fields)] (str (subs body 0 (:start field)) line (subs body (:end field)))
                            (str newline line newline body))]
            (spit file (str (subs content 0 (:body-start section)) updated (subs content end)) :encoding "UTF-8")
            (document-data state id)))))))

(defn locate-data [{:keys [architecture] :as state} module]
  (when-not (contains? (get-in architecture [:graph :nodes]) module)
    (throw (ex-info "当前项目中未找到这个模块，请重新分析后重试" {:status 404})))
  (let [parts (binding [projection/*namespace-root-depth* (get-in architecture [:guidance :namespace-root-depth] 1)]
                (projection/namespace-segments module))
        path (vec (butlast parts))
        node (some #(when (= module (:sourceModule %)) %) (:nodes (view-data state path)))]
    (when-not node (throw (ex-info "无法定位这个模块的架构位置" {:status 404})))
    {:path path :nodeId (:id node) :module module}))

;; ===== 私有方法 =====

(defn- status-fields [content]
  (loop [lines (str/split content #"\n" -1) offset 0 fence nil fields []]
    (if-not (seq lines) fields
      (let [raw (first lines) line (str/replace raw #"\r$" "")
            marker (second (re-find #"^\s{0,3}(`{3,}|~{3,})" line))
            closes? (and fence marker (= (first fence) (first marker)) (>= (count marker) (count fence)))
            match? (and (nil? fence) (nil? marker)
                        (re-find #"^[ \t]*[-*+]\s+(?:\*\*)?实现状态(?:\*\*)?\s*[:：]" line))]
        (recur (next lines) (+ offset (count raw) 1) (cond closes? nil fence fence marker marker :else nil)
               (if match? (conj fields {:start offset :end (+ offset (count line))}) fields))))))

(defn- document-headings [content]
  (loop [lines (str/split content #"\n" -1) offset 0 fence nil headings []]
    (if-not (seq lines) headings
      (let [raw (first lines) line (str/replace raw #"\r$" "")
            marker (second (re-find #"^\s{0,3}(`{3,}|~{3,})" line))
            closes? (and fence marker (= (first fence) (first marker)) (>= (count marker) (count fence)))
            match (when (and (nil? fence) (nil? marker)) (re-matches #"^(#{2,6})\s+(.+?)\s*#*\s*$" line))
            next-offset (min (count content) (+ offset (count raw) 1))]
        (recur (next lines) next-offset (cond closes? nil fence fence marker marker :else nil)
               (if match (conj headings {:title (str/trim (nth match 2)) :depth (count (second match))
                                        :start offset :body-start next-offset}) headings))))))

(defn- node-data [{:keys [state view path canonical groups build-modules]} id]
  (let [{:keys [architecture root]} state
        leaf? (true? (get-in view [:module->leaf? id]))
        members (members-for groups path id leaf?)
        member-files (mapv #(get-in architecture [:graph :module->source-file %]) members)
        ;; 分组只有一个明确的源码文件时，直接沿用它的说明；仍保留分组与下钻语义。
        only-file (when (and (seq member-files) (every? some? member-files)
                             (= 1 (count (set member-files))))
                    (first member-files))
        file (or (get-in view [:module->source-file id]) only-file)
        build-module (when-not leaf? (get build-modules id))]
    (merge {:id id :label (get-in view [:module->display-label id])
            :fullName (get-in view [:module->full-name id])
            :leaf leaf? :abstract (= :abstract (get-in view [:module->kind id]))
            :cycle (boolean (get-in view [:module->cycle? id]))
            :layer (get-in view [:layout :module->layer id] 0)
            :moduleCount (count members) :members members
            :sourceModule (when leaf? (first members))}
           (when build-module {:buildModule build-module})
           (when (and (not leaf?) only-file) {:descriptionFromOnlyFile true})
           (when-let [metrics (complexity/node-metrics (:complexity state)
                                                       (map (fn [module] [module (get canonical module)]) members))]
             {:complexity metrics})
           (source-info root file))))

(defn- build-module-data [{:keys [name directory build-tool]}]
  {:name name :directory directory :buildTool build-tool})

(defn- canonical-sources [architecture]
  (delay (into {} (for [[module file] (get-in architecture [:graph :module->source-file])
                        :when file]
                    [module (.getCanonicalPath (io/file file))]))))

(defn- members-by-child
  "把当前层下的模块按直接子包分组并附带分段，避免每个节点都重扫全部模块。"
  [architecture path]
  (let [segments (:segments (projection/architecture-index architecture))
        depth (count path)]
    (->> segments
         (filter (fn [[_ parts]] (and (> (count parts) depth) (projection/prefix? path parts))))
         (group-by (fn [[_ parts]] (nth parts depth))))))

(defn- members-for [groups path node leaf?]
  (let [size (inc (count path))]
    (->> (get groups (projection/node-child-name node))
         (keep (fn [[module parts]]
                 (when (if leaf? (= (count parts) size) (> (count parts) size)) module)))
         sort vec)))

(defn- source-info [root file]
  (when file
    (try
      (let [file (documents/contained-file root file)]
        {:sourcePath (documents/relative-path root file)
         :description (documents/header-description (slurp file :encoding "UTF-8"))})
      (catch java.io.IOException _ nil)
      (catch clojure.lang.ExceptionInfo _ nil))))
