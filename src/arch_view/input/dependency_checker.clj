;; 工具函数：读取已有的架构规则文件，将文件内容转换为程序可使用的配置。

;; mutation-tested: 2026-03-08
(ns arch-view.input.dependency-checker
  (:require [clojure.edn :as edn]))

(defn read-guidance
  "Reads dependency-checker.edn and returns its EDN map."
  [path]
  (-> path slurp edn/read-string))
