;; 职责：把目标、架构职责、静态依赖、复杂度和已有知识整理为可核对的上下文包。
;; 核心入口：构建上下文包（build）；命令行同步分析复杂度（analyze-complexity）。

(ns arch-view.context.pack
  (:require [arch-view.context.architecture :as architecture]
            [arch-view.context.records :as records]
            [arch-view.web.complexity :as complexity]
            [arch-view.web.documents :as documents]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private source-entry ^:private target-info ^:private dependencies ^:private metrics
         ^:private markdown ^:private source-limit ^:private edge-index ^:private neighbors
         ^:private cycle-modules ^:private render-target ^:private render-documents
         ^:private render-headers ^:private render-dependencies ^:private render-complexity
         ^:private render-references ^:private render-source ^:private render-question
         ^:private section ^:private bullet-lines ^:private render-neighbors ^:private render-records
         ^:private source-fence)

(defn build
  "按契约顺序生成 Markdown；复杂度结果采用网页 state 的 :complexity 结构。"
  [ws target {:keys [max-source-lines question] :as options}]
  (let [resolved (workspace/resolve-target ws target)
        limit (source-limit max-source-lines)
        sources (mapv #(source-entry ws % limit (= :package (:kind resolved))) (:modules resolved))
        data {:target (target-info ws resolved sources)
              :architecture (architecture/matching-sections ws resolved)
              :headers (mapv #(select-keys % [:module :path :description]) sources)
              :dependencies (dependencies ws resolved)
              :complexity (metrics ws resolved (get options :complexity (:complexity ws)))
              :references (records/references ws resolved)
              :source sources
              :question question}]
    {:target (:target resolved) :data data :markdown (markdown data)}))

(defn analyze-complexity
  "复用网页的命令发现、JSON 解析及索引；auto 在当前线程完成一次分析。"
  [ws]
  (let [root (:root ws) limit (#'complexity/complexity-limit root)
        command (#'complexity/find-command (assoc (:opts ws) :crap "auto"))]
    (if-not command {:status "unavailable" :limit limit}
      (try {:status "ready" :limit limit
            :files (#'complexity/index-functions (#'complexity/run-crap command root))}
           (catch Exception ex {:status "failed" :limit limit :error (.getMessage ex)})))))

;; ===== 私有方法 =====

(defn- source-limit [value]
  (let [n (or value 300)]
    (when-not (and (integer? n) (pos? n))
      (throw (ex-info "--max-source-lines 必须是正整数" {:status 400})))
    n))

(defn- source-entry [ws module limit package?]
  (let [path (workspace/source-file ws module)
        file (when path (documents/contained-file (:root ws) (io/file (.toFile ^java.nio.file.Path (:root ws)) path)))]
    (if-not (and file (.isFile file))
      {:module module :path path :description nil :unavailable true}
      (let [content (slurp file :encoding "UTF-8")
            lines (str/split-lines content)
            shown (vec (take limit lines))]
        (cond-> {:module module :path path :description (documents/header-description content)}
          (not package?) (assoc :totalLines (count lines) :shownLines (count shown)
                                :truncated (> (count lines) limit)
                                :content (str/join "\n" shown)))))))

(defn- target-info [ws resolved sources]
  (let [layers (get-in ws [:architecture :layout :module->layer])]
    (assoc resolved :paths (vec (keep :path sources))
           :layers (into (sorted-map) (map (fn [module] [module (get layers module)])) (:modules resolved)))))

(defn- cycle-modules [ws]
  (set (mapcat identity (get-in ws [:architecture :layout :cycles]))))

(defn- edge-index [ws]
  (let [edges (get-in ws [:architecture :graph :edges])
        classified (get-in ws [:architecture :classified-edges])
        abstract (get-in ws [:architecture :graph :abstract-modules] #{})]
    (vals (reduce (fn [index edge]
                    (let [typed (assoc edge :type (or (:type edge)
                                                     (if (contains? abstract (:to edge)) :abstract :direct)))]
                      (assoc index [(:from edge) (:to edge)] typed)))
                  {} (concat edges classified)))))

(defn- neighbors [ws members edges direction]
  (let [[inside outside] (if (= direction :upstream) [:to :from] [:from :to])
        related (filter #(and (members (inside %)) (not (members (outside %)))) edges)
        cycles (cycle-modules ws)]
    (->> related (group-by outside)
         (map (fn [[module links]]
                (let [abstract? (boolean (some #(= :abstract (:type %)) links))]
                  {:module module :path (workspace/source-file ws module) :depth 1
                   :type (if abstract? :abstract :direct) :abstract abstract?
                   :cycle (contains? cycles module)})))
         (sort-by :module) vec)))

(defn- dependencies [ws resolved]
  (let [members (set (:modules resolved)) edges (edge-index ws)]
    {:upstream (neighbors ws members edges :upstream)
     :downstream (neighbors ws members edges :downstream)
     :cycles (vec (filter #(some members %) (get-in ws [:architecture :layout :cycles])))}))

(defn- metrics [ws resolved result]
  (let [module-files (map (fn [module]
                            [module (some->> (workspace/source-file ws module)
                                             (io/file (.toFile ^java.nio.file.Path (:root ws)))
                                             .getCanonicalPath)]) (:modules resolved))
        value (complexity/node-metrics result module-files)]
    {:status (get result :status "unavailable") :limit (:limit result) :error (:error result)
     :metrics (when value
                (update value :functions
                        #(mapv (fn [entry] (assoc entry :path (workspace/source-file ws (:module entry)))) %)))}))

(defn- section [title body]
  (str "## " title "\n\n" body "\n\n"))

(defn- bullet-lines [values fallback]
  (if (seq values) (str/join "\n" (map #(str "- " %) values)) fallback))

(defn- render-target [{:keys [target kind paths layers modules]}]
  (str "- 名称：" target "\n- 类型：" ({:module "模块" :package "包" :file "文件"} kind)
       "\n- 成员数：" (count modules)
       "\n- 源码路径：" (if (seq paths) (str/join "、" paths) "无可用源码")
       "\n- 所在层级：" (str/join "、" (map (fn [[module layer]] (str module " = " (if (some? layer) layer "未知"))) layers))))

(defn- render-documents [entries]
  (if (seq entries)
    (str/join "\n\n" (map (fn [{:keys [path title status body]}]
                              (str "### " title "\n\n来源：" path "\n\n实现状态："
                                   (if (seq status) status "未标注") "\n\n" body)) entries))
    "未找到匹配的架构职责小节。"))

(defn- render-headers [headers]
  (str/join "\n\n" (map (fn [{:keys [module path description]}]
                            (str "### " module "\n\n" (or path "无源码路径") "\n\n" (or description "未提供文件头说明。"))) headers)))

(defn- render-neighbors [title entries]
  (str "### " title "（共 " (count entries) " 项）\n\n"
       (bullet-lines (map (fn [{:keys [module path type abstract cycle]}]
                            (str module " · " (if (or abstract (= :abstract type)) "抽象依赖" "直接依赖")
                                 " · " (if cycle "在环中" "不在环中") (when path (str " · " path)))) (take 20 entries))
                     "无。")
       (when (> (count entries) 20) (str "\n共 " (count entries) " 项，已列出前 20 项。"))))

(defn- render-dependencies [{:keys [upstream downstream cycles]}]
  (str (render-neighbors "上游（依赖目标的模块）" upstream) "\n\n"
       (render-neighbors "下游（目标依赖的模块）" downstream)
       "\n\n目标参与的环：" (if (seq cycles) (str/join "；" (map #(str/join " → " %) cycles)) "无。")))

(defn- render-complexity [{:keys [status error metrics]}]
  (if metrics
    (str "最高圈复杂度：" (:max metrics) "；合计：" (:total metrics) "；函数数：" (:count metrics) "。\n\n"
         (bullet-lines (map (fn [{:keys [symbol path line complexity]}]
                              (str symbol " · " path ":" line " · 圈复杂度 " complexity)) (:functions metrics)) "无函数结果。"))
    (str (get {"running" "复杂度分析正在进行。" "failed" "复杂度分析失败。" "unavailable" "无复杂度结果。"} status "无复杂度结果。")
         (when error (str "\n" error)))))

(defn- render-records [title entries]
  (str "### " title "\n\n"
       (bullet-lines (map (fn [{:keys [id title status]}] (str title "（" id "） · 新鲜度：" status)) entries) "无。")))

(defn- render-references [{:keys [notes traces warnings]}]
  (str (render-records "已有解释" notes) "\n\n" (render-records "已有链路" traces)
       (when (seq warnings) (str "\n\n读取提示：\n" (bullet-lines warnings "")))))

(defn- source-fence [content]
  (let [longest (reduce max 2 (map count (re-seq #"`+" content)))]
    (apply str (repeat (inc longest) "`"))))

(defn- render-source [{:keys [target source]}]
  (if (= :package (:kind target))
    (bullet-lines (map (fn [{:keys [module path description]}]
                         (str module " · " (or path "无源码路径") "\n\n  "
                              (str/replace (or description "未提供文件头说明。") "\n" "\n  "))) source) "无成员。")
    (str/join "\n\n"
              (map (fn [{:keys [path content totalLines shownLines truncated unavailable]}]
                     (if unavailable (str (or path "目标") "：源码不可用。")
                       (let [fence (source-fence content)]
                         (str "### " path "\n\n共 " totalLines " 行"
                              (if truncated (str "，已截取前 " shownLines " 行") "，已完整收录")
                              "。\n\n" fence "text\n"
                              (str/join "\n" (map-indexed (fn [i line] (str (inc i) " | " line)) (str/split-lines content)))
                              "\n" fence)))) source))))

(defn- render-question [question]
  (when-not (str/blank? question)
    (section "8. 请回答的问题"
             (str question "\n\n作答要求：\n"
                  "- 引用代码时写 `路径:行号`。\n"
                  "- 静态依赖中没有的调用关系必须标明为推断。\n"
                  "- 不确定就明确说不确定；不要把模块依赖当成函数调用的证据。"))))

(defn- markdown [{:keys [target architecture headers dependencies complexity references question] :as data}]
  (str "# 架构上下文包\n\n"
       (section "1. 目标" (render-target target))
       (section "2. 架构文档" (render-documents architecture))
       (section "3. 文件头说明" (render-headers headers))
       (section "4. 依赖" (render-dependencies dependencies))
       (section "5. 复杂度" (render-complexity complexity))
       (section "6. 已有解释与链路" (render-references references))
       (section "7. 源码" (render-source data))
       (render-question question)))
