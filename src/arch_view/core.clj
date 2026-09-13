;; mutation-tested: 2026-03-08
(ns arch-view.core
  (:require [clojure.edn :as edn]
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
   "  --help                     Print this usage summary and exit.\n"
   "  --project-path <path>      Project root to scan (default: current directory).\n"
   "  --language <name>          clojure (default) or python.\n"
   "  --source-path <path>       Source root relative to project; repeat for multiple roots.\n"
   "  --in-edn <file>            Load architecture from an EDN file instead of scanning source.\n"
   "  --out <file>               Write architecture EDN output to file.\n"
   "  --no-gui                   Run headless (do not open the interactive viewer).\n"))

(defn load-architecture
  ([project-path] (load-architecture project-path {}))
  ([project-path {:keys [language source-paths]}]
  (let [language (languages/language-key language)
        source-paths (or (seq source-paths) (languages/default-source-paths project-path language))
        guidance (assoc default-guidance :source-paths (vec source-paths) :language language
                        :namespace-root-depth (if (= :clojure language) 1 0))
        graph (languages/build-module-graph project-path source-paths language)
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

(defn parse-args
  [args]
  (let [flag-handlers {"--help" (fn [remaining opts]
                                  [(next remaining) (assoc opts :help true)])
                       "--no-gui" (fn [remaining opts]
                                    [(next remaining) (assoc opts :no-gui true)])}
        value-handlers {"--project-path" :project-path
                        "--language" :language
                        "--source-path" :source-paths
                        "--in-edn" :in-edn
                        "--out" :out}]
    (loop [remaining args
           opts {:project-path "." :in-edn nil :no-gui false :out nil :help false}]
      (if (empty? remaining)
        opts
        (let [arg (first remaining)]
          (if-let [handle-flag (get flag-handlers arg)]
            (let [[next-remaining next-opts] (handle-flag remaining opts)]
              (recur next-remaining next-opts))
            (if-let [key-name (get value-handlers arg)]
              (let [value (second remaining)]
                (when (or (nil? value) (str/starts-with? value "--"))
                  (throw (ex-info (str "Missing value for " arg) {:option arg})))
                (recur (nnext remaining)
                       (if (= key-name :source-paths)
                         (update opts key-name (fnil conj []) value)
                         (assoc opts key-name (if (= key-name :language)
                                                (languages/language-key value)
                                                value)))))
              (recur (next remaining) opts))))))))

(defn exit-program!
  []
  (shutdown-agents)
  (System/exit 0))

(defn -main [& args]
  (let [{:keys [project-path in-edn no-gui out help] :as opts} (parse-args args)]
    (if help
      (println (usage-summary))
      (let [architecture (if in-edn
                           (load-architecture-edn in-edn)
                           (load-architecture project-path opts))
            source-label (or in-edn project-path)
            {:keys [graph scene]} architecture]
        (println "Architecture loaded")
        (println "Nodes:" (count (:nodes graph)))
        (println "Edges:" (count (:edges graph)))
        (when out
          (spit out (pr-str architecture)))
        (when-not no-gui
          (-> (render/show! scene {:title (str "architecture-viewer: " (str/trim source-label))
                                   :architecture architecture
                                   :reload-architecture (when-not in-edn
                                                          (fn []
                                                            (load-architecture project-path opts)))})
              (render/wait-until-closed!))
          (exit-program!))))))
