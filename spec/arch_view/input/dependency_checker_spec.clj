;; 回归测试：检查架构规则文件的读取是否符合已有行为。

(ns arch-view.input.dependency-checker-spec
  (:require [arch-view.input.dependency-checker :as sut]
            [speclj.core :refer :all]))

(describe "dependency-checker"
  (it "parses dependency-checker EDN guidance"
    (let [tmp-file (doto (java.io.File/createTempFile "dependency-checker" ".edn")
                     (.deleteOnExit))]
      (spit tmp-file
            "{:source-paths [\"src\"] :component-rules [{:component :core :kind :concrete :match \"my.app.*\"}]}")
      (should= {:source-paths ["src"]
                :component-rules [{:component :core :kind :concrete :match "my.app.*"}]}
               (sut/read-guidance (.getAbsolutePath tmp-file))))))
