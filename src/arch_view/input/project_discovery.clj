;; 职责：识别项目源码语言，发现主源码目录与 Maven/Gradle 构建模块。
;; 核心入口：识别语言（detect-language）；查找 Java 源码目录（java-source-paths）；发现构建模块（build-modules）。

(ns arch-view.input.project-discovery
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private ignored-directories
  #{"target" "build" "out" "dist" "node_modules" "venv" "env"
    "__pycache__" "site-packages" "vendor" "buildSrc"})

(declare ^:private entries ^:private project-root ^:private module-candidates ^:private name-modules)

(def ^:private markers
  {:clojure #{"deps.edn" "project.clj" "bb.edn"}
   :python #{"pyproject.toml" "setup.py" "setup.cfg" "requirements.txt" "Pipfile"}
   :java #{"pom.xml" "build.gradle" "build.gradle.kts" "settings.gradle" "settings.gradle.kts"}
   :kotlin #{"build.gradle" "build.gradle.kts" "settings.gradle" "settings.gradle.kts"}})

(def ^:private extensions
  {"clj" :clojure "cljc" :clojure "cljs" :clojure "py" :python "java" :java
   "kt" :kotlin "kts" :kotlin})

(defn detect-language
  "Prefer a unique root build marker; otherwise choose the most common source language.
  Explicit source roots restrict detection and bypass project-wide build markers."
  [project-path source-paths]
  (let [root (project-root project-path)
        marked (when-not (seq source-paths)
                 (for [[language names] markers
                       :when (some #(.isFile (io/file root %)) names)] language))]
    (if (= 1 (count marked))
      (first marked)
      (let [roots (if (seq source-paths)
                    (map #(let [file (io/file %)]
                            (if (.isAbsolute file) file (io/file root %))) source-paths)
                    [root])
            counts (->> roots (mapcat entries)
                        (filter #(.isFile ^java.io.File %))
                        ;; 构建脚本使用 Kotlin 语法，不代表目标项目的源码也是 Kotlin。
                        (remove #(#{"build.gradle.kts" "settings.gradle.kts"} (.getName ^java.io.File %)))
                        (map #(.getCanonicalFile ^java.io.File %)) distinct
                        (keep #(extensions (last (str/split (.getName ^java.io.File %) #"\."))))
                        frequencies)
            candidates (if (seq marked) (select-keys counts marked) counts)
            highest (when (seq candidates) (apply max (vals candidates)))
            winners (for [[language n] candidates :when (= highest n)] language)]
        (if (= 1 (count winners))
          (first winners)
          (throw (ex-info
                  (str "Cannot determine project language"
                       (when (seq candidates) (str ": " (pr-str candidates)))
                       ". Specify --language clojure, python, kotlin or java.")
                  {:project-path project-path :language-counts counts})))))))

(defn java-source-paths [project-path]
  (let [root (project-root project-path)
        paths (->> (entries root)
                   (filter #(.isDirectory ^java.io.File %))
                   (map #(-> (.toPath root) (.relativize (.toPath ^java.io.File %)) str
                             (str/replace "\\" "/")))
                   (filter #(or (= % "src/main/java")
                                (str/ends-with? % "/src/main/java")))
                   sort vec)]
    (if (seq paths)
      paths
      (if (.isDirectory (io/file root "src")) ["src"] ["."]))))

(defn build-modules
  "按目录发现构建模块，不读取或执行构建脚本；源码路径和目录均相对项目根目录。"
  [project-path language]
  (if (#{:java :kotlin} language)
    (name-modules (module-candidates (project-root project-path) language))
    []))

;; ===== 私有方法 =====

(defn- relative-path [root file]
  (let [path (str (.relativize (.toPath root) (.toPath file)))]
    (if (empty? path) "." (str/replace path "\\" "/"))))

(defn- build-tool
  "按构建描述文件判断构建工具；同时存在时以 Gradle 为准（常见于迁移中的项目）。"
  [dir]
  (cond
    (some #(.isFile (io/file dir %)) ["build.gradle" "build.gradle.kts"]) "gradle"
    (.isFile (io/file dir "pom.xml")) "maven"))

(defn- module-candidates [root language]
  (let [source-dirs (if (= :kotlin language) ["src/main/kotlin" "src/main/java"] ["src/main/java"])]
    (->> (entries root)
         (filter #(.isDirectory ^java.io.File %))
         (keep (fn [dir]
                 (let [tool (build-tool dir)
                       paths (when tool (filter #(.isDirectory (io/file dir %)) source-dirs))]
                   (when (seq paths)
                     {:name (str/replace (.getName dir) "." "_")
                      :build-tool tool
                      :directory (relative-path root dir)
                      :source-paths (mapv #(relative-path root (io/file dir %)) paths)}))))
         (sort-by :directory) vec)))

(defn- path-module-name [{:keys [name directory]}]
  (if (= "." directory) name (str/replace directory #"[/.]" {"/" ":" "." "_"})))

(defn- available-module-name [used base]
  (first (remove used (cons base (map #(str base ":" %) (iterate inc 2))))))

(defn- name-modules [modules]
  (let [counts (frequencies (map :name modules))
        named (mapv #(if (> (counts (:name %)) 1) (assoc % :name (path-module-name %)) %) modules)
        ;; 预留所有自然名称，避免补充序号覆盖另一个模块的名称。
        reserved (set (map :name named))]
    (second
     (reduce (fn [[used result] module]
               (let [base (:name module)
                     name (if (used base) (available-module-name (into reserved used) base) base)]
                 [(conj used name) (conj result (assoc module :name name))]))
             [#{} []] named))))

(defn- entries [root]
  (tree-seq
   #(.isDirectory ^java.io.File %)
   (fn [^java.io.File dir]
     (remove #(or (java.nio.file.Files/isSymbolicLink (.toPath ^java.io.File %))
                  (str/starts-with? (.getName ^java.io.File %) ".")
                  (and (.isDirectory ^java.io.File %)
                       (ignored-directories (.getName ^java.io.File %))))
             (or (.listFiles dir) [])))
   root))

(defn- project-root [project-path]
  (let [root (.getCanonicalFile (io/file project-path))]
    (when-not (.isDirectory root)
      (throw (ex-info (str "Project directory does not exist: " project-path)
                      {:project-path project-path})))
    root))
