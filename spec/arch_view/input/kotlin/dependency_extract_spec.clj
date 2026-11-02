;; 测试：检查 Kotlin 构建模块前缀、重复符号消歧、跨模块引用与单模块兼容性。

(ns arch-view.input.kotlin.dependency-extract-spec
  (:require [arch-view.input.kotlin.dependency-extract :as sut]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(defn- with-project [files f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-kotlin-modules" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file content :encoding "UTF-8")))
      (f (str root))
      (finally (doseq [file (reverse (file-seq root))] (.delete file))))))

(describe "Kotlin 构建模块引用"
  (it "相同包及完整类型名按模块区分，同模块优先，外部歧义跳过"
    (with-project
      {"one/src/p/Port.kt" "package p\ninterface Port"
       "two/src/p/Port.kt" "package p\ninterface Port"
       "one/src/p/Local.kt" "package p\nclass Local(val p: Port, val u: q.Unique)"
       "two/src/p/Local.kt" "package p\nclass Local(val p: Port, val u: q.Unique)"
       "two/src/q/Unique.kt" "package q\nclass Unique"
       "app/src/r/App.kt" "package r\nimport p.Port\nclass App(val p: Port, val u: q.Unique)"}
      (fn [root]
        (let [modules [{:name "one" :source-paths ["one/src"]}
                       {:name "two" :source-paths ["two/src"]}
                       {:name "app" :source-paths ["app/src"]}]
              g (sut/build-module-graph root ["one/src" "two/src" "app/src"] modules)]
          (should= #{"one.p.Port" "two.p.Port" "one.p.Local" "two.p.Local" "two.q.Unique" "app.r.App"}
                   (:nodes g))
          (should= #{"one.p.Port" "two.p.Port"} (:abstract-modules g))
          (should= #{{:from "one.p.Local" :to "one.p.Port"}
                     {:from "two.p.Local" :to "two.p.Port"}
                     {:from "one.p.Local" :to "two.q.Unique"}
                     {:from "two.p.Local" :to "two.q.Unique"}
                     {:from "app.r.App" :to "two.q.Unique"}}
                   (:edges g))
          (should= (.getCanonicalPath (io/file root "two/src/p/Port.kt"))
                   (get-in g [:module->source-file "two.p.Port"]))))))

  (it "通配导入的重名符号同样优先选择同模块候选"
    (with-project
      {"one/src/p/Port.kt" "package p\ninterface Port"
       "two/src/p/Port.kt" "package p\ninterface Port"
       "one/src/q/App.kt" "package q\nimport p.*\nclass App(val p: Port)"
       "app/src/r/App.kt" "package r\nimport p.*\nclass App(val p: Port)"}
      (fn [root]
        (should= #{{:from "one.q.App" :to "one.p.Port"}}
                 (:edges (sut/build-module-graph
                          root ["one/src" "two/src" "app/src"]
                          [{:name "one" :source-paths ["one/src"]}
                           {:name "two" :source-paths ["two/src"]}
                           {:name "app" :source-paths ["app/src"]}]))))))

  (it "未传模块分组时保持原文件节点和依赖"
    (with-project {"src/p/Port.kt" "package p\ninterface Port"
                   "src/p/App.kt" "package p\nclass App(val p: Port)"}
      (fn [root]
        (let [g (sut/build-module-graph root ["src"])]
          (should= #{"p.Port" "p.App"} (:nodes g))
          (should= #{"p.Port"} (:abstract-modules g))
          (should= #{{:from "p.App" :to "p.Port"}} (:edges g))))))

  (it "未分组的重复文件节点仍报告冲突"
    (with-project {"one/A.kt" "package p\nclass A"
                   "two/A.kt" "package p\nclass A"}
      (fn [root]
        (should-throw clojure.lang.ExceptionInfo #"Duplicate Kotlin file module"
                      (sut/build-module-graph root ["one" "two"]))))))
