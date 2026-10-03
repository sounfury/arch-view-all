;; 回归测试：检查脚本项目（Python）的扫描、依赖提取和错误提示是否符合已有行为。

(ns arch-view.input.python.dependency-extract-spec
  (:require [arch-view.core :as core]
            [arch-view.input.python.dependency-extract :as sut]
            [arch-view.domain.architecture-projection :as projection]
            [arch-view.render.ui.quil.view :as render]
            [speclj.core :refer :all]))

(defn with-project [files f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-view python 中文" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[name content] files]
        (let [file (java.io.File. root name)]
          (.mkdirs (.getParentFile file))
          (spit file content :encoding "UTF-8")))
      (f (.getAbsolutePath root))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(describe "Python architecture integration"
  (it "shows flat modules and their dependencies in the shared view"
    (with-project {"main.py" "import port\n"
                   "port.py" "from typing import Protocol\nclass Port(Protocol): pass\n"}
      (fn [root]
        (let [architecture (core/load-architecture root {:language :python})
              view (projection/view-architecture architecture [])]
          (should= ["."] (get-in architecture [:guidance :source-paths]))
          (should= #{"main" "port"} (get-in view [:graph :nodes]))
          (should= #{"port"} (get-in view [:graph :abstract-modules]))
          (should= #{{:from "main" :to "port"}} (get-in architecture [:graph :edges]))
          (should= 2 (count (:module-positions (render/build-scene view))))))))

  (it "keeps package initializers and nested source files accessible"
    (with-project {"src/pkg/__init__.py" "from . import api\n"
                   "src/pkg/api.py" "VALUE = 1\n"}
      (fn [root]
        (let [architecture (core/load-architecture root {:language :python})
              top (projection/view-architecture architecture [])
              nested (projection/view-architecture architecture ["pkg"])]
          (should= #{"pkg" "pkg|file"} (get-in top [:graph :nodes]))
          (should= true (get-in top [:module->leaf? "pkg|file"]))
          (should= #{"api"} (get-in nested [:graph :nodes]))
          (should= true (.endsWith (get-in nested [:module->source-file "api"]) "api.py"))))))

  (it "reports Python syntax errors instead of returning an incomplete graph"
    (with-project {"bad.py" "def broken("}
      (fn [root]
        (should-throw clojure.lang.ExceptionInfo (sut/build-module-graph root ["."]))))))

(describe "language CLI options"
  (it "accepts Python and repeated source roots"
    (let [opts (core/parse-args ["--language" "python" "--source-path" "src"
                               "--source-path" "lib"])]
      (should= :python (:language opts))
      (should= ["src" "lib"] (:source-paths opts))))

  (it "rejects unsupported languages"
    (should-throw clojure.lang.ExceptionInfo (core/parse-args ["--language" "unknown"])))

  (it "rejects options without a value"
    (should-throw clojure.lang.ExceptionInfo (core/parse-args ["--language"]))
    (should-throw clojure.lang.ExceptionInfo (core/parse-args ["--source-path" "--no-gui"])))

  (it "retains Python options when reanalyzing a GUI session"
    (with-project {"code/main.py" "import helper" "code/helper.py" ""}
      (fn [root]
        (let [reloaded (atom nil)]
          (with-redefs [render/show! (fn [_ opts]
                                      (reset! reloaded ((:reload-architecture opts)))
                                      :sketch)
                        render/wait-until-closed! (fn [_])
                        core/exit-program! (fn [])]
            (core/-main "--project-path" root "--language" "python" "--source-path" "code"))
          (should= :python (get-in @reloaded [:guidance :language]))
          (should= #{"main" "helper"} (get-in @reloaded [:graph :nodes])))))))

(describe "Python test filtering through the architecture loader"
  (it "defaults to production modules and accepts an explicit inclusion override"
    (with-project {"app.py" ""
                   "tests/test_app.py" "import app\n"
                   "conftest.py" ""}
      (fn [root]
        (should= #{"app"} (get-in (core/load-architecture root) [:graph :nodes]))
        (let [architecture (core/load-architecture root {:include-tests true})]
          (should= #{"app" "tests.test_app" "conftest"} (get-in architecture [:graph :nodes]))
          (should= #{{:from "tests.test_app" :to "app"}} (get-in architecture [:graph :edges])))))))
