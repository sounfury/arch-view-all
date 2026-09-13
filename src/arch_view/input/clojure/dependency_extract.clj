(ns arch-view.input.clojure.dependency-extract
  (:require [arch-view.input.dependency-extract :as legacy]))

;; Keep the original public API compatible while exposing a language adapter.
(def build-module-graph legacy/build-module-graph)
