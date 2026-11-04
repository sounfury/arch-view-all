;; 职责：保存、浏览和逐跳校验链路，输出便于人工与 AI 阅读的 Markdown 或 JSON。
;; 核心入口：命令入口（run!）；完整校验文本（markdown）。

(ns arch-view.trace.cli
  (:refer-clojure :exclude [run!])
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.trace.store :as store]
            [arch-view.trace.verify :as verify]
            [clojure.string :as str]))

(declare ^:private help-text ^:private validate-command! ^:private run-command!
         ^:private save! ^:private verify! ^:private verify-command! ^:private list-data
         ^:private list-markdown ^:private step-markdown ^:private hop-markdown
         ^:private summary-markdown ^:private next-steps ^:private many-markdown)

(defn run! [args]
  (let [{:keys [options positional]}
        (cli-support/parse-args args {:flags #{"--force" "--all" "--fix-moved" "--strict"}})
        [command & targets] positional]
    (if (or (:help options) (nil? command))
      (do (println (help-text)) 0)
      (do
        (validate-command! command targets options)
        (run-command! (cli-support/load-workspace options) command targets options)))))

(defn markdown [{:keys [trace steps hops summary verdict]}]
  (str "# " (:title trace) "（" (:id trace) "）\n\n"
       (when (seq (:question trace)) (str "问题：" (:question trace) "\n\n"))
       (when (seq (:summary trace)) (str (:summary trace) "\n\n"))
       (str/join "\n" (map #(step-markdown trace %) steps))
       "\n\n逐跳静态证据：\n"
       (if (seq hops) (str/join "\n" (map hop-markdown hops)) "只有一步，无需校验跳转。")
       "\n\n汇总：" (summary-markdown summary) "；结论：" verdict
       "\n\n下一步：" (next-steps summary)))

;; ===== 私有方法 =====

(defn- help-text []
  (str "用法：arch-view trace <命令> [参数]\n\n"
       "  save <草稿.json> [--force]   补全锚点、校验并保存；--force 覆盖同 id\n"
       "  list                       列出链路和当前校验摘要\n"
       "  show <id>                  查看链路及当前逐跳证据\n"
       "  verify [<id>|--all] [--fix-moved] [--strict]\n"
       "                             不传 id 时校验全部；--fix-moved 保存移动后的行号\n"
       "                             --strict 遇到 stale、missing、unverified 或虚线关系时退出码为 2\n\n"
       (cli-support/common-help)
       "\n最小草稿 JSON（行号从 1 起，路径相对项目根）：\n"
       "{\"id\":\"example\",\"title\":\"示例链路\",\"steps\":[{\"title\":\"入口\","
       "\"anchor\":{\"path\":\"src/main.clj\",\"startLine\":1}}]}\n"
       "\n每步 via 可选：call、http、event、db、config、return、other，描述上一步到本步。\n"
       "示例：arch-view trace save /tmp/trace.json --project-path .\n"
       "      arch-view trace verify example --format json --strict\n"))

(def ^:private command-spec
  {"save" {:arity #{1} :flags #{:force}}
   "list" {:arity #{0} :flags #{}}
   "show" {:arity #{1} :flags #{}}
   "verify" {:arity #{0 1} :flags #{:all :fix-moved :strict}}})

(defn- validate-command! [command targets options]
  (let [{:keys [arity flags]} (get command-spec command)]
    (when-not arity (throw (ex-info (str "未知链路命令：" command "；请用 --help 查看帮助") {:status 400})))
    (when-not (contains? arity (count targets))
      (throw (ex-info (str "trace " command " 的位置参数数量不正确；请用 --help 查看帮助") {:status 400})))
    (doseq [flag [:force :all :fix-moved :strict]]
      (when (and (get options flag) (not (contains? flags flag)))
        (throw (ex-info (str "trace " command " 不支持 --" (name flag)) {:status 400}))))
    (when (and (:all options) (seq targets))
      (throw (ex-info "--all 不能与链路 id 同时使用" {:status 400})))))

(defn- save! [ws file options]
  (let [trace (verify/prepare ws (cli-support/read-json-file file))
        checked (verify/verify ws trace)]
    (store/write-trace! ws trace (:force options))
    checked))

(defn- verify! [ws id options]
  (let [trace (store/read-trace ws id)
        checked (verify/verify ws trace)]
    (if (and (:fix-moved options) (pos? (get-in checked [:summary :moved])))
      (let [fixed (verify/fix-moved trace checked)]
        (store/write-trace! ws fixed true)
        (verify/verify ws fixed))
      checked)))

(defn- list-data [ws]
  (mapv (fn [trace]
          {:id (:id trace) :title (:title trace) :question (:question trace)
           :stepCount (count (:steps trace)) :summary (:summary (verify/verify ws trace))})
        (store/list-traces ws)))

(defn- run-command! [ws command targets options]
  (case command
    "save" (do (cli-support/emit! options (save! ws (first targets) options) markdown) 0)
    "show" (do (cli-support/emit! options (verify! ws (first targets) options) markdown) 0)
    "list" (do (cli-support/emit! options (list-data ws) list-markdown) 0)
    "verify" (verify-command! ws (first targets) options)))

(defn- verify-command! [ws id options]
  (let [ids (if id [id] (store/list-ids ws))
        results (mapv #(verify! ws % options) ids)]
    (if id
      (cli-support/emit! options (first results) markdown)
      (cli-support/emit! options results many-markdown))
    (if (and (:strict options) (some #(not= "ok" (:verdict %)) results)) 2 0)))

(defn- step-markdown [trace {:keys [index module anchor]}]
  (let [step (nth (:steps trace) index)
        start (or (:currentStartLine anchor) (:startLine anchor))
        end (or (:currentEndLine anchor) (:endLine anchor))]
    (str (inc index) ". " (:title step) " — " (:path anchor) ":" start "-" end
         "（" (or module "未映射模块") "）[" (:status anchor) "]")))

(def ^:private hop-icons
  {"direct" "✓" "indirect" "≈" "reverse" "↩" "unverified" "?" "unmapped" "·" "same-module" "="})

(defn- hop-markdown [{:keys [from to fromModule toModule status path reason uncertain]}]
  (str "- " (inc from) " → " (inc to) "：" (get hop-icons status) " " status
       "（" (or fromModule "未映射") " → " (or toModule "未映射") "）"
       (when (= "indirect" status) (str "；路径：" (str/join " → " path)))
       (when uncertain (str "；虚线：" reason))))

(defn- summary-markdown [summary]
  (str "锚点 " (str/join "、" (map #(str (name %) " " (get summary % 0)) [:fresh :moved :stale :missing]))
       "；跳转 " (str/join "、" (map #(str (name %) " " (get summary % 0))
                                   [:same-module :direct :indirect :reverse :unverified :unmapped]))
       (when (:uncertain summary) (str "；不确定关系 " (:uncertain summary)))))

(defn- next-steps [{:keys [moved stale missing unverified uncertain] :or {uncertain 0}}]
  (let [advice (cond-> []
                 (pos? (+ stale missing)) (conj (str "有 " (+ stale missing) " 处锚点过期或缺失，请检查源码并重新保存链路"))
                 (pos? moved) (conj (str "有 " moved " 处锚点移动，可用 verify --fix-moved 更新行号"))
                 (pos? unverified) (conj (str "有 " unverified " 跳缺少静态证据，请人工复核"))
                 (pos? uncertain) (conj (str "有 " uncertain " 条多实现、异步或动态关系，虚线原因已保留，请对照源码复核")))]
    (if (seq advice) (str/join "；" advice) "锚点有效，静态证据校验通过；可在网页查看链路。")))

(defn- list-markdown [entries]
  (if (seq entries)
    (str "# 链路列表\n\n"
         (str/join "\n" (map #(str "- " (:id %) "：" (:title %) "（" (:stepCount %) " 步）；"
                                  (summary-markdown (:summary %))) entries)))
    "暂无链路。可用 arch-view trace save <草稿.json> 保存第一条链路。"))

(defn- many-markdown [results]
  (if (seq results) (str/join "\n\n---\n\n" (map markdown results)) "暂无链路可校验。"))
