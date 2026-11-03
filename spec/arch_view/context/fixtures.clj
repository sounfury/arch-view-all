;; 职责：为上下文包测试构造独立的小型项目和工作区。
;; 核心入口：创建测试项目（project）；清理测试项目（cleanup!）。

(ns arch-view.context.fixtures
  (:require [arch-view.workspace :as workspace]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn status-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo ex (:status (ex-data ex)))))

(defn put! [root path content]
  (let [file (io/file (.toFile ^java.nio.file.Path root) path)]
    (io/make-parents file)
    (spit file content :encoding "UTF-8")
    file))

(defn project []
  (let [root (Files/createTempDirectory "av-context-spec-" (make-array FileAttribute 0))
        files {"demo.web.model" (put! root "src/demo/web/model.clj" ";; 模型职责\n(ns demo.web.model)\n(defn load-model []\n  :model)\n")
               "demo.web.api" (put! root "src/demo/web/api.clj" ";; 接口职责\n(ns demo.web.api)\n")
               "demo.web.port" (put! root "src/demo/web/port.clj" ";; 抽象接口\n(ns demo.web.port)\n")
               "demo.util" (put! root "src/demo/util.clj" ";; 工具职责\n(ns demo.util)\n")}
        edges [{:from "demo.web.model" :to "demo.web.port"}
               {:from "demo.web.model" :to "demo.web.api"}
               {:from "demo.web.api" :to "demo.web.model"}
               {:from "demo.util" :to "demo.web.model"}
               {:from "demo.util" :to "demo.web.api"}]
        architecture {:graph {:nodes (set (keys files)) :edges (set edges)
                              :abstract-modules #{"demo.web.port"} :module->source-file files}
                      :guidance {:language :clojure :namespace-root-depth 1}
                      :classified-edges (mapv #(assoc % :type (if (= "demo.web.port" (:to %)) :abstract :direct)) edges)
                      :layout {:module->layer {"demo.web.model" 1 "demo.web.api" 0 "demo.web.port" 2 "demo.util" 0}
                               :cycles [["demo.web.api" "demo.web.model" "demo.web.api"]]}}]
    (put! root "ARCHITECTURE.md"
          (str "# 示例架构\n\n## 核心子系统与职责\n\n"
               "### web · 网页\n\n- **实现状态**：已实现。\n- **职责**：负责网页。\n\n[探索网页](#module=web)\n\n"
               "### util · 工具\n\n- **职责**：负责工具。\n\n[探索工具](#module=util)\n"))
    (put! root "src/demo/web/ARCHITECTURE.md"
          (str "# 网页内部\n\n## 核心子系统与职责\n\n"
               "### service · 模型服务\n\n- **实现状态**：进行中。\n- **职责**：构建模型。\n\n[探索模型](#module=model)\n\n"
               "### api · 接口\n\n- **实现状态**：待实现。\n- **职责**：处理请求。\n\n[探索接口](#module=api)\n"))
    (workspace/create architecture (str root) {})))

(defn cleanup! [ws]
  (doseq [file (reverse (file-seq (.toFile ^java.nio.file.Path (:root ws))))]
    (.delete ^java.io.File file)))

(defn script! [ws path content]
  (let [file (put! (:root ws) path (str "#!/bin/sh\n" content "\n"))]
    (.setExecutable file true)
    (str file)))
