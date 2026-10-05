;; 验收测试：用实际项目（Kotlin）检查扫描、依赖、分组、导出和重新分析等完整流程。

(ns arch-view.kotlin-acceptance
  "End-to-end Kotlin acceptance checks; run with src and dev on the classpath."
  (:require [arch-view.core :as core]
            [arch-view.domain.architecture-projection :as projection]
            [arch-view.render.ui.quil.view :as render]
            [arch-view.render.ui.util.source-html :as source-html]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- check! [expected actual description]
  (when-not (= expected actual)
    (throw (ex-info description {:expected expected :actual actual}))))

(defn- with-project [files run]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-view Kotlin 中文 "
                       (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path source] files]
        (let [file (io/file root path)]
          (.mkdirs (.getParentFile file))
          (spit file source :encoding "UTF-8")))
      (run (.getCanonicalPath root))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn- accepts-project! []
  ;; Given a multi-module project, when Kotlin is selected, then its files,
  ;; imports, same-package references, abstractions and shared view are available.
  (with-project
    {"app/src/main/kotlin/Main.kt"
     (str "package sample.app\n"
          "import sample.api.Port as ServicePort\n"
          "import sample.util.*\n"
          "import external.Unknown\n"
          "class Main(val port: ServicePort) { fun run() = format(Helper()) }\n"
          "val text = \"sample.unused.Ghost <script>\"\n"
          "/* import sample.unused.Ghost */\n")
     "app/src/main/kotlin/Helper.kt" "package sample.app\nclass Helper\n"
     "api/src/commonMain/kotlin/Contract.kt" "package sample.api\ninterface Port\n"
     "api/src/commonMain/kotlin/Format.kt" "package sample.util\nfun format(value: Any) = value.toString()\n"
     "api/src/commonMain/kotlin/Unused.kt" "package sample.util\nclass Unused\n"
     "api/src/commonMain/kotlin/Ghost.kt" "package sample.unused\nclass Ghost\n"
     "api/src/commonMain/kotlin/Qualified.kt" "package sample.app\nval port: sample.api.Port? = null\n"
     "api/src/commonMain/kotlin/Base.kt" "package sample.api\nabstract class Base\n"
     "build/generated/Bad.kt" "invalid Kotlin ("
     ".gradle/cache/Bad.kt" "invalid Kotlin ("
     "build.gradle.kts" "invalid Kotlin ("}
    (fn [root]
      (let [architecture (core/load-architecture root {:language :kotlin})
            graph (:graph architecture)
            nested (projection/view-architecture architecture ["sample" "app"])
            out (io/file root "architecture.edn")]
        (check! #{"sample.app.Main" "sample.app.Helper" "sample.api.Contract"
                  "sample.util.Format" "sample.util.Unused" "sample.unused.Ghost"
                  "sample.app.Qualified" "sample.api.Base"}
                (:nodes graph) "Kotlin files must be grouped by their declared packages")
        (check! #{{:from "sample.app.Main" :to "sample.api.Contract"}
                  {:from "sample.app.Main" :to "sample.util.Format"}
                  {:from "sample.app.Main" :to "sample.app.Helper"}
                  {:from "sample.app.Qualified" :to "sample.api.Contract"}}
                (:edges graph) "Only internal imports and referenced wildcard/same-package symbols form edges")
        (check! #{"sample.api.Contract" "sample.api.Base"}
                (:abstract-modules graph) "Interfaces and abstract classes must be abstract modules")
        (check! #{"Main" "Helper" "Qualified"} (get-in nested [:graph :nodes])
                "Kotlin files must be accessible through package drill-down")
        (check! true (.endsWith (get-in nested [:module->source-file "Main"]) "Main.kt")
                "File leaves must retain absolute source paths")
        (let [source (get-in nested [:module->source-file "Main"])
              html (source-html/source->html source (slurp source :encoding "UTF-8"))]
          (check! true (str/includes? html "&lt;script&gt;") "Source viewing must escape Kotlin text")
          (check! false (str/includes? html "<span class='kw'>") "Kotlin source must not use Clojure keyword markup"))
        (core/-main "--language" "kotlin" "--project-path" root "--no-gui" "--out" (str out))
        (check! graph (:graph (edn/read-string (slurp out))) "CLI EDN export must preserve the graph")
        (let [reloaded (atom nil)]
          (with-redefs [render/show! (fn [_ opts]
                                      (reset! reloaded ((:reload-architecture opts))))
                        render/wait-until-closed! (fn [_])
                        core/exit-program! (fn [])]
            (core/-main "--language" "kotlin" "--project-path" root))
          (check! graph (:graph @reloaded) "Reanalyze must preserve the Kotlin language selection"))))))

(defn- accepts-source-roots! []
  ;; Given overlapping custom roots and default-package scripts, when exported,
  ;; then every source appears once and non-Kotlin sources stay outside the graph.
  (with-project {"code/Runner.kts" "import answer\nprintln(answer())\n"
                 "code/Util.kt" "fun answer() = 42\n"
                 "src/Skipped.kt" "class Skipped\n"
                 "code/Ignored.java" "class Ignored {}\n"}
    (fn [root]
      (let [architecture (core/load-architecture root {:language :kotlin :source-paths ["code" "code"]})]
        (check! #{"Runner" "Util"} (get-in architecture [:graph :nodes]) "Explicit source roots must be respected")
        (check! #{{:from "Runner" :to "Util"}} (get-in architecture [:graph :edges]) "Scripts can import top-level functions")
        (check! #{"Runner" "Util"}
                (get-in (projection/view-architecture architecture []) [:graph :nodes])
                "Default-package files must appear in the top-level view")))))

(defn- rejects-incomplete-analysis! []
  ;; Given malformed Kotlin or duplicate file modules, when analyzed,
  ;; then the caller receives a useful path instead of an incomplete diagram.
  (doseq [files [{"src/Broken.kt" "class Broken {\n"}
                 {"src/one/Same.kt" "package duplicate\nclass One\n"
                  "src/two/Same.kt" "package duplicate\nclass Two\n"}]]
    (with-project files
      (fn [root]
        (let [failure (try (core/load-architecture root {:language :kotlin}) nil
                           (catch clojure.lang.ExceptionInfo ex ex))]
          (check! true (instance? clojure.lang.ExceptionInfo failure) "Invalid projects must report an analysis error")
          (check! true (boolean (re-find #"\.kt" (.getMessage failure))) "Analysis errors must name the source file")))))
  (with-project {"src/Valid.kt" "class Valid"}
    (fn [root]
      (let [failure (try (core/load-architecture root {:language :kotlin :source-paths ["missing"]}) nil
                         (catch clojure.lang.ExceptionInfo ex ex))]
        (check! true (instance? clojure.lang.ExceptionInfo failure) "Missing source roots must report an error")
        (check! true (str/includes? (.getMessage failure) "missing") "Missing-root errors must name the directory")))))

(defn- accepts-declarations! []
  ;; Given Kotlin declarations and scoped names, when the whole project is
  ;; analyzed, then aliases/members/extensions resolve without shadowed edges.
  (with-project
    {"src/api/Types.kt" (str "package api\nclass Outer { class Nested; companion object Factory { fun make() = Outer() } }\n"
                             "object Config { val VALUE = 1 }\ntypealias Alias = Outer\n")
     "src/client/Use.kt" (str "package client\nimport api.Outer.Nested as N\nimport api.Outer.make\n"
                              "import api.Config.VALUE\nimport api.Alias\n"
                              "val x: Alias = make()\nval n = N()\nval v = VALUE\n")
     "src/client/Extensions.kt" "package client\nfun String.trimmed() = trim()\n"
     "src/other/ExtensionUse.kt" "package other\nimport client.*\nval result = \" test \".trimmed()\n"
     "src/client/Values.kt" "package client\nval value = 1\n"
     "src/client/Shadow.kt" (str "package client\nfun parameter(value: Int) = value\n"
                                 "fun local(): Int { val value = 2; return value }\n"
                                 "val lambda = { value: Int -> value }\n")}
    (fn [root]
      (let [architecture (core/load-architecture root {:language :kotlin})]
        (check! #{{:from "client.Use" :to "api.Types"}
                  {:from "other.ExtensionUse" :to "client.Extensions"}}
                (get-in architecture [:graph :edges])
                "Nested declarations, companion aliases and extensions must resolve; locals must not form edges")))))

(defn- accepts-crlf-sources! []
  ;; 编辑工具常把部分文件写成 CRLF；Kotlin PSI 只认 LF，混用时也必须照常分析。
  (with-project
    {"src/app/Reader.kt" (str "// 读取端口\r\npackage app\r\n\r\nimport core.Model\r\n\r\n"
                              "/**\r\n * 说明\r\n */\r\ninterface Reader {\r\n    fun read(): Model\r\n}\r\n")
     "src/core/Model.kt" "package core\n\ndata class Model(val name: String)\n"}
    (fn [root]
      (let [architecture (core/load-architecture root {:language :kotlin})]
        (check! #{{:from "app.Reader" :to "core.Model"}} (get-in architecture [:graph :edges])
                "CRLF sources mixed with LF sources must parse")))))

(defn -main [& _]
  (accepts-project!)
  (accepts-source-roots!)
  (accepts-declarations!)
  (accepts-crlf-sources!)
  (rejects-incomplete-analysis!)
  (println "Kotlin acceptance passed: scan, graph, declarations, projection, source view, CLI export, reload and diagnostics.")
  (shutdown-agents))
