;; 职责：记录入口分析时的源码版本，防止把旧调用图按新源码补哈希保存成“有效”的 trace。
;; 核心入口：生成快照（capture）；核对源码是否仍是同一版本（assert-current!）。

(ns arch-view.entries.source-snapshot
  (:require [arch-view.workspace :as workspace]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(defn- digest [file]
  (vec (.digest (MessageDigest/getInstance "SHA-256") (Files/readAllBytes (.toPath (io/file file))))))

(defn capture [ws files]
  (into {} (for [file files]
             [(workspace/relative ws file) (when (.isFile (io/file file)) (digest file))])))

(defn assert-current!
  "核对分析使用的文件；保存时包含源码、规则和声明 XML，变动后要求重载入口。"
  [ws expected paths]
  (doseq [path (distinct paths)]
    (let [file (io/file (.toFile ^java.nio.file.Path (:root ws)) path)
          actual (try (digest file) (catch Exception _ nil))]
      (when (or (not (contains? expected path)) (not= (get expected path) actual))
        (throw (ex-info (str "分析输入已变化：" path "。请重新加载入口后再查看或保存调用链。") {:status 409})))))
  true)
