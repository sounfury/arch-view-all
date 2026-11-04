;; 职责：为入口识别测试在临时目录写入 Java 源码与入口规则，并按真实分析流程构造工作区。
;; 核心入口：with-project、scan-project、by-label。

(ns arch-view.entries.fixtures
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.core :as core]
            [arch-view.entries.core :as entries]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn with-project
  "写入 {相对路径 内容} 后调用 (f 根目录)；rules 非空时写成 .arch-view/entry-rules.json。"
  ([files f] (with-project files nil f))
  ([files rules f]
   (let [root (.toFile (Files/createTempDirectory "arch-view-entries-" (make-array FileAttribute 0)))]
     (try
       (doseq [[path content] files]
         (let [file (io/file root path)]
           (io/make-parents file)
           (spit file content :encoding "UTF-8")))
       (when rules
         (let [file (io/file root ".arch-view" "entry-rules.json")]
           (if (string? rules)
             (do (io/make-parents file) (spit file rules :encoding "UTF-8"))
             (cli-support/write-json-file! file rules))))
       (f (.getCanonicalPath root))
       (finally (doseq [file (reverse (file-seq root))] (.delete file)))))))

(defn workspace-of [root]
  (workspace/create (core/load-architecture root {}) root {}))

(defn scan-project
  "写入项目、真实分析并识别入口，返回扫描结果。"
  ([files] (scan-project files nil))
  ([files rules]
   (with-project files rules #(entries/scan (workspace-of %)))))

(defn by-label [scanned]
  (into {} (map (juxt :label identity)) (:entries scanned)))

(defn java [package & body]
  (apply str "package " package ";\n" body))
