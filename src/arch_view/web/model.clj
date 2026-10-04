;; 职责：把完整架构整理为网页数据，并把用户选择的子系统实现状态保存到架构文档。
;; 核心入口：生成当前层数据（view-data）；生成项目概览（project-data）。

(ns arch-view.web.model
  (:require [arch-view.domain.architecture-projection :as projection]
            [arch-view.web.complexity :as complexity]
            [arch-view.web.documents :as documents]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private members-for ^:private source-info ^:private document-headings ^:private status-fields)

(defn create-state [architecture project-path opts]
  (let [root (documents/root-path project-path)]
    {:architecture architecture :root root :opts opts
     :documents (documents/discover root architecture (:architecture-doc opts))}))

(defn project-data [{:keys [architecture root documents can-reanalyze opts] :as state}]
  {:name (.getName (.toFile ^java.nio.file.Path root))
   :root (str root)
   :language (get-in architecture [:guidance :language] :clojure)
   :namespaceRootDepth (get-in architecture [:guidance :namespace-root-depth] 1)
   :sourcePaths (get-in architecture [:guidance :source-paths])
   :moduleCount (count (get-in architecture [:graph :nodes]))
   :dependencyCount (count (get-in architecture [:graph :edges]))
   :canReanalyze (boolean can-reanalyze)
   :edgeScope (get opts :edge-scope "focus")
   :complexity (complexity/summary (:complexity state))
   :documents documents})

(defn view-data [{:keys [architecture root documents] :as state} path]
  (let [view (projection/view-architecture architecture path)
        nodes (mapv (fn [id]
                      (let [leaf? (true? (get-in view [:module->leaf? id]))
                            members (members-for architecture path id leaf?)
                            member-files (mapv #(get-in architecture [:graph :module->source-file %]) members)
                            ;; 分组只有一个明确的源码文件时，直接沿用它的说明；仍保留分组与下钻语义。
                            only-file (when (and (seq member-files) (every? some? member-files)
                                                 (= 1 (count (set member-files))))
                                        (first member-files))
                            file (or (get-in view [:module->source-file id]) only-file)]
                        (merge {:id id :label (get-in view [:module->display-label id])
                                :fullName (get-in view [:module->full-name id])
                                :leaf leaf? :abstract (= :abstract (get-in view [:module->kind id]))
                                :cycle (boolean (get-in view [:module->cycle? id]))
                                :layer (get-in view [:layout :module->layer id] 0)
                                :moduleCount (count members) :members members
                                :sourceModule (when leaf? (first members))}
                               (when (and (not leaf?) only-file) {:descriptionFromOnlyFile true})
                               (when-let [metrics (complexity/node-metrics
                                                   (:complexity state)
                                                   (map (fn [module file] [module (some-> file io/file .getCanonicalPath)])
                                                        members member-files))]
                                 {:complexity metrics})
                               (source-info root file))))
                    (sort (get-in view [:graph :nodes])))
        sources (keep #(get-in architecture [:graph :module->source-file %]) (mapcat :members nodes))
        local-docs (filter (fn [{:keys [scope directory]}]
                            (and (= scope :package)
                                 (some (fn [file]
                                         (.startsWith (.toPath (.getCanonicalFile (io/file file)))
                                                      (.toPath (io/file (.toFile ^java.nio.file.Path root) directory))))
                                       sources))) documents)]
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

(defn- members-for [architecture path node leaf?]
  (binding [projection/*namespace-root-depth* (get-in architecture [:guidance :namespace-root-depth] 1)]
    (let [prefix (conj path (projection/node-child-name node))]
      (->> (get-in architecture [:graph :nodes])
           (filter (fn [module]
                     (let [parts (projection/namespace-segments module)]
                       (and (projection/prefix? prefix parts)
                            (if leaf? (= (count parts) (count prefix))
                                (> (count parts) (count prefix)))))))
           sort vec))))

(defn- source-info [root file]
  (when file
    (try
      (let [file (documents/contained-file root file)]
        {:sourcePath (documents/relative-path root file)
         :description (documents/header-description (slurp file :encoding "UTF-8"))})
      (catch java.io.IOException _ nil)
      (catch clojure.lang.ExceptionInfo _ nil))))
