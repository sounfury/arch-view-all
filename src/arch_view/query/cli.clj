;; 职责：解析精确查询与 Git 改动影响命令，输出紧凑中文 Markdown 或 JSON。
;; 核心入口：run!。

(ns arch-view.query.cli
  (:refer-clojure :exclude [run!])
  (:require [arch-view.cli-support :as support]
            [arch-view.query.core :as query]
            [arch-view.query.impact :as impact]
            [arch-view.workspace :as workspace]
            [clojure.string :as str]))

(declare ^:private help-text ^:private execute ^:private markdown ^:private number-option
         ^:private validate-arguments ^:private path-data ^:private table ^:private cell
         ^:private module-table ^:private cycle-text ^:private impact-text ^:private summary-text)

(def ^:private commands
  {"deps" {:arity 1 :usage "deps <目标> [--depth N]" :description "下游依赖；默认深度 1。"
           :values #{"--depth"}}
   "rdeps" {:arity 1 :usage "rdeps <目标> [--depth N]" :description "上游调用方；默认深度 1。"
            :values #{"--depth"}}
   "path" {:arity 2 :usage "path <起点> <终点> [--max N] [--max-depth N]"
           :description "依赖路径，最短优先；默认最多 5 条，深度默认覆盖全部模块。"
           :values #{"--max" "--max-depth"}}
   "cycles" {:arity 0 :usage "cycles" :description "全部成环强连通分量及内部依赖边。"}
   "layers" {:arity 0 :usage "layers [--prefix 目标]" :description "按层号列出模块。" :values #{"--prefix"}}
   "find" {:arity 1 :usage "find <关键字> [--limit N]" :description "搜索模块名、路径、文件头；默认最多 20 项。"
           :values #{"--limit"}}
   "module" {:arity 1 :usage "module <目标>" :description "成员、层级、直接上下游数量、依赖环与文件头说明。"}
   "impact" {:arity 0 :usage "impact [--since 引用] [--staged]"
             :description (str "默认 HEAD 到工作区，含暂存、未暂存、删除和未跟踪文件。\n"
                               "  --since 使用 git merge-base <引用> HEAD 到工作区，并合并 HEAD 到工作区改动和未跟踪文件。\n"
                               "  这是三点比较到工作区的语义；git diff <引用>... 本身只比较提交，不含工作区。\n"
                               "  --staged 仅暂存区（可用于尚无 HEAD 的仓库），不能与 --since 同用。\n"
                               "  文件路径相对项目根；仓库中项目外的文件会以 ../ 路径列为未映射。")
             :values #{"--since"} :flags #{"--staged"}}})

(defn run! [args]
  (let [command (first args)
        spec (get commands command)
        {:keys [options positional]} (support/parse-args (if spec (rest args) args) spec)]
    (if (or (:help options) (empty? args))
      (println (help-text (when spec command)))
      (do (when-not spec (throw (ex-info (str "未知查询子命令：" command "；请运行 arch-view query --help") {:status 400})))
          (validate-arguments command positional options)
          (let [ws (support/load-workspace options)
                data (execute ws command positional options)]
            (support/emit! options data #(markdown command %)))))))

;; ===== 私有方法 =====

(defn- help-text [command]
  (str "用法：arch-view " (if (= command "impact") "" "query ")
       (if command (:usage (commands command)) "<子命令> [参数]") "\n\n"
       (if command (str (:description (commands command)) "\n")
           (str "精确查询，目标可为模块、源码文件或包；包只统计包外依赖。\n"
                (str/join "\n" (for [name ["deps" "rdeps" "path" "cycles" "layers" "find" "module"]]
                                  (str "  " (:usage (commands name)) "  " (:description (commands name)))))
                "\n改动影响：arch-view impact [--since 引用] [--staged]\n"))
       "\n数字参数必须是正整数；Markdown 表格最多展示 50 项，并注明总数；JSON 保留查询结果。\n"
       (support/common-help)))

(defn- number-option [options key default]
  (let [value (get options key default)
        n (if (integer? value) value (parse-long (str value)))]
    (when-not (and n (pos? n))
      (throw (ex-info (str "--" (name key) " 必须是正整数") {:status 400})))
    n))

(defn- validate-arguments [command positional options]
  (let [{:keys [arity usage]} (commands command)]
    (when (< (count positional) arity)
      (throw (ex-info (str "缺少参数。用法：arch-view " (when-not (= command "impact") "query ") usage) {:status 400})))
    (when (> (count positional) arity)
      (throw (ex-info (str "多余的位置参数。用法：" usage) {:status 400})))
    (doseq [key [:depth :max :max-depth :limit] :when (contains? options key)]
      (number-option options key nil))
    (when (and (:since options) (:staged options))
      (throw (ex-info "--since 与 --staged 不能同时使用" {:status 400})))))

(defn- dependency-data [ws command target options]
  (query/dependencies ws target {:direction ({"deps" :out "rdeps" :in} command)
                                 :depth (number-option options :depth 1)}))

(defn- execute [ws command [target to] options]
  (case command
    ("deps" "rdeps") (dependency-data ws command target options)
    "path" (path-data ws target to options)
    "cycles" (query/cycles ws)
    "layers" (query/layers ws {:prefix (:prefix options)})
    "find" (query/find-modules ws target {:limit (number-option options :limit 20)})
    "module" (query/module-summary ws target)
    "impact" (impact/impact ws (select-keys options [:since :staged]))))

(defn- path-data [ws from to options]
  (let [paths (query/paths ws from to (cond-> {:max (number-option options :max 5)}
                                       (:max-depth options) (assoc :max-depth (number-option options :max-depth nil))))
        modules (set (mapcat identity paths))]
    {:from (workspace/resolve-target ws from) :to (workspace/resolve-target ws to)
     :paths paths :modules (vec (filter #(contains? modules (:module %))
                                       (mapcat :items (:layers (query/layers ws {})))))}))

(defn- cell [value]
  (-> (if (keyword? value) (name value) (str value))
      (str/replace "|" "\\|") (str/replace #"[\r\n]+" " / ")))

(defn- table [headers rows total]
  (let [shown (vec (take 50 rows))]
    (str "共 " total " 项，显示 " (count shown) " 项。\n\n"
         "| " (str/join " | " headers) " |\n"
         "| " (str/join " | " (repeat (count headers) "---")) " |\n"
         (str/join "\n" (map #(str "| " (str/join " | " (map cell %)) " |") shown))
         (when (< (count shown) total) (str "\n…已截断，总数 " total "。")))))

(defn- module-table [items total]
  (table ["模块" "距离" "层" "类型" "环" "源码" "说明"]
         (map (fn [{:keys [module distance layer type cycle path description]}]
                [module distance layer type (if cycle "是" "否") path description]) items) total))

(defn- cycle-text [items]
  (if (empty? items) "无依赖环。"
      (str "依赖环：" (count items) " 个。\n\n"
           (str/join "\n\n"
                     (map-indexed
                      (fn [index {:keys [modules edges members]}]
                        (str "环 " (inc index) "：" (str/join "、" modules) "\n\n"
                             (module-table members (count modules)) "\n\n"
                             (table ["起点" "终点" "类型"] (map (juxt :from :to :type) edges) (count edges))))
                      (take 20 items)))
           (when (> (count items) 20) (str "\n…已截断，总数 " (count items) " 个环。")))))

(defn- summary-text [{:keys [target member-count members layers dependencies cycles]}]
  (str "目标：" (:target target) "（" (name (:kind target)) "），成员 " member-count
       "，层：" (str/join "、" layers) "，包外直接下游 " (:out dependencies)
       "，上游 " (:in dependencies) "。\n\n"
       (module-table members member-count) "\n\n" (cycle-text cycles)))

(defn- impact-text [{:keys [base staged files modules unmapped upstream upstream-count cycles]}]
  (str "改动影响：" (if staged "仅暂存区" (str "基准 " base " → 工作区（含未跟踪）")) "。\n\n"
       (table ["改动文件" "模块"] (map (juxt :path :module) files) (count files))
       "\n\n改动模块 " (count modules) "，未映射文件 " (count unmapped) "。\n"
       (when (seq unmapped) (str "未映射：" (str/join "、" (take 50 unmapped)) "\n"))
       "\n受影响上游（按最短距离分组）：\n\n"
       (module-table (mapcat :items upstream) upstream-count)
       "\n\n" (cycle-text cycles)))

(defn- markdown [command data]
  (case command
    ("deps" "rdeps") (str "目标：" (get-in data [:target :target]) "；"
                            (if (= command "deps") "下游依赖" "上游调用方") "。\n\n"
                            (module-table (:items data) (:total data)))
    "find" (str "搜索：" (:keyword data) "。\n\n" (module-table (:items data) (:total data)))
    "cycles" (cycle-text (:items data))
    "layers" (str "分层：" (count (:layers data)) " 层。\n\n"
                  (module-table (mapcat :items (:layers data)) (:total data)))
    "module" (summary-text data)
    "impact" (impact-text data)
    "path" (str "依赖路径：" (count (:paths data)) " 条（最短优先，受 --max 限制）。\n"
                (str/join "\n" (map-indexed #(str (inc %1) ". " (str/join " → " %2)) (:paths data)))
                "\n\n" (module-table (:modules data) (count (:modules data))))))
