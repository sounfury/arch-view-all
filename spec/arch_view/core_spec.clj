;; 回归测试：检查项目分析、构建模块分组、网页定位、架构导出和桌面启动。

(ns arch-view.core-spec
  (:require [arch-view.core :as sut]
            [arch-view.web.model :as web]
            [clojure.java.io :as io]
            [arch-view.render.ui.quil.view :as render]
            [speclj.core :refer :all]))

(describe "core architecture loader"
  (it "loads guidance and derived graph"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-project" (make-array java.nio.file.attribute.FileAttribute 0)))
          src-dir (doto (java.io.File. root "src") .mkdirs)
          a-file (java.io.File. src-dir "my/app/a.clj")
          b-file (java.io.File. src-dir "my/app/b.clj")]
      (.mkdirs (.getParentFile a-file))
      (spit a-file "(ns my.app.a (:require [my.app.b :as b]))")
      (spit b-file "(ns my.app.b)")
      (let [architecture (sut/load-architecture (.getAbsolutePath root))]
        (should= ["src"] (get-in architecture [:guidance :source-paths]))
        (should= #{"my.app.a" "my.app.b"}
                 (get-in architecture [:graph :nodes]))
        (should= #{{:from "my.app.a" :to "my.app.b"}}
                 (get-in architecture [:graph :edges]))
        (should= [{:index 0 :modules ["my.app.a"]}
                  {:index 1 :modules ["my.app.b"]}]
                 (get-in architecture [:layout :layers]))
        (should= {"my.app.a" 0 "my.app.b" 1}
                 (get-in architecture [:layout :module->layer]))
        (should= #{{:from "my.app.a" :to "my.app.b" :type :direct}}
                 (:classified-edges architecture))
        (should= {"my.app.a" nil "my.app.b" nil}
                 (:module->component architecture))
        (should= 2 (count (get-in architecture [:scene :layer-rects])))
        (should= 2 (count (get-in architecture [:scene :module-positions])))
        (should= 1 (count (get-in architecture [:scene :edge-drawables]))))))

  (it "parses cli options with no-gui flag"
    (should= {:project-path "/tmp/demo" :in-edn nil :no-gui true :out nil :help false}
             (sut/parse-args ["--project-path" "/tmp/demo" "--no-gui"])))

  (it "parses output file option"
    (should= {:project-path "/tmp/demo" :in-edn nil :no-gui true :out "/tmp/scene.edn" :help false}
             (sut/parse-args ["--project-path" "/tmp/demo" "--no-gui" "--out" "/tmp/scene.edn"])))

  (it "parses in-edn option"
    (should= {:project-path "." :in-edn "/tmp/demo.edn" :no-gui true :out nil :help false}
             (sut/parse-args ["--in-edn" "/tmp/demo.edn" "--no-gui"])))

  (it "parses help option"
    (should= {:project-path "." :in-edn nil :no-gui false :out nil :help true}
             (sut/parse-args ["--help"])))

  (it "prints usage summary and skips loading when --help is present"
    (let [printed (atom "")
          loaded? (atom false)]
      (with-redefs [sut/load-architecture (fn [_] (reset! loaded? true) {})
                    sut/load-architecture-edn (fn [_] (reset! loaded? true) {})
                    println (fn [& xs] (swap! printed str (apply str xs) "\n"))]
        (sut/-main "--help"))
      (should= false @loaded?)
      (should= true (.contains ^String @printed "Usage: clj -M:run [options]"))))

  (it "does not invoke quil rendering when --no-gui is present"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
          src-dir (doto (java.io.File. root "src") .mkdirs)
          a-file (java.io.File. src-dir "my/app/a.clj")
          b-file (java.io.File. src-dir "my/app/b.clj")
          called? (atom false)]
      (.mkdirs (.getParentFile a-file))
      (spit a-file "(ns my.app.a (:require [my.app.b :as b]))")
      (spit b-file "(ns my.app.b)")
      (with-redefs [render/show! (fn [& _] (reset! called? true))]
        (sut/-main "--project-path" (.getAbsolutePath root) "--no-gui"))
      (should= false @called?)))

  (it "invokes viewer and wait loop when gui mode is enabled"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-gui" (make-array java.nio.file.attribute.FileAttribute 0)))
          src-dir (doto (java.io.File. root "src") .mkdirs)
          a-file (java.io.File. src-dir "my/app/a.clj")
          b-file (java.io.File. src-dir "my/app/b.clj")
          showed? (atom false)
          reload-fn? (atom false)
          waited? (atom false)
          exited? (atom false)]
      (.mkdirs (.getParentFile a-file))
      (spit a-file "(ns my.app.a (:require [my.app.b :as b]))")
      (spit b-file "(ns my.app.b)")
      (with-redefs [render/show! (fn [_ opts]
                                   (reset! showed? true)
                                   (reset! reload-fn? (fn? (:reload-architecture opts)))
                                   :fake-sketch)
                    render/wait-until-closed! (fn [sketch]
                                                (when (= :fake-sketch sketch)
                                                  (reset! waited? true)))
                    sut/exit-program! (fn [] (reset! exited? true))]
        (sut/-main "--project-path" (.getAbsolutePath root)))
      (should= true @showed?)
      (should= true @reload-fn?)
      (should= true @waited?)
      (should= true @exited?)))

  (it "ignores dependency-checker.edn and uses default guidance"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-no-guide" (make-array java.nio.file.attribute.FileAttribute 0)))
          src-dir (doto (java.io.File. root "src") .mkdirs)
          dep-file (java.io.File. root "dependency-checker.edn")
          a-file (java.io.File. src-dir "my/app/a.clj")
          b-file (java.io.File. src-dir "my/app/b.clj")]
      (.mkdirs (.getParentFile a-file))
      (spit dep-file "{:source-paths [\"nonsense\"] :component-rules [{:component :never :kind :concrete :match \"**\"}]}")
      (spit a-file "(ns my.app.a (:require [my.app.b :as b]))")
      (spit b-file "(ns my.app.b)")
      (let [architecture (sut/load-architecture (.getAbsolutePath root))]
        (should= ["src"] (get-in architecture [:guidance :source-paths]))
        (should= #{"my.app.a" "my.app.b"} (get-in architecture [:graph :nodes])))))

  (it "writes architecture data to out file when --out is provided"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-out" (make-array java.nio.file.attribute.FileAttribute 0)))
          src-dir (doto (java.io.File. root "src") .mkdirs)
          out-file (java.io.File. root "scene.edn")
          a-file (java.io.File. src-dir "my/app/a.clj")
          b-file (java.io.File. src-dir "my/app/b.clj")]
      (.mkdirs (.getParentFile a-file))
      (spit a-file "(ns my.app.a (:require [my.app.b :as b]))")
      (spit b-file "(ns my.app.b)")
      (with-redefs [render/show! (fn [& _] nil)]
        (sut/-main "--project-path" (.getAbsolutePath root) "--no-gui" "--out" (.getAbsolutePath out-file)))
      (should= true (.exists out-file))
      (should-not= nil (re-find #"classified-edges" (slurp out-file)))))

  (it "loads architecture directly from edn input file"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-in-edn" (make-array java.nio.file.attribute.FileAttribute 0)))
          in-file (java.io.File. root "input.edn")
          architecture {:graph {:nodes #{"a" "b" "c"}
                                :edges #{{:from "a" :to "b"} {:from "a" :to "c"}}}
                        :layout {:layers [{:index 0 :modules ["b" "c"]}
                                          {:index 1 :modules ["a"]}]
                                 :module->layer {"a" 1 "b" 0 "c" 0}}
                        :classified-edges #{{:from "a" :to "b" :type :direct}
                                            {:from "a" :to "c" :type :abstract}}
                        :module->component {"a" :api "b" :impl "c" :port}}
          showed? (atom false)
          waited? (atom false)
          exited? (atom false)]
      (spit in-file (pr-str architecture))
      (with-redefs [render/show! (fn [scene _]
                                   (when (= 2 (count (:edge-drawables scene)))
                                     (reset! showed? true))
                                   :fake-sketch)
                    render/wait-until-closed! (fn [sketch]
                                                (when (= :fake-sketch sketch)
                                                  (reset! waited? true)))
                    sut/exit-program! (fn [] (reset! exited? true))]
        (sut/-main "--in-edn" (.getAbsolutePath in-file)))
      (should= true @showed?)
      (should= true @waited?)
      (should= true @exited?)))

  (it "uses in-edn path in viewer title"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-in-edn-title" (make-array java.nio.file.attribute.FileAttribute 0)))
          in-file (java.io.File. root "input.edn")
          title* (atom nil)
          architecture {:graph {:nodes #{"a"} :edges #{}}
                        :layout {:layers [{:index 0 :modules ["a"]}]
                                 :module->layer {"a" 0}}
                        :classified-edges #{}
                        :module->component {"a" :x}}]
      (spit in-file (pr-str architecture))
      (with-redefs [render/show! (fn [_ opts]
                                   (reset! title* (:title opts))
                                   :fake-sketch)
                    render/wait-until-closed! (fn [_] nil)
                    sut/exit-program! (fn [] nil)]
        (sut/-main "--in-edn" (.getAbsolutePath in-file)))
      (should= true (boolean (and @title* (.contains ^String @title* "input.edn")))))))

  (it "does not expose reanalyze button state for in-edn viewer sessions"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-view-in-edn-reload" (make-array java.nio.file.attribute.FileAttribute 0)))
          in-file (java.io.File. root "input.edn")
          reload* (atom :unset)
          architecture {:graph {:nodes #{"a"} :edges #{}}
                        :layout {:layers [{:index 0 :modules ["a"]}]
                                 :module->layer {"a" 0}}
                        :classified-edges #{}
                        :module->component {"a" :x}}]
      (spit in-file (pr-str architecture))
      (with-redefs [render/show! (fn [_ opts]
                                   (reset! reload* (:reload-architecture opts))
                                   :fake-sketch)
                    render/wait-until-closed! (fn [_] nil)
                    sut/exit-program! (fn [] nil)]
        (sut/-main "--in-edn" (.getAbsolutePath in-file)))
      (should= nil @reload*)))

(describe "Java CLI integration"
  (it "Given Java CLI options, when parsed, then retains language and repeated roots"
    (should= {:language :java :source-paths ["api/src/main/java" "app/src/main/java"]}
             (select-keys (sut/parse-args ["--language" "java"
                                          "--source-path" "api/src/main/java"
                                          "--source-path" "app/src/main/java"])
                          [:language :source-paths])))

  (it "Given a Java project, when exported and reloaded, then preserves its graph and source links"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "arch-view-java-cli" (make-array java.nio.file.attribute.FileAttribute 0)))
          src (doto (java.io.File. root "src/main/java/demo") .mkdirs)
          out (java.io.File. root "architecture.edn")]
      (try
        (spit (java.io.File. src "Port.java") "package demo; public interface Port {}")
        (spit (java.io.File. src "App.java") "package demo; class App implements Port {}")
        (with-redefs [sut/exit-program! (fn [])]
          (sut/-main "--language" "java" "--project-path" (.getAbsolutePath root)
                     "--no-gui" "--out" (.getAbsolutePath out)))
        (let [architecture (sut/load-architecture-edn (.getAbsolutePath out))]
          (should= :java (get-in architecture [:guidance :language]))
          (should= ["src/main/java"] (get-in architecture [:guidance :source-paths]))
          (should= #{"demo.App" "demo.Port"} (get-in architecture [:graph :nodes]))
          (should= #{{:from "demo.App" :to "demo.Port"}} (get-in architecture [:graph :edges]))
          (should= #{"demo.Port"} (get-in architecture [:graph :abstract-modules]))
          (should= (.getCanonicalPath (java.io.File. src "App.java"))
                   (get-in architecture [:graph :module->source-file "demo.App"]))
          (should= 2 (count (get-in architecture [:layout :module->layer]))))
        (should= nil (some #(.endsWith (.getName %) ".class") (file-seq root)))
        (finally (doseq [file (reverse (file-seq root))] (.delete file)))))))

(defn- with-build-project [files f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-build-loader" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file content :encoding "UTF-8")))
      (f (str root))
      (finally (doseq [file (reverse (file-seq root))] (.delete file))))))

(describe "构建模块集成"
  (it "Java 自动启用构建模块，根层聚合依赖并支持源码定位、成员查询和导出回读"
    (with-build-project
      {"user-api/pom.xml" "<project/>"
       "user-api/src/main/java/com/foo/Port.java" "package com.foo; public interface Port {}"
       "user-app/build.gradle" "无需解析的构建脚本"
       "user-app/src/main/java/com/foo/App.java" "package com.foo; class App implements Port {}"
       "user-app/src/test/java/com/foo/App.java" "无效的测试源码"
       "ARCHITECTURE.md" "## 核心子系统与职责\n### 接口\n- 职责：接口契约。\n[探索接口](#module=user-api)"}
      (fn [root]
        (let [architecture (sut/load-architecture root)
              state (web/create-state architecture root {})
              top (web/view-data state [])
              out (io/file root "architecture.edn")]
          (should= [{:name "user-api" :build-tool "maven" :directory "user-api" :source-paths ["user-api/src/main/java"]}
                    {:name "user-app" :build-tool "gradle" :directory "user-app" :source-paths ["user-app/src/main/java"]}]
                   (get-in architecture [:guidance :build-modules]))
          (should= ["user-api/src/main/java" "user-app/src/main/java"]
                   (get-in architecture [:guidance :source-paths]))
          (should= #{"user-api" "user-app"} (set (map :id (:nodes top))))
          (should= [{:name "user-api" :directory "user-api" :buildTool "maven"}
                    {:name "user-app" :directory "user-app" :buildTool "gradle"}]
                   (map :buildModule (:nodes top)))
          (should= ["user-api" "user-app"] (map :name (:buildModules (web/project-data state))))
          ;; 进入模块后就是普通软件包，不再标注构建模块。
          (should-not-contain :buildModule (first (:nodes (web/view-data state ["user-api"]))))
          (should= [{:from "user-app" :to "user-api" :type :abstract :count 1}] (:edges top))
          (should= ["user-api.com.foo.Port"] (:members (first (:nodes top))))
          (should= {:path ["user-app" "com" "foo"] :nodeId "App" :module "user-app.com.foo.App"}
                   (web/locate-data state "user-app.com.foo.App"))
          (should= "user-api.com.foo.Port"
                   (:sourceModule (first (:nodes (web/view-data state ["user-api" "com" "foo"])))))
          (with-redefs [sut/load-architecture (fn [_ _] architecture)]
            (sut/-main "--project-path" root "--no-gui" "--out" (str out)))
          (should= architecture (sut/load-architecture-edn (str out)))
          (with-redefs [sut/load-architecture (fn [& _] (throw (ex-info "不应重新扫描" {})))]
            (sut/-main "--in-edn" (str out) "--no-gui"))
          (should= #{"com.foo.Port" "com.foo.App"}
                   (get-in (sut/load-architecture root {:language :java
                                                       :source-paths ["user-api/src/main/java" "user-app/src/main/java"]})
                           [:graph :nodes]))))))

  (it "Kotlin 自动分组只扫描主源码，显式路径仍使用原文件节点"
    (with-build-project
      {"api/build.gradle.kts" "无需解析的构建脚本"
       "api/src/main/kotlin/p/Port.kt" "package p\ninterface Port"
       "app/build.gradle" ""
       "app/src/main/java/p/App.kt" "package p\nclass App(val p: Port)"
       "app/src/test/kotlin/p/App.kt" "无效的测试源码"}
      (fn [root]
        (let [architecture (sut/load-architecture root {:language :kotlin})]
          (should= #{"api.p.Port" "app.p.App"} (get-in architecture [:graph :nodes]))
          (should= #{{:from "app.p.App" :to "api.p.Port"}} (get-in architecture [:graph :edges]))
          (should= ["api/src/main/kotlin" "app/src/main/java"]
                   (get-in architecture [:guidance :source-paths])))
        (let [architecture (sut/load-architecture root {:language :kotlin :source-paths ["api/src/main/kotlin"]})]
          (should= #{"p.Port"} (get-in architecture [:graph :nodes]))
          (should-not-contain :build-modules (:guidance architecture))))))

  (it "Java 和 Kotlin 单模块即使有构建文件也保持原节点与 guidance"
    (doseq [[language extension code] [[:java "java" "package p; interface Port {}"]
                                      [:kotlin "kt" "package p\ninterface Port"]]]
      (with-build-project {(str "src/main/" (name language) "/p/Port." extension) code}
        (fn [root]
          (let [before (sut/load-architecture root {:language language})]
            (spit (io/file root "pom.xml") "<project/>")
            (should= before (sut/load-architecture root {:language language}))
            (should= #{"p.Port"} (get-in before [:graph :nodes]))
            (should-not-contain :build-modules (:guidance before))))))))

(describe "automatic multi-module Java loading"
  (it "discovers modules and preserves cross-module edges without CLI overrides"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "arch-auto-java" (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try
        (doseq [[path code] [["app/api/src/main/java/demo/Port.java" "package demo; public interface Port {}"]
                            ["app/web/src/main/java/demo/App.java" "package demo; class App implements Port {}"]
                            ["app/web/src/test/java/demo/App.java" "package demo; class App {}"]]]
          (let [file (java.io.File. root path)]
            (.mkdirs (.getParentFile file))
            (spit file code)))
        (let [architecture (sut/load-architecture (str root))]
          (should= :java (get-in architecture [:guidance :language]))
          (should= ["app/api/src/main/java" "app/web/src/main/java"]
                   (get-in architecture [:guidance :source-paths]))
          (should= #{"demo.Port" "demo.App"} (get-in architecture [:graph :nodes]))
          (should= #{{:from "demo.App" :to "demo.Port"}} (get-in architecture [:graph :edges])))
        (should= #{"demo.Port"}
                 (get-in (sut/load-architecture (str root) {:source-paths ["app/api/src/main/java"]})
                         [:graph :nodes]))
        (finally (doseq [file (reverse (file-seq root))] (.delete file)))))))
