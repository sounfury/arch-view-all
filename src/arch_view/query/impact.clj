;; 职责：读取 Git 改动并映射到源码模块，汇总受影响的上游闭包与依赖环。
;; 核心入口：impact。

(ns arch-view.query.impact
  (:require [arch-view.query.core :as query]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(declare ^:private git! ^:private changed-files ^:private upstream ^:private nearer
         ^:private base-ref)

(defn impact
  "默认 HEAD 到工作区并含未跟踪文件；since 为 merge-base(ref, HEAD) 到工作区，另并工作区改动；staged 仅暂存区。"
  [{:keys [root] :as ws} {:keys [since staged]}]
  (when (and since staged)
    (throw (ex-info "--since 与 --staged 不能同时使用：请选择工作区比较或仅暂存区" {:status 400})))
  (let [repository (str/trim (git! root ["rev-parse" "--show-toplevel"]))
        base (base-ref root since staged)
        files (->> (changed-files (.toPath (io/file repository)) base staged)
                   (map #(workspace/relative ws (io/file repository %))) distinct sort
                   (mapv #(hash-map :path % :module (workspace/module-for-path ws %))))
        modules (vec (sort (set (keep :module files))))
        affected (upstream ws modules)
        changed (set modules)]
    {:since since :staged (boolean staged) :base base
     :files files :modules modules :unmapped (mapv :path (remove :module files))
     :upstream (mapv (fn [[distance items]] {:distance distance :items (vec items)})
                     (sort-by key (group-by :distance affected)))
     :upstream-count (count affected)
     :cycles (vec (filter #(some changed (:modules %)) (:items (query/cycles ws))))}))

;; ===== 私有方法 =====

(defn- git! [^java.nio.file.Path root args]
  (let [{:keys [exit out err]}
        (try (apply shell/sh (concat ["git"] args [:dir (.toFile root) :encoding "UTF-8"]))
             (catch java.io.IOException ex
               (throw (ex-info "无法执行 git：请安装 Git 并确保命令位于 PATH 中" {:status 400} ex))))]
    (when-not (zero? exit)
      (throw (ex-info (str "Git 查询失败：请确认项目位于 Git 仓库内，且提交或引用有效。"
                           (when-not (str/blank? err) (str "\n" (str/trim err))))
                      {:status 400 :command (into ["git"] args) :exit exit})))
    out))

(defn- base-ref [root since staged]
  (when-not staged
    (if since
      (do (when (str/starts-with? since "-")
            (throw (ex-info "Git 引用不能以 - 开头" {:status 400})))
          (str/trim (git! root ["merge-base" since "HEAD"])))
      "HEAD")))

(defn- changed-files [root base staged]
  (let [diff-args (if staged ["diff" "--no-relative" "--name-only" "-z" "--cached" "--"]
                      ["diff" "--no-relative" "--name-only" "-z" base "--"])
        tracked (git! root diff-args)
        working (when (and (not staged) (not= base "HEAD"))
                  (git! root ["diff" "--no-relative" "--name-only" "-z" "HEAD" "--"]))
        untracked (when-not staged (git! root ["ls-files" "--full-name" "--others" "--exclude-standard" "-z"]))]
    (remove str/blank? (str/split (str tracked working untracked) #"\u0000"))))

(defn- nearer [items item]
  (let [module (:module item) existing (get items module)]
    (if (or (nil? existing) (< (:distance item) (:distance existing)))
      (assoc items module item) items)))

(defn- upstream [ws modules]
  (let [depth (max 1 (count (get-in ws [:architecture :graph :nodes])))
        changed (set modules)]
    (->> modules
         (mapcat #(:items (query/dependencies ws % {:direction :in :depth depth})))
         (remove #(contains? changed (:module %)))
         (reduce nearer {}) vals (sort-by (juxt :distance :module)) vec)))
