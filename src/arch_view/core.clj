;; 职责：组织桌面和网页共用的源码分析流程；同时处理桌面启动参数、架构导出和窗口启动。
;; 核心入口：项目分析函数（load-architecture）；桌面与导出启动函数（-main）。

;; mutation-tested: 2026-03-08
(ns arch-view.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [arch-view.config :as config]
            [clojure.string :as str]
            [arch-view.input.languages :as languages]
            [arch-view.layout.layers :as layers]
            [arch-view.model.classify :as classify]
            [arch-view.model.components :as components]
            [arch-view.render.ui.quil.view :as render]))

(def default-guidance
  {:source-paths ["src"]
   :component-rules []})

(defn usage-summary
  []
  (str
   "Usage: clj -M:run [options]\n"
   "\n"
   "Options:\n"
   "  --env-file <file>          Load defaults from this file (default: .env in the arch-view install directory).\n"
   "  --zoom <number>            Initial diagram and label zoom (default: 1.0).\n"
   "  --ui-scale <number>        UI and font scaling, e.g. 1.25.\n"
   "  --include-tests            Include Python test files (excluded by default).\n"
   "  --exclude-tests            Exclude Python test files.\n"
   "  --gui                      Override headless configuration.\n"
   "  --help                     Print this usage summary and exit.\n"
   "  --project-path <path>      Project root to scan (default: current directory).\n"
   "  --language <name>          auto (default), clojure, python, kotlin or java.\n"
   "  --source-path <path>       Override auto-discovered roots; repeat for multiple roots.\n"
   "  --in-edn <file>            Load architecture from an EDN file instead of scanning source.\n"
   "  --out <file>               Write architecture EDN output to file.\n"
   "  --no-gui                   Run headless (do not open the interactive viewer).\n"))

(defn load-architecture
  ([project-path] (load-architecture project-path {}))
  ([project-path {:keys [language source-paths] :as opts}]
  (let [language (languages/resolve-language project-path source-paths language)
        source-paths (or (seq source-paths) (languages/default-source-paths project-path language))
        guidance (assoc default-guidance :source-paths (vec source-paths) :language language
                        :namespace-root-depth (if (= :clojure language) 1 0))
        graph (languages/build-module-graph project-path source-paths language opts)
        module->component (components/assign-components guidance (:nodes graph))
        layout (layers/assign-layers graph)
        classified-edges (classify/classify-edges guidance graph)
        scene (render/build-scene {:layout layout
                                   :classified-edges classified-edges
                                   :module->component module->component})]
    {:guidance guidance
     :graph graph
     :module->component module->component
     :layout layout
     :classified-edges classified-edges
     :scene scene})))

(defn load-architecture-edn
  [path]
  (let [architecture (-> path slurp edn/read-string)]
    (if (:scene architecture)
      architecture
      (assoc architecture
             :scene (render/build-scene architecture)))))

;; 开关参数：参数名 -> [选项键 取值]。
(def ^:private flag-options
  {"--include-tests" [:include-tests true]
   "--exclude-tests" [:include-tests false]
   "--gui" [:no-gui false]
   "--no-gui" [:no-gui true]
   "--help" [:help true]})

(def ^:private value-options
  {"--env-file" :env-file
   "--ui-scale" :ui-scale
   "--zoom" :zoom
   "--project-path" :project-path
   "--language" :language
   "--source-path" :source-paths
   "--in-edn" :in-edn
   "--out" :out})

(defn- required-value [arg value]
  (when (or (nil? value) (str/starts-with? value "--"))
    (throw (ex-info (str "Missing value for " arg) {:option arg})))
  value)

(defn- parse-option-value [key-name value]
  (case key-name
    :language (languages/language-key value)
    :ui-scale (config/parse-scale value)
    :zoom (Double/parseDouble (config/parse-scale value))
    value))

(defn- assoc-value-option [opts key-name value]
  (if (= key-name :source-paths)
    (update opts key-name (fnil conj []) value)
    (assoc opts key-name (parse-option-value key-name value))))

(defn parse-args
  ([args] (parse-args args {}))
  ([args defaults]
   (loop [remaining args
          opts (merge {:project-path "." :in-edn nil :no-gui false :out nil :help false}
                      (if (some #{"--source-path"} args) (dissoc defaults :source-paths) defaults))]
     (let [arg (first remaining)]
       (cond
         (empty? remaining) opts
         (contains? flag-options arg)
         (let [[key-name value] (flag-options arg)] (recur (next remaining) (assoc opts key-name value)))
         (contains? value-options arg)
         (recur (nnext remaining)
                (assoc-value-option opts (value-options arg) (required-value arg (second remaining))))
         ;; 不认识的参数沿用旧行为：跳过。
         :else (recur (next remaining) opts))))))

(defn exit-program!
  []
  (shutdown-agents)
  (System/exit 0))

(defn -main [& args]
  (let [cli (parse-args args)
        {:keys [project-path in-edn no-gui out help] :as opts}
        (if (:help cli) cli
            (parse-args args (config/defaults (:env-file cli) (into {} (System/getenv)))))]
    (if help
      (println (usage-summary))
      (let [_ (when (and (not no-gui) (java.awt.GraphicsEnvironment/isHeadless))
                ;; 无图形界面时先拦下，免得分析完才失败且退出码仍为 0。
                (throw (ex-info (str "当前环境没有图形界面，无法打开桌面窗口。"
                                     "请改用 arch-view serve（远程服务器可通过 SSH 端口转发在本机浏览器查看），"
                                     "或用 arch-view scan 只做分析与导出。") {})))
            _ (when-let [scale (:ui-scale opts)] (System/setProperty "sun.java2d.uiScale" scale))
            architecture (if in-edn
                           (load-architecture-edn in-edn)
                           (load-architecture project-path opts))
            source-label (or in-edn project-path)
            {:keys [graph scene]} architecture]
        (println "Architecture loaded")
        (when-let [language (get-in architecture [:guidance :language])]
          (println "Language:" (name language))
          (println "Source paths:" (str/join ", " (get-in architecture [:guidance :source-paths]))))
        (println "Nodes:" (count (:nodes graph)))
        (println "Edges:" (count (:edges graph)))
        (when out
          (spit out (pr-str architecture))
          (println (str "架构数据已导出：" (.getCanonicalPath (io/file out)))))
        (when-not no-gui
          (-> (render/show! scene {:title (str "architecture-viewer: " (str/trim source-label))
                                   :architecture architecture
                                   :zoom (:zoom opts)
                                   :reload-architecture (when-not in-edn
                                                          (fn []
                                                            (load-architecture project-path opts)))})
              (render/wait-until-closed!))
          (exit-program!))))))
