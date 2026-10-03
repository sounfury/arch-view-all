;; 测试：检查默认配置文件、环境变量及参数值的读取和校验。

(ns arch-view.config-spec
  (:require [arch-view.config :as sut]
            [arch-view.core :as core]
            [speclj.core :refer :all]))

(describe "env defaults"
  (with env-file (java.io.File/createTempFile "arch-env" ".env"))
  (after (.delete @env-file))

  (it "loads quoted values and typed defaults without evaluating shell syntax"
    (spit @env-file "# settings\nexport ARCH_VIEW_PROJECT_PATH='/some project'\nARCH_VIEW_UI_SCALE=1.5 # scale\nARCH_VIEW_INCLUDE_TESTS=false\nARCH_VIEW_SOURCE_PATHS=src;lib\nUNRELATED=ignored\nARCH_VIEW_PYTHON='$(nothing)'\n")
    (should= {:project-path "/some project" :ui-scale "1.5" :include-tests false
              :source-paths ["src" "lib"] :python "$(nothing)"}
             (sut/defaults (str @env-file) {})))

  (it "lets environment override files and CLI replace configured source roots"
    (spit @env-file "ARCH_VIEW_LANGUAGE=java\nARCH_VIEW_NO_GUI=true\nARCH_VIEW_SOURCE_PATHS=src;lib\nARCH_VIEW_UI_SCALE=1.25\n")
    (let [defaults (sut/defaults (str @env-file) {"ARCH_VIEW_LANGUAGE" "python"})
          opts (core/parse-args ["--language" "clojure" "--source-path" "code"
                                 "--source-path" "extra" "--gui" "--ui-scale" "2"] defaults)]
      (should= "python" (:language defaults))
      (should= :clojure (:language opts))
      (should= ["code" "extra"] (:source-paths opts))
      (should= false (:no-gui opts))
      (should= "2" (:ui-scale opts))))

  (it "validates boolean and scale values"
    (doseq [scale ["0" "-1" "NaN" "Infinity" "abc"]]
      (should-throw clojure.lang.ExceptionInfo (sut/parse-scale scale)))
    (should-throw clojure.lang.ExceptionInfo (sut/parse-boolean "perhaps"))
    (should-throw clojure.lang.ExceptionInfo (sut/read-env (str @env-file ".missing") true)))

  (it "allows CLI test flags to override env defaults"
    (should= false (:include-tests (core/parse-args ["--exclude-tests"] {:include-tests true})))
    (should= true (:include-tests (core/parse-args ["--include-tests"] {:include-tests false})))))

(describe "configured CLI startup"
  (it "passes file defaults to analysis and viewer before opening the window"
    (let [file (java.io.File/createTempFile "arch-startup" ".env")
          received (atom nil)
          shown (atom nil)
          old-scale (System/getProperty "sun.java2d.uiScale")]
      (try
        (spit file "ARCH_VIEW_PROJECT_PATH=configured-project\nARCH_VIEW_ZOOM=1.4\nARCH_VIEW_UI_SCALE=1.5\nARCH_VIEW_INCLUDE_TESTS=true\n")
        (with-redefs [core/load-architecture (fn [path opts]
                                              (reset! received [path opts])
                                              {:graph {:nodes #{} :edges #{}} :scene {}})
                      arch-view.render.ui.quil.view/show! (fn [_ opts]
                                                           (reset! shown [(:zoom opts) (System/getProperty "sun.java2d.uiScale")]))
                      arch-view.render.ui.quil.view/wait-until-closed! (fn [_])
                      core/exit-program! (fn [])]
          (core/-main "--env-file" (str file) "--exclude-tests"))
        (should= "configured-project" (first @received))
        (should= false (:include-tests (second @received)))
        (should= [1.4 "1.5"] @shown)
        (finally
          (.delete file)
          (if old-scale (System/setProperty "sun.java2d.uiScale" old-scale)
              (System/clearProperty "sun.java2d.uiScale")))))))
