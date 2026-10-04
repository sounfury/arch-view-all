;; 职责：统一处理 arch-view 命令，选择网页、桌面或无界面分析。
;; 核心入口：主启动函数（-main）；保留原有参数直接启动桌面的用法。

(ns arch-view.cli
  (:require [clojure.string :as str]))

(declare ^:private dispatch! ^:private normalize-project-args ^:private validate-options!
         ^:private common-help ^:private command-help)

(defn usage-summary
  ([] (usage-summary nil))
  ([command]
   (str "用法：arch-view " (or command "<命令>") " [项目目录] [参数]\n\n"
        (if command (command-help command)
            (str "命令：\n"
                 "  serve     启动网页工作台，默认自动打开浏览器\n"
                 "  desktop   启动桌面架构窗口\n"
                 "  scan      分析源码，可导出架构数据，不打开窗口\n"
                 "  help      查看帮助\n\n"
                 "不传参数时显示帮助；仅传旧分析参数时仍启动桌面窗口。\n"
                 "示例：arch-view serve .\n"
                 "      arch-view scan . --out architecture.edn\n"
                 "命令帮助：arch-view serve --help\n")))))

(defn -main [& args]
  (try
    (dispatch! args)
    (catch Exception ex
      (binding [*out* *err*] (println (str "启动失败：" (.getMessage ex))))
      (System/exit 1))))

;; ===== 私有方法 =====

(def ^:private commands #{"serve" "desktop" "scan"})
(def ^:private common-value-options #{"--project-path" "--language" "--source-path" "--in-edn"})
(def ^:private desktop-value-options #{"--env-file" "--zoom" "--ui-scale" "--out"})

(defn- common-help []
  (str "  --project-path <目录>   指定项目；不指定时使用当前目录\n"
       "  --language <语言>       auto、clojure、python、kotlin、java\n"
       "  --source-path <目录>    指定源码目录，可重复传入\n"
       "  --in-edn <文件>         加载已有的架构数据\n"
       "  --help                  查看当前命令帮助\n"))

(defn- command-help [command]
  (case command
    "serve" (str "启动本机网页工作台：\n" (common-help)
                 "  --port <端口>           默认 7331，被占用时自动换端口；0 自动分配\n"
                 "  --architecture-doc <文件>  读取已有的架构说明\n"
                 "  --env-file <文件>       配置文件，默认读取工具安装目录的 .env\n"
                 "  --edge-scope <focus|all> 默认连线范围：当前模块或全部模块\n"
                 "  --crap <auto|off>       发现 crap 命令时在后台分析函数复杂度，默认 auto\n"
                 "  --crap-command <命令>   crap 不在 PATH 上时指定其路径\n"
                 "  --no-browser            启动服务但不自动打开浏览器\n"
                 "就绪后输出地址和进程号（PID），服务持续占用前台；按 Ctrl+C 停止。\n"
                 "自动化调用请使用持久或后台进程，并添加 --no-browser。\n")
    ("desktop" "scan")
    (str (if (= command "scan") "分析源码，不打开窗口：\n" "启动桌面窗口：\n")
         (common-help)
         "  --out <文件>            导出架构数据（EDN）\n"
         "  --env-file <文件>       指定配置文件，默认读取工具安装目录的 .env\n"
         "  --include-tests / --exclude-tests  包含或排除 Python 测试\n"
         "  --zoom <数值>           架构图初始缩放\n"
         "  --ui-scale <数值>       桌面界面缩放\n"
         (when (= command "desktop") "  --gui / --no-gui         打开或关闭桌面窗口\n"))
    (throw (ex-info (str "未知命令：" command) {}))))

(defn- normalize-project-args [args]
  (if (and (seq args) (not (str/starts-with? (first args) "-")))
    (do
      (when (some #{"--project-path"} (rest args))
        (throw (ex-info "项目目录只能指定一次，请选择位置参数或 --project-path。" {})))
      (concat ["--project-path" (first args)] (rest args)))
    args))

;; 各命令允许的参数：values 需要取值，flags 是开关。
(def ^:private command-options
  {"serve" {:values (into common-value-options #{"--port" "--architecture-doc" "--env-file" "--edge-scope"
                                                 "--crap" "--crap-command"})
            :flags #{"--no-browser"}}
   "scan" {:values (into common-value-options desktop-value-options)
           :flags #{"--include-tests" "--exclude-tests" "--no-gui"}}
   "desktop" {:values (into common-value-options desktop-value-options)
              :flags #{"--include-tests" "--exclude-tests" "--gui" "--no-gui"}}})

(defn- missing-value? [value]
  (or (nil? value) (str/starts-with? value "--")))

(defn- validate-options! [command args]
  (let [{:keys [values flags]} (command-options command)]
    (loop [remaining (seq args)]
      (when-let [[option value] remaining]
        (cond
          (contains? flags option) (recur (next remaining))
          (not (contains? values option))
          (throw (ex-info (str "当前命令不支持参数：" option "；可用 --help 查看帮助。") {}))
          (missing-value? value) (throw (ex-info (str "缺少参数值：" option) {}))
          :else (recur (nnext remaining)))))))

(defn- resolve-command
  "返回 [命令 命令参数]；没写命令、直接传旧分析参数时按桌面版处理。"
  [args]
  (let [first-arg (first args)
        explicit? (contains? commands first-arg)]
    (when (and first-arg (not explicit?) (not (str/starts-with? first-arg "-")))
      (throw (ex-info (str "未知命令：" first-arg "；请运行 arch-view --help。") {})))
    [(if explicit? first-arg "desktop") (normalize-project-args (if explicit? (rest args) args))]))

(defn- run-scan! [args]
  (println "正在分析源码，不打开窗口……")
  (flush)
  (apply (requiring-resolve 'arch-view.core/-main) (concat args ["--no-gui"]))
  (println "源码分析已完成。")
  (flush)
  (shutdown-agents))

(defn- run-command! [command args]
  (case command
    "serve" (apply (requiring-resolve 'arch-view.web.server/-main) args)
    "scan" (run-scan! args)
    "desktop" (apply (requiring-resolve 'arch-view.core/-main) args)))

(defn- dispatch! [args]
  (if (or (nil? (first args)) (contains? #{"--help" "help"} (first args)))
    (println (usage-summary (second args)))
    (let [[command command-args] (resolve-command args)]
      (if (some #{"--help"} command-args)
        (println (usage-summary command))
        (do (validate-options! command command-args)
            (run-command! command command-args))))))
