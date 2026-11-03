;; 职责：为查询测试构造带包、抽象依赖、强连通分量和源码说明的小工作区。
;; 核心入口：temporary-root、workspace、delete-tree!。

(ns arch-view.query.fixtures
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn temporary-root []
  (Files/createTempDirectory "arch-view-query-" (make-array FileAttribute 0)))

(defn workspace [^Path root]
  (let [files {"app.web.a" "src/a.clj" "app.web.b" "src/b.clj"
               "app.webby" "src/webby.clj" "lib.port" "src/port.clj"
               "lib.impl" "src/impl.clj" "client" "src/client.clj"
               "orphan" "src/orphan.clj" "self" "src/self.clj"}
        edges [["app.web.a" "app.web.b"] ["app.web.a" "lib.port"]
               ["app.web.b" "lib.port"] ["lib.port" "lib.impl"] ["lib.impl" "lib.port"]
               ["client" "app.web.a"] ["app.webby" "client"] ["self" "self"]]]
    (doseq [[module path] files]
      (let [file (io/file (.toFile root) path)]
        (io/make-parents file)
        (spit file (str ";; 职责：" (if (= module "orphan") "复杂度 complexity 检索" module) "。\n(ns " module ")\n"))))
    {:root root :opts {}
     :architecture
     {:graph {:nodes (set (keys files))
              :edges (set (map (fn [[from to]] {:from from :to to}) edges))
              :abstract-modules #{"lib.port"}
              :module->source-file (into {} (map (fn [[module path]] [module (str (.resolve root path))]) files))}
      :classified-edges (mapv (fn [[from to]] {:from from :to to :type (if (= to "lib.port") :abstract :direct)}) edges)
      :layout {:module->layer {"app.webby" 0 "client" 1 "app.web.a" 2 "app.web.b" 3
                              "lib.port" 4 "lib.impl" 5 "orphan" 0 "self" 0}
               :cycles [["lib.port" "lib.impl" "lib.port"] ["self" "self"]]}}}))

(defn delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))] (.delete file)))
