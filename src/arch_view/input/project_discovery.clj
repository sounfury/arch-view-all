;; 职责：识别项目的主要源码语言，并发现 Java 项目的标准源码目录。
;; 核心入口：识别语言（detect-language）；查找 Java 源码目录（java-source-paths）。

(ns arch-view.input.project-discovery
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private ignored-directories
  #{"target" "build" "out" "dist" "node_modules" "venv" "env"
    "__pycache__" "site-packages" "vendor"})

(declare ^:private entries ^:private project-root)

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

;; ===== 私有方法 =====

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
