;; 职责：为 query、context、trace、notes 等子命令提供公共参数解析、项目分析加载、结果输出与 JSON 文件读写。
;; 核心入口：解析参数（parse-args）；加载工作区（load-workspace）；输出结果（emit!）。

(ns arch-view.cli-support
  (:require [arch-view.config :as config]
            [arch-view.web.json :as json]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private pretty ^:private keywordize ^:private assoc-option)

(def common-values #{"--project-path" "--language" "--source-path" "--in-edn" "--format" "--env-file"})
(def common-flags #{"--help"})

(defn common-help
  "公共参数说明，供各子命令拼进自己的帮助文本。"
  []
  (str "  --project-path <目录>   指定项目；不指定时使用当前目录\n"
       "  --language <语言>       auto、clojure、python、kotlin、java\n"
       "  --source-path <目录>    指定源码目录，可重复传入\n"
       "  --in-edn <文件>         加载已导出的架构数据（源码仍按 --project-path 定位）\n"
       "  --format <md|json>      输出格式，默认 md\n"
       "  --env-file <文件>       配置文件，默认读取工具安装目录的 .env\n"
       "  --help                  查看当前命令帮助\n"))

(defn parse-args
  "返回 {:options {...} :positional [...]}；spec 为 {:values #{...} :flags #{...}}，公共参数总是可用。"
  [args {:keys [values flags]}]
  (let [values (into common-values values)
        flags (into common-flags flags)]
    (loop [args (seq args) options {:project-path "." :format "md"} positional []]
      (if-not args
        {:options options :positional positional}
        (let [[arg value] args]
          (cond
            (contains? flags arg) (recur (next args) (assoc options (keyword (subs arg 2)) true) positional)
            (contains? values arg)
            (do (when (or (nil? value) (str/starts-with? value "--"))
                  (throw (ex-info (str "缺少参数值：" arg) {})))
                (recur (nnext args) (assoc-option options arg value) positional))
            (str/starts-with? arg "--")
            (throw (ex-info (str "当前命令不支持参数：" arg "；可用 --help 查看帮助。") {}))
            :else (recur (next args) options (conj positional arg))))))))

(defn load-workspace
  "按命令行和全局配置分析项目，返回工作区；分析进度输出到标准错误，标准输出只留结果。"
  [options]
  (let [defaults (config/defaults (:env-file options) (into {} (System/getenv)))
        opts (merge (dissoc defaults :project-path :out) options)
        opts (if (seq (:source-paths options)) opts (assoc opts :source-paths (:source-paths defaults)))
        load-architecture (requiring-resolve 'arch-view.core/load-architecture)
        load-edn (requiring-resolve 'arch-view.core/load-architecture-edn)
        architecture (binding [*out* *err*]
                       (if (:in-edn opts)
                         (load-edn (:in-edn opts))
                         (load-architecture (:project-path opts) opts)))]
    (workspace/create architecture (:project-path opts) opts)))

(defn emit!
  "json 时输出数据本身，md 时输出 markdown-fn 生成的文本。"
  [options data markdown-fn]
  (println (if (= "json" (:format options)) (json/write-json data) (markdown-fn data)))
  (flush))

(defn read-json-file
  "读取 JSON 文件，对象键转成关键字。"
  [file]
  (let [file (io/file file)]
    (when-not (.isFile file) (throw (ex-info (str "文件不存在：" file) {:status 404})))
    (keywordize (json/read-json (slurp file :encoding "UTF-8")))))

(defn read-json-text [text]
  (keywordize (json/read-json text)))

(defn write-json-file!
  "写出两空格缩进的 JSON，自动创建目录。"
  [file data]
  (let [file (io/file file)]
    (io/make-parents file)
    (spit file (str (pretty data "") "\n") :encoding "UTF-8")
    file))

(defn valid-id? [id]
  (boolean (and (string? id) (re-matches #"[a-z0-9][a-z0-9-]{0,63}" id))))

;; ===== 私有方法 =====

(defn- assoc-option [options arg value]
  (case arg
    "--source-path" (update options :source-paths (fnil conj []) value)
    "--format" (if (#{"md" "json"} value) (assoc options :format value)
                   (throw (ex-info "--format 只能是 md 或 json" {})))
    (assoc options (keyword (subs arg 2)) value)))

(defn- keywordize [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(keyword k) (keywordize v)])) value)
    (sequential? value) (mapv keywordize value)
    :else value))

(defn- pretty [value indent]
  (let [inner (str indent "  ")]
    (cond
      (and (map? value) (seq value))
      (str "{\n" (str/join ",\n" (map (fn [[k v]] (str inner (json/write-json (if (keyword? k) (name k) (str k)))
                                                     ": " (pretty v inner))) value))
           "\n" indent "}")
      (and (coll? value) (not (map? value)) (seq value))
      (str "[\n" (str/join ",\n" (map #(str inner (pretty % inner)) value)) "\n" indent "]")
      :else (json/write-json value))))
