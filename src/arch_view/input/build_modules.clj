;; 职责：将源码归属到构建模块，并在引用重名时选择唯一候选节点。
;; 核心入口：建立源码归属查询（source-owner）；添加模块前缀（node-id）；筛选引用候选（preferred-candidates）。

(ns arch-view.input.build-modules
  (:require [clojure.java.io :as io]))

(defn source-owner
  "按最长源码根目录匹配归属，避免嵌套源码根被父模块抢占。"
  [project-path modules]
  (let [roots (->> modules
                   (mapcat (fn [{:keys [name source-paths]}]
                             (for [path source-paths
                                   :let [file (io/file path)
                                         root (if (.isAbsolute file) file (io/file project-path path))]]
                               [(.toPath (.getCanonicalFile root)) name])))
                   (sort-by #(.getNameCount ^java.nio.file.Path (first %)) >))]
    (fn [file]
      (let [path (.toPath (.getCanonicalFile (io/file file)))]
        (some (fn [[^java.nio.file.Path root name]]
                (when (.startsWith path root) name)) roots)))))

(defn node-id [owner name]
  (if owner (str owner "." name) name))

(defn preferred-candidates
  "优先保留同构建模块候选；否则仅接受全局唯一候选，歧义引用不连边。"
  [node->owner owner candidates]
  (let [local (filter #(= owner (get node->owner %)) candidates)
        preferred (if (seq local) local candidates)]
    (if (= 1 (count preferred)) preferred [])))
