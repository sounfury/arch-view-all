;; 职责：自动发现 crap 命令，在后台统计函数圈复杂度，并按源码文件汇总给网页展示。
;; 核心入口：后台启动分析（start!）；汇总模块复杂度（node-metrics）；分析状态概览（summary）。

(ns arch-view.web.complexity
  (:require [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(declare ^:private find-command ^:private run-crap ^:private index-functions
         ^:private complexity-limit ^:private aggregate)

(def ^:private default-limit 10)
(def ^:private timeout-seconds 180)

(defn parse-mode [value]
  (let [mode (str/lower-case (str/trim (str value)))]
    (when-not (#{"auto" "off"} mode)
      (throw (ex-info "复杂度分析只能是 auto（发现 crap 时自动分析）或 off（关闭）。" {:value value})))
    mode))

(defn start!
  "crap 可用时在后台分析复杂度，结果写回 state 的 :complexity；分析期间页面照常可用。"
  [state ^java.nio.file.Path root opts]
  (let [command (find-command opts)
        token (Object.)
        limit (complexity-limit root)]
    (swap! state assoc :complexity (if command {:status "running" :token token :limit limit}
                                       {:status "unavailable" :limit limit}))
    (when command
      (future
        (let [result (try {:status "ready" :files (index-functions (run-crap command root))}
                          (catch Exception ex {:status "failed" :error (.getMessage ex)}))]
          (swap! state (fn [current]
                         ;; 重新分析会换新标记，过期任务的结果直接丢弃。
                         (if (identical? token (get-in current [:complexity :token]))
                           (update current :complexity merge result)
                           current))))))
    state))

(defn summary [{:keys [status error limit files]}]
  (cond-> {:status (or status "unavailable") :limit (or limit default-limit)}
    error (assoc :error error)
    (= status "ready") (merge (aggregate (mapcat val files)))))

(defn node-metrics
  "按模块对应的源码文件汇总复杂度；分析未完成或没有函数时返回 nil。"
  [{:keys [status files]} module-files]
  (when (= status "ready")
    (let [functions (for [[module path] module-files
                          :when path
                          function (get files path)]
                      (assoc function :module module))]
      (when (seq functions)
        (assoc (aggregate functions)
               :functions (vec (take 50 (sort-by (juxt (comp - :complexity) :module :line) functions))))))))

;; ===== 私有方法 =====

(defn- aggregate [functions]
  (let [values (map :complexity functions)]
    {:max (when (seq values) (apply max values)) :total (reduce + 0 values) :count (count values)}))

(defn- find-command [{:keys [crap crap-command]}]
  (when-not (= "off" crap)
    (or crap-command
        (let [windows? (str/starts-with? (str/lower-case (System/getProperty "os.name")) "windows")
              names (if windows? ["crap.exe" "crap.cmd" "crap.bat"] ["crap"])
              dirs (str/split (or (System/getenv "PATH") "")
                              (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))]
          (some (fn [dir]
                  (some #(let [file (io/file dir %)]
                           (when (and (not (str/blank? dir)) (.isFile file) (.canExecute file)) (.getPath file)))
                        names))
                dirs)))))

(defn- run-crap [command ^java.nio.file.Path root]
  (let [error-file (java.io.File/createTempFile "arch-view-crap-" ".log")]
    (try
      (let [builder (doto (ProcessBuilder. ^java.util.List [command "complexity" (str root) "--json"])
                      (.redirectError error-file))
            ;; crap 是 Python 程序，强制 UTF-8，避免 Windows 管道按本地编码输出中文。
            _ (doto (.environment builder) (.put "PYTHONUTF8" "1") (.put "PYTHONIOENCODING" "utf-8"))
            process (.start builder)
            output (future (with-open [in (.getInputStream process)] (slurp in :encoding "UTF-8")))]
        (.close (.getOutputStream process))
        (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
          (.destroyForcibly process)
          (throw (ex-info (str "复杂度分析超过 " timeout-seconds " 秒，已停止") {})))
        (let [text @output]
          (try
            (json/read-json text)
            (catch Exception _
              (let [reason (first (remove str/blank? (str/split-lines (slurp error-file :encoding "UTF-8"))))]
                (throw (ex-info (str "crap 未返回有效结果（退出码 " (.exitValue process) "）"
                                     (when reason (str "：" reason))) {})))))))
      (finally (.delete error-file)))))

(defn- index-functions [result]
  (reduce (fn [files {:strs [project_path entries]}]
            (reduce (fn [files {:strs [file line location symbol complexity]}]
                      ;; 旧版 crap 只有“相对路径:行号”形式的位置，拆开后同样可用。
                      (let [[_ located-file located-line] (re-matches #"(.+):(\d+)" (str location))
                            file (or file located-file)]
                        (if (and file project_path (number? complexity))
                          (update files (.getCanonicalPath (io/file project_path file)) (fnil conj [])
                                  {:symbol symbol :line (or line (some-> located-line parse-long))
                                   :complexity complexity})
                          files)))
                    files entries))
          {} (get result "projects")))

(defn- complexity-limit [^java.nio.file.Path root]
  ;; 与 crap 项目门禁使用同一上限；没有配置时按常用经验值 10。
  (let [file (io/file (.toFile root) "crap.toml")
        gate (when (.isFile file)
               (second (re-find #"(?s)\[gate\](.*?)(?:\n\s*\[|\z)" (slurp file :encoding "UTF-8"))))]
    (or (some->> (or gate "") (re-find #"(?m)^\s*max_complexity\s*=\s*(\d+)") second parse-long)
        default-limit)))
