;; 回归测试：检查源码窗口显示和代码文字处理是否符合已有行为。

(ns arch-view.render.ui.swing.source-window-spec
  (:require [arch-view.render.ui.swing.source-window :as sut]
            [speclj.core :refer :all]))

(describe "swing source window"
  (it "delegates html helper functions"
    (with-redefs [arch-view.render.ui.util.source-html/html-escape (fn [_] "&lt;")
                  arch-view.render.ui.util.source-html/colorize-clojure-html (fn [_] "<span>ok</span>")
                  arch-view.render.ui.util.source-html/expand-tabs (fn [_] "tabbed")
                  arch-view.render.ui.util.source-html/source-lines->html (fn [_] "<div>line</div>")
                  arch-view.render.ui.util.source-html/source->html (fn [_ _] "<html></html>")]
      (should= "&lt;" (sut/html-escape "<"))
      (should= "<span>ok</span>" (sut/colorize-clojure-html "(ns a)"))
      (should= "tabbed" (sut/expand-tabs "\tline"))
      (should= "<div>line</div>" (sut/source-lines->html "(ns a)"))
      (should= "<html></html>" (sut/source->html "x.clj" "(ns a)"))))

  (it "detects existing source files"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-source-window" (make-array java.nio.file.attribute.FileAttribute 0)))
          present (java.io.File. root "present.clj")
          missing (java.io.File. root "missing.clj")]
      (spit present "(ns present)")
      (should= true (#'sut/existing-source-file? (.getAbsolutePath present)))
      (should= false (#'sut/existing-source-file? (.getAbsolutePath missing)))
      (should= nil (#'sut/existing-source-file? nil))))

  (it "does nothing when source file is missing"
    (let [scheduled (atom false)]
      (with-redefs [sut/existing-source-file? (fn [_] false)
                    sut/schedule-ui! (fn [_] (reset! scheduled true))]
        (should= nil (sut/open-source-file-window! "/tmp/missing.clj")))
      (should= false @scheduled)))

  (it "loads content and schedules frame creation when source exists"
    (let [scheduled (atom false)
          built (atom nil)]
      (with-redefs [sut/existing-source-file? (fn [_] true)
                    clojure.core/slurp (fn [_] "(ns sample)")
                    sut/build-source-frame! (fn [path content] (reset! built [path content]))
                    sut/schedule-ui! (fn [f] (reset! scheduled true) (f))]
        (sut/open-source-file-window! "/tmp/sample.clj"))
      (should= true @scheduled)
      (should= ["/tmp/sample.clj" "(ns sample)"] @built))))
