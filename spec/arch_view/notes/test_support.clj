;; 职责：为解释和共享基础测试创建独立临时项目，并在测试后清理。
;; 核心入口：临时工作区（create-workspace）；清理（delete-workspace!）。

(ns arch-view.notes.test-support
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn create-workspace []
  (let [root (Files/createTempDirectory "arch-view-notes-" (make-array FileAttribute 0))
        file (io/file (.toFile root) "src/web.clj")]
    (io/make-parents file)
    (spit file "头部\n(defn view [] :ok)\n尾部\n" :encoding "UTF-8")
    {:root root :opts {}
     :architecture {:graph {:nodes #{"app.web.model" "app.web.routes" "app.web.sub.api" "app.webbing" "app.data"}
                            :module->source-file {"app.web.model" (.getPath file)}}}}))

(defn delete-workspace! [ws]
  (doseq [file (reverse (file-seq (.toFile ^java.nio.file.Path (:root ws))))]
    (.delete file)))

(defn error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo ex (ex-data ex))))

(def draft {:title "网页解释" :body "解释正文" :targets ["app.web.model"]})
