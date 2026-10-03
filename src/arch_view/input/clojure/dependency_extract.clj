;; 职责：为本项目使用的语言（Clojure）提供统一的源码分析入口，沿用原有分析逻辑。
;; 核心入口：构建模块关系图（build-module-graph）。

(ns arch-view.input.clojure.dependency-extract
  (:require [arch-view.input.dependency-extract :as legacy]))

;; Keep the original public API compatible while exposing a language adapter.
(def build-module-graph legacy/build-module-graph)
