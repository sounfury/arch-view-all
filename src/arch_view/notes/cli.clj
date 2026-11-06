;; 职责：提供解释保存、列表、详情和过期检查命令，输出中文 Markdown 或 JSON。
;; 核心入口：命令执行（run!）。

(ns arch-view.notes.cli
  (:refer-clojure :exclude [run!])
  (:require [arch-view.cli-support :as support]
            [arch-view.notes.core :as notes]
            [arch-view.notes.store :as store]
            [clojure.string :as str]))

(declare ^:private usage ^:private execute! ^:private validate-command!
         ^:private save! ^:private list! ^:private show! ^:private check!
         ^:private note-line ^:private list-markdown
         ^:private show-markdown ^:private anchor-line ^:private strict-failure?)

(defn run! [args]
  (let [{:keys [options positional]}
        (support/parse-args args {:values #{"--target"} :flags #{"--force" "--fix-moved" "--strict"}})]
    (if (or (:help options) (empty? args))
      (println (usage))
      (do (validate-command! positional options)
          (execute! (support/load-workspace options) options positional)))))

;; ===== 私有方法 =====

(defn- usage []
  (str "用法：arch-view notes <命令> [参数]\n\n"
       "  save <草稿.json> [--force]  保存解释；显式 id 已存在时需 --force\n"
       "  list [--target <目标>]      列出解释，支持模块、源码文件和包\n"
       "  show <id>                   显示正文和锚点状态\n"
       "  check [--fix-moved] [--strict]  检查全部解释；修复移动行号\n"
       "  --strict                    有 stale、missing 或目标失效时返回退出码 2\n\n"
       "最小草稿 JSON（id 缺省时按标题生成，重名自动追加序号）：\n"
       "  {\"title\":\"模块说明\",\"body\":\"Markdown 正文\",\"targets\":[\"arch-view.web.model\"]}\n\n"
       (support/common-help)))

(defn- validate-command! [[command :as positional] options]
  (let [arity ({"save" 2 "list" 1 "show" 2 "check" 1} command)
        allowed ({"save" #{:force} "list" #{:target} "show" #{} "check" #{:fix-moved :strict}} command)]
    (when-not (= arity (count positional))
      (throw (ex-info "解释命令或参数不正确，请用 notes --help 查看帮助" {:status 400})))
    (doseq [option [:force :target :fix-moved :strict]]
      (when (and (contains? options option) (not (contains? allowed option)))
        (throw (ex-info (str "当前解释命令不支持 --" (name option)) {:status 400}))))))

(defn- execute! [ws options [command argument]]
  (case command
    "save" (save! ws options argument)
    "list" (list! ws options)
    "show" (show! ws options argument)
    "check" (check! ws options)))

(defn- save! [ws options file]
  (let [note (->> (support/read-json-file file) (notes/prepare ws))
        saved (store/write-note! ws note (:force options))]
    (support/emit! options (notes/check ws saved) show-markdown)))

(defn- list! [ws options]
  (support/emit! options (notes/checked-notes ws (:target options)) list-markdown))

(defn- show! [ws options id]
  (support/emit! options (notes/check ws (store/read-note ws id)) show-markdown))

(defn- check! [ws options]
  (let [checked (mapv (fn [note]
                        (let [result (notes/check ws note)]
                          (when (:fix-moved options)
                            (let [fixed (notes/fix-moved note result)]
                              (when (not= note fixed) (store/write-note! ws fixed true))))
                          result))
                      (store/list-notes ws))]
    (support/emit! options checked list-markdown)
    (if (and (:strict options) (some strict-failure? checked)) 2 0)))

(defn- strict-failure? [note]
  (or (#{"stale" "missing"} (:status note)) (:targetMissing note)))

(defn- note-line [{:keys [status title id targets author createdAt targetMissing]}]
  (str "[" status (when targetMissing "/目标失效") "] "
       (str/replace title #"[\r\n]+" " ") "（" id "）— " (str/join "、" targets)
       " — " author "/" (subs (str createdAt) 0 (min 10 (count (str createdAt))))))

(defn- list-markdown [notes]
  (if (seq notes) (str/join "\n" (map note-line notes)) "暂无解释。"))

(defn- anchor-line [{:keys [path startLine endLine status currentStartLine currentEndLine]}]
  (str "- [" status "] " path ":" startLine "-" endLine
       (when (= status "moved") (str " → " currentStartLine "-" currentEndLine))))

(defn- show-markdown [note]
  (str (note-line note) "\n\n" (:body note) "\n\n锚点：\n"
       (if (seq (:anchors note)) (str/join "\n" (map anchor-line (:anchors note))) "无锚点。")))
