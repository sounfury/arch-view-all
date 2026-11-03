;; 职责：为链路测试建立临时源码和有直接、间接、反向及无关联模块的静态图。
;; 核心入口：创建与清理（create!、cleanup!）；草稿（draft）。

(ns arch-view.trace.fixtures
  (:require [arch-view.workspace :as workspace]
            [clojure.java.io :as io]))

(defn create! []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                      "arch-trace-" (make-array java.nio.file.attribute.FileAttribute 0)))
        modules ["a" "b" "c" "d" "e" "u"]]
    (doseq [module (conj modules "asset")]
      (spit (io/file root (str module ".clj")) (str "前导\n内容-" module "\n结尾\n") :encoding "UTF-8"))
    (workspace/create
     {:graph {:nodes (set modules)
              :edges #{{:from "a" :to "b"} {:from "b" :to "c"} {:from "c" :to "d"}
                       {:from "d" :to "e"} {:from "d" :to "b"}}
              :module->source-file (into {} (map #(vector % (str (io/file root (str % ".clj")))) modules))}}
     root {})))

(defn cleanup! [ws]
  (doseq [file (reverse (file-seq (.toFile ^java.nio.file.Path (:root ws))))]
    (io/delete-file file true)))

(defn draft [& modules]
  {:id "example" :title "测试链路" :question "如何调用？"
   :steps (mapv (fn [module]
                  {:title (str "步骤-" module) :anchor {:path (str module ".clj") :startLine 2}})
                modules)})

(defn file [ws path]
  (io/file (.toFile ^java.nio.file.Path (:root ws)) path))

(defn exception-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo ex {:message (.getMessage ex) :data (ex-data ex)})))
