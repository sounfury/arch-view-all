;; 验收测试：通过真实网页接口检查架构浏览、说明文档、源码读取和重新分析是否正常。

(ns arch-view.web-acceptance
  "HTTP acceptance scenarios using real temporary projects; no new unit tests."
  (:require [arch-view.core :as core]
            [clojure.edn :as edn]
            [arch-view.web.server :as server]
            [arch-view.web.model :as model]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.time Duration]))

(declare ^:private architecture-session! ^:private optional-documents! ^:private lazy-dependency-session!
         ^:private language-adapters-session! ^:private cli-session! ^:private status-session!
         ^:private launcher-session! ^:private port-session!
         ^:private with-project ^:private check! ^:private request ^:private encode)

(defn -main [& _]
  (try
    (architecture-session!) (optional-documents!) (lazy-dependency-session!)
    (language-adapters-session!)
    (cli-session!)
    (launcher-session!) (port-session!)
    (status-session!)
    (println "Web HTTP 验收通过；视觉与真实项目使用仍需人工验收。")
    (shutdown-agents)
    (catch Exception ex
      (binding [*out* *err*] (println "FAIL:" (.getMessage ex)))
      (System/exit 1))))

;; ===== 私有方法 =====

(defn- status-session! []
  (with-project {"src/demo/core.clj" "(ns demo.core)"
                 "ARCHITECTURE.md" (str "# 项目\r\n\r\n## 核心子系统与职责\r\n\r\n"
                                       "### core · 运行调度\r\n\r\n- **职责**：组织运行。\r\n"
                                       "- **实现状态**：已完成。\r\n\r\n```markdown\r\n"
                                       "### core · 运行调度\r\n- **实现状态**：示例状态。\r\n```\r\n\r\n"
                                       "### planned · 规划\r\n\r\n- **职责**：尚未启动。\r\n")}
    (fn [root]
      (let [state (model/create-state (core/load-architecture root) root {})
            app (server/start! (:architecture state) root {:port 0} nil)
            original (:content (model/document-data state "ARCHITECTURE.md"))
            change! (fn [status] (request (:url app)
                                         (str "/api/subsystem-status?id=ARCHITECTURE.md&heading="
                                              (encode "core · 运行调度") "&status=" (encode status)) :post {}))]
        (try
          (doseq [status ["进行中" "未完成" "已完成"]]
            (check! (= 200 (:status (change! status))) "三项人工状态必须能保存")
            (check! (= (str/replace-first original "**实现状态**：已完成。" (str "**实现状态**：" status "。"))
                       (:content (model/document-data state "ARCHITECTURE.md")))
                    "状态保存只能修改对应行，必须保留正文、代码示例和原换行"))
          (check! (= 400 (:status (change! "未知状态"))) "必须拒绝三项之外的状态")
          (check! (= original (:content (model/document-data state "ARCHITECTURE.md"))) "非法状态不能改动文档")
          (check! (= 403 (:status (request (:url app)
                                          (str "/api/subsystem-status?id=ARCHITECTURE.md&heading="
                                               (encode "core · 运行调度") "&status=" (encode "未完成"))
                                          :post {"Origin" "https://other.example"}))) "外站不能修改实现状态")
          (check! (= 404 (:status (request (:url app) "/api/subsystem-status?id=../outside.md&heading=x&status=%E5%B7%B2%E5%AE%8C%E6%88%90" :post {})))
                  "不能写入未收录的文档")
          (check! (= 200 (:status (request (:url app)
                                          (str "/api/subsystem-status?id=ARCHITECTURE.md&heading=" (encode "planned · 规划")
                                               "&status=" (encode "未完成")) :post {}))) "缺少状态行时必须能补上人工选择")
          (finally ((:stop app)))))))
  (println "PASS: 三项人工状态、只修改对应字段、保留正文与换行、忽略代码示例、补充状态与同源访问限制"))

(defn- check! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn- request [url path method headers]
  (let [builder (-> (HttpRequest/newBuilder (URI. (str url path)))
                    (.timeout (Duration/ofSeconds 30)))
        builder (if (= method :post) (.POST builder (java.net.http.HttpRequest$BodyPublishers/noBody)) (.GET builder))]
    (doseq [[k v] headers] (.header builder k v))
    (let [response (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (.body response)})))

(defn- get! [app path] (request (:url app) path :get {}))

(defn- contains-text? [response text] (str/includes? (:body response) text))

(defn- encode [s] (URLEncoder/encode s "UTF-8"))

(defn- with-project [files run]
  (let [root (.toFile (Files/createTempDirectory "arch-view web 中文 " (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path text] files]
        (let [file (io/file root path)] (.mkdirs (.getParentFile file)) (spit file text :encoding "UTF-8")))
      (run (.getCanonicalPath root))
      (finally (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn- architecture-session! []
  ;; Given existing central/package docs and source comments, when the independent
  ;; Web UI starts, then macro docs and real drill-down/source/dependencies work.
  (with-project
    {"README.md" "# 示例项目\n\n用户背景。\n"
     "docs/design.md" "# 宏观架构\n\n业务目的。\n\n## app · 应用\n\n负责入口。\n<script>alert(1)</script>\n"
     "src/demo/app/README.md" "# 应用包\n\n普通说明。\n"
     "src/demo/app/ARCHITECTURE.md" "# 应用包\n\n包职责。\n"
     "src/demo/app.clj" "(ns demo.app)"
     "src/demo/entry.clj" "(ns demo.entry)"
     "src/demo/plain/main.clj" "(ns demo.plain.main)"
     "src/demo/multi/a.clj" ";; 职责：多个文件中第一个文件的说明。\n(ns demo.multi.a)"
     "src/demo/multi/b.clj" ";; 职责：多个文件中第二个文件的说明。\n(ns demo.multi.b)"
     "src/demo/app/main.clj" ";; 文件说明：启动应用\n(ns demo.app.main (:require [demo.lib.service :as service]))\n"
     "src/demo/lib/service.clj" "(ns demo.lib.service \"模块文档字符串\" (:require [demo.app.main :as main]))\n(def x \"<script>源码仅作文本</script>\")"}
    (fn [root]
      (let [opts {:port 0 :architecture-doc "docs/design.md"}
            fail-reload? (atom false)
            load! #(if @fail-reload? (throw (ex-info "模拟扫描失败" {}))
                       (model/create-state (core/load-architecture root) root opts))
            app (server/start! (core/load-architecture root) root opts load!)]
        (try
          (let [project (get! app "/api/project") view (get! app "/api/view")]
            (check! (contains-text? project "\"namespaceRootDepth\":1")
                    "网页覆盖检查必须沿用 Clojure 架构图的包层级，不能把隐藏根命名空间算作未覆盖")
            (check! (= 200 (:status project)) "Project metadata must be served")
            (check! (contains-text? project "\"canReanalyze\":true") "Live sessions must allow reanalysis")
            (check! (contains-text? project "docs/design.md") "Explicit existing document must be discovered")
            (check! (contains-text? project "src/demo/app/ARCHITECTURE.md") "Optional package architecture doc must be discovered")
            (check! (not (contains-text? project "src/demo/app/README.md")) "Package README must not replace architecture docs")
            (check! (contains-text? view "\"id\":\"app\"") "Root must group source namespaces")
            (check! (contains-text? view "demo.app.main") "Root groups must retain member modules")
            (check! (contains-text? view "app->lib->app") "Cycle data must remain visible"))
          (let [nodes (into {} (map (juxt :id identity)) (:nodes (model/view-data (load!) [])))
                view (get! app "/api/view")]
            (check! (= "文件说明：启动应用" (get-in nodes ["app" :description]))
                    "单文件分组必须直接展示唯一源码文件的头部说明")
            (check! (= "模块文档字符串" (get-in nodes ["lib" :description]))
                    "单文件分组必须兼容命名空间说明")
            (check! (and (false? (get-in nodes ["app" :leaf]))
                         (nil? (get-in nodes ["app" :sourceModule]))
                         (true? (get-in nodes ["app" :descriptionFromOnlyFile])))
                    "继承说明不能把分组变成源码叶子节点")
            (check! (nil? (get-in nodes ["multi" :description]))
                    "多文件分组不能任取一个文件的说明作为整体职责")
            (check! (nil? (get-in nodes ["plain" :description]))
                    "唯一文件没有说明时必须保留原有提示")
            (check! (contains-text? view "\"descriptionFromOnlyFile\":true")
                    "网页接口必须标明说明来自唯一源码文件")
            (check! (not (contains-text? view "多个文件中第一个文件的说明"))
                    "网页接口不得把多文件分组误标为单文件说明"))
          (let [view (get! app "/api/view?path=app")
                source (get! app "/api/source?module=demo.app.main")
                ns-source (get! app "/api/source?module=demo.lib.service")]
            (check! (contains-text? view "\"sourceModule\":\"demo.app.main\"") "Drill-down leaves must identify their source")
            (check! (contains-text? view "demo.lib.service") "Cross-scope dependencies must be retained")
            (check! (contains-text? source "文件说明：启动应用") "Leading comments must be available as descriptions")
            (check! (contains-text? ns-source "模块文档字符串") "Clojure namespace docstrings must be available"))
          ;; 从当前包之外的依赖获取位置，进入返回的层级后必须能选中对应源码。
          (let [location (get! app "/api/locate?module=demo.lib.service")
                target (get! app "/api/view?path=lib")
                mixed (get! app "/api/locate?module=demo.app")
                root-leaf (get! app "/api/locate?module=demo.entry")]
            (check! (= 200 (:status location)) "跨包依赖必须能直接定位")
            (check! (contains-text? location "\"path\":[\"lib\"]") "定位必须返回目标所在层级")
            (check! (contains-text? location "\"nodeId\":\"service\"") "定位必须返回可选中的模块标识")
            (check! (contains-text? target "\"sourceModule\":\"demo.lib.service\"") "目标视图必须包含相应源码模块")
            (check! (contains-text? mixed "\"nodeId\":\"app|file\"") "同名文件与包并存时必须定位文件节点")
            (check! (contains-text? root-leaf "\"path\":[]") "顶层源码文件必须定位到根视图"))
          (check! (= 404 (:status (get! app "/api/locate?module=unknown"))) "未知模块必须明确返回未找到")
          (doseq [path ["/" "/app.css" "/app.js" "/architecture_document.js" "/vendor/marked.umd.js" "/vendor/purify.min.js" "/vendor/mermaid.min.js"]]
            (check! (= 200 (:status (get! app path))) (str "Local asset must be served: " path)))
          (check! (= 404 (:status (get! app "/api/document?id=../outside.md"))) "Documents cannot read arbitrary files")
          (check! (= 404 (:status (get! app "/api/source?module=unknown"))) "Unknown source must return 404")
          (check! (= 403 (:status (get! app "/api/image?path=../outside.png"))) "Images cannot escape the project")
          (check! (= 403 (:status (request (:url app) "/api/project" :get {"Origin" "https://other.example"}))) "Foreign origins must be refused")
          (reset! fail-reload? true)
          (check! (= 500 (:status (request (:url app) "/api/reanalyze" :post {}))) "Scan errors must be reported")
          (check! (= 200 (:status (get! app "/api/view"))) "Failed scans must retain the previous architecture")
          (reset! fail-reload? false)
          (spit (io/file root "src/demo/app/extra.clj") "(ns demo.app.extra)")
          (check! (= 200 (:status (request (:url app) "/api/reanalyze" :post {}))) "Reanalysis must succeed")
          (check! (contains-text? (get! app "/api/view?path=app") "demo.app.extra") "Reanalysis must reflect source edits")
          (let [nodes (into {} (map (juxt :id identity)) (:nodes (model/view-data (load!) [])))]
            (check! (nil? (get-in nodes ["app" :description]))
                    "重新分析发现第二个文件后必须撤销继承的单文件说明"))
          (println "PASS: 文档、文件头、真实依赖、下钻、跨包定位、循环、资源、访问边界与重新分析")
          (finally ((:stop app))))))))

(defn- lazy-dependency-session! []
  ;; 按需加载的适配器应与其他适配器连到同一个入口；源码只能读取，不能执行。
  (with-project
    {"src/demo/input/languages.clj"
     (str "(ns demo.input.languages (:require [demo.input.clojure.extract] [demo.input.python.extract]))\n"
          "(def adapters {:kotlin (fn [] (requiring-resolve 'demo.input.kotlin.extract/run))\n"
          "               :other (fn [] (clojure.core/requiring-resolve 'demo.input.other.extract/run))})\n"
          "(def examples '(requiring-resolve 'demo.input.ignored/run))\n"
          "(comment (requiring-resolve 'demo.input.ignored/run))\n"
          "(def text \"(requiring-resolve 'demo.input.ignored/run)\")\n"
          ";; (requiring-resolve 'demo.input.ignored/run)\n"
          "(defn dynamic-target [] (requiring-resolve (symbol \"demo.input.ignored\" \"run\")))\n")
     "src/demo/input/clojure/extract.clj" "(ns demo.input.clojure.extract)"
     "src/demo/input/python/extract.clj" "(ns demo.input.python.extract)"
     "src/demo/input/kotlin/extract.clj" "(ns demo.input.kotlin.extract)\n(throw (ex-info \"禁止执行被扫描的源码\" {}))"
     "src/demo/input/other/extract.clj" "(ns demo.input.other.extract)"
     "src/demo/input/ignored.clj" "(ns demo.input.ignored)"
     "src/demo/input/fallback.clj" "(ns demo.input.fallback (:require [demo.input.python.extract]))\n::missing-alias/value"}
    (fn [root]
      (let [architecture (core/load-architecture root)
            state (model/create-state architecture root {})
            nodes (into {} (map (juxt :id identity)) (:nodes (model/view-data state ["input"])))
            app (server/start! architecture root {:port 0} nil)]
        (try
          (check! (= #{{:from "demo.input.languages" :to "demo.input.clojure.extract"}
                      {:from "demo.input.languages" :to "demo.input.python.extract"}
                      {:from "demo.input.languages" :to "demo.input.kotlin.extract"}
                      {:from "demo.input.languages" :to "demo.input.other.extract"}
                      {:from "demo.input.fallback" :to "demo.input.python.extract"}}
                     (get-in architecture [:graph :edges]))
                  "按需加载必须形成真实依赖，不能把引用、注释或计算出的目标误判为依赖")
          (check! (= (get-in nodes ["clojure" :layer]) (get-in nodes ["python" :layer])
                     (get-in nodes ["kotlin" :layer]) (get-in nodes ["other" :layer]))
                  "同一入口下的语言适配器必须展示在同一依赖层")
          (check! (< (get-in nodes ["languages" :layer]) (get-in nodes ["kotlin" :layer]))
                  "按需加载的 Kotlin 适配器必须位于语言入口的下一层")
          (let [view (get! app "/api/view?path=input")]
            (check! (= 200 (:status view)) "按需加载依赖必须可通过网页查看")
            (check! (contains-text? view "demo.input.kotlin.extract") "网页必须保留 Kotlin 模块")
            (check! (contains-text? (get! app "/api/project") "\"dependencyCount\":5")
                    "网页必须展示补全后的依赖数量"))
          (println "PASS: 按需加载连线、适配器层级、仅静态读取与无法读取时的兼容处理")
          (finally ((:stop app))))))))

(defn- optional-documents! []
  ;; Given a project with no documents, when browsing its architecture, then no
  ;; docs or AI are required; read-only EDN-style sessions hide reload support.
  (with-project {"src/demo/main.clj" "(ns demo.main)"}
    (fn [root]
      (let [app (server/start! (core/load-architecture root) root {:port 0} nil)]
        (try
          (let [project (get! app "/api/project")]
            (check! (contains-text? project "\"documents\":[]") "Documents must be optional")
            (check! (contains-text? project "\"canReanalyze\":false") "Snapshots must hide reload"))
          (check! (contains-text? (get! app "/api/view") "main") "Undocumented projects must remain browseable")
          (check! (= 409 (:status (request (:url app) "/api/reanalyze" :post {}))) "Snapshots must reject reanalysis")
          (println "PASS: 无文档项目与只读快照")
          (finally ((:stop app))))))))

(defn- language-adapters-session! []
  ;; 用真实注册入口检查四种语言连线，再通过网页接口验收 Java 多模块项目。
  (let [architecture (core/load-architecture "." {:language :clojure})
        nodes (into {} (map (juxt :id identity))
                    (:nodes (model/view-data (model/create-state architecture "." {}) ["input"])))]
    (doseq [language ["clojure" "python" "kotlin" "java"]]
      (check! (contains? (get-in architecture [:graph :edges])
                         {:from "arch-view.input.languages"
                          :to (str "arch-view.input." language ".dependency-extract")})
              (str "统一入口必须连接 " language " 分析器")))
    (check! (apply = (map #(get-in nodes [% :layer]) ["clojure" "python" "kotlin" "java"]))
            "四个分析器在自身架构图中必须位于同一层"))
  (with-project
    {"pom.xml" "<project/>"
     "api/src/main/java/demo/api/Port.java" "package demo.api; public interface Port {}"
     "impl/src/main/java/demo/impl/Service.java" "package demo.impl; public class Service implements demo.api.Port {}"
     "impl/src/main/java/demo/impl/Plugin.java" "package demo.impl; public class Plugin {}"
     "app/src/main/java/demo/app/App.java"
     (str "// 职责：启动示例应用。\npackage demo.app; public class App {\n"
          "demo.api.Port port = new demo.impl.Service();\n"
          "static { if (true) throw new RuntimeException(\"禁止执行目标代码\"); }\n"
          "void lazy() throws Exception { Class.forName(\"demo.impl.Plugin\"); } }")
     "app/src/test/java/Bad.java" "测试源码不应扫描"}
    (fn [root]
      (let [architecture (core/load-architecture root)
            app (server/start! architecture root {:port 0}
                               #(model/create-state (core/load-architecture root) root {}))]
        (try
          (check! (= :java (get-in architecture [:guidance :language])) "Java 多模块项目必须自动识别")
          (check! (= #{{:from "demo.impl.Service" :to "demo.api.Port"}
                      {:from "demo.app.App" :to "demo.api.Port"}
                      {:from "demo.app.App" :to "demo.impl.Service"}}
                     (get-in architecture [:graph :edges]))
                  "Java 必须识别跨模块实际类型引用；当前反射字符串不计入依赖")
          (check! (contains-text? (get! app "/api/view?path=demo/app") "demo.app.App")
                  "Java 源码必须支持网页逐层下钻")
          (check! (contains-text? (get! app "/api/source?module=demo.app.App") "职责：启动示例应用")
                  "Java 文件头说明必须能从网页读取")
          (check! (contains-text? (get! app "/api/locate?module=demo.impl.Service") "\"path\":[\"demo\",\"impl\"]")
                  "Java 跨包依赖必须支持直接定位")
          (check! (= 200 (:status (request (:url app) "/api/reanalyze" :post {})))
                  "Java 项目必须支持网页重新分析")
          (println "PASS: 四种语言接入层级与 Java 自动识别、跨模块依赖、源码说明、定位和重新分析")
          (finally ((:stop app)))))))
  (with-project {"build.gradle.kts" "plugins {}" "App.kt" "class App"}
    (fn [root]
      (let [architecture (core/load-architecture root)]
        (check! (= :kotlin (get-in architecture [:guidance :language]))
                "自动识别必须保留 Kotlin 支持，不能被共享的构建标记误判为 Java"))))
  (with-project {"build.gradle.kts" "plugins {}" "App.java" "class App {}"}
    (fn [root]
      (check! (= :java (get-in (core/load-architecture root) [:guidance :language]))
              "Java 项目使用 Kotlin 构建脚本时仍必须识别为 Java"))))

(defn- run-command [root command]
  (let [process (-> (ProcessBuilder. ^java.util.List command)
                    (.directory (io/file root)) (.redirectErrorStream true) (.start))]
    (try
      (check! (.waitFor process 30 java.util.concurrent.TimeUnit/SECONDS) "命令验收不能挂起或意外打开窗口")
      {:status (.exitValue process) :body (slurp (.getInputStream process) :encoding "UTF-8")}
      (finally (.destroyForcibly process)))))

(defn- run-cli [root args]
  (let [java-bin (io/file (System/getProperty "java.home") "bin"
                          (if (str/starts-with? (System/getProperty "os.name") "Windows") "java.exe" "java"))
        tool-root (.getCanonicalPath (io/file "."))
        classpath (str/join java.io.File/pathSeparator
                            (map #(.getCanonicalPath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator)))))
        command (into [(str java-bin) "-Dfile.encoding=UTF-8" "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8"
                       (str "-Darch-view.home=" tool-root)
                       "-cp" classpath "clojure.main" "-m" "arch-view.cli"] args)]
    (run-command root command)))

(defn- cli-session! []
  ;; 从带中文和空格的目标项目目录启动独立进程，验证命令入口不依赖调用者的工具源码目录。
  (with-project {"src/demo/main.clj" "(ns demo.main (:require [demo.helper]))"
                 "src/demo/helper.clj" "(ns demo.helper)"
                 ".env" "ARCH_VIEW_NO_GUI=false\n"}
    (fn [root]
      (let [help (run-cli root [])]
        (check! (and (= 0 (:status help)) (contains-text? help "用法：arch-view"))
                "无参数调用必须显示帮助并退出，不能意外打开桌面窗口"))
      (let [scan (run-cli root ["scan" "." "--out" "architecture.edn"])
            legacy (run-cli root ["--project-path" "." "--no-gui" "--out" "legacy.edn"])
            desktop (run-cli root ["desktop" "." "--no-gui" "--out" "desktop.edn"])]
        (doseq [response [scan legacy desktop]]
          (check! (= 0 (:status response)) (str "新旧分析命令必须成功：" (:body response))))
        (let [architecture (edn/read-string (slurp (io/file root "architecture.edn") :encoding "UTF-8"))]
          (check! (= #{"demo.main" "demo.helper"} (get-in architecture [:graph :nodes]))
                  "命令必须分析调用者项目，不能错误切换到工具目录")
          (doseq [name ["legacy.edn" "desktop.edn"]]
            (check! (= (:graph architecture)
                       (:graph (edn/read-string (slurp (io/file root name) :encoding "UTF-8"))))
                    "新旧桌面参数与 scan 导出的依赖必须一致")))
        (check! (= 0 (:status (run-cli root ["init" "."]))) "必须能从工具安装目录复制模板")
        (let [template (slurp "ARCHITECTURE_TEMPLATE.md" :encoding "UTF-8")]
          (check! (= template (slurp (io/file root "ARCHITECTURE_TEMPLATE.md") :encoding "UTF-8"))
                  "架构模板必须完整复制")
          (check! (not (str/includes? template "给 AI 的架构文档生成提示词"))
                  "模板只保留架构格式，生成提示词必须放在技能中")
          (check! (.isFile (io/file "skills/arch-view/references/architecture-prompts.md"))
                  "新老项目生成提示词必须位于技能参考文件"))
        (spit (io/file root "ARCHITECTURE_TEMPLATE.md") "用户修改过的模板" :encoding "UTF-8")
        (check! (= 1 (:status (run-cli root ["init" "."]))) "已有模板时必须拒绝覆盖")
        (check! (= "用户修改过的模板" (slurp (io/file root "ARCHITECTURE_TEMPLATE.md") :encoding "UTF-8"))
                "重复初始化不能破坏用户文件")
        (doseq [args [["serve" "--unknown"] ["scan" "--project-path"] ["unknown-command"]]]
          (check! (= 1 (:status (run-cli root args))) "错误命令必须返回失败状态"))
        (check! (contains-text? (run-cli root ["serve" "--help"]) "网页工作台")
                "网页子命令必须提供中文帮助"))))
  (let [architecture (core/load-architecture "." {:language :clojure})
        state (model/create-state architecture "." {})
        cli (some #(when (= "cli" (:id %)) %) (:nodes (model/view-data state [])))]
    (check! (= "arch-view.cli" (:sourceModule cli)) "统一命令入口必须出现在自身的架构图中")
    (doseq [target ["arch-view.core" "arch-view.web.server"]]
      (check! (contains? (get-in architecture [:graph :edges]) {:from "arch-view.cli" :to target})
              "架构图必须识别 CLI 到网页和桌面的按需加载依赖")))
  (println "PASS: 统一命令、跨项目运行、旧参数兼容、相对路径导出、模板保护、中文帮助与架构接入"))

(defn- launcher-session! []
  ;; 通过真实 Windows 安装入口验收参数传递，不能仅绕过包装脚本调用 Java。
  (when (str/starts-with? (System/getProperty "os.name") "Windows")
    (with-project {"src/demo/main.clj" "(ns demo.main)"}
      (fn [root]
        (let [powershell (str (io/file (System/getenv "SystemRoot") "System32/WindowsPowerShell/v1.0/powershell.exe"))
              pwsh (io/file (System/getenv "ProgramFiles") "PowerShell/7/pwsh.exe")
              destination (str (io/file root "命令入口"))
              installer (.getCanonicalPath (io/file "bin/install.ps1"))
              installed (run-command root [powershell "-NoProfile" "-File" installer "-Destination" destination])
              launcher (str (io/file destination "arch-view.ps1"))
              quote-ps #(str "'" (str/replace % "'" "''") "'")]
          (check! (= 0 (:status installed)) (str "安装入口必须能生成可运行的包装脚本：" (:body installed)))
          (doseq [[index shell] (map-indexed vector (cond-> [powershell] (.isFile pwsh) (conj (str pwsh))))]
            (let [output (str "导出 数据 " index ".edn")
                  response (run-command root [shell "-NoProfile" "-Command"
                                              (str "& " (quote-ps launcher) " scan . --out " (quote-ps output))])
                  invalid (run-command root [shell "-NoProfile" "-Command"
                                             (str "& " (quote-ps launcher) " scan . -out invalid.edn")])]
              (check! (= 0 (:status response)) (str "未加引号的 --out 必须能通过包装脚本：" (:body response)))
              (check! (= #{"demo.main"} (get-in (edn/read-string (slurp (io/file root output) :encoding "UTF-8")) [:graph :nodes]))
                      "中文与空格路径必须完整传递，并导出调用者项目")
              (check! (and (= 1 (:status invalid)) (contains-text? invalid "当前命令不支持参数：-out"))
                      "单横线参数必须交给 CLI 报错，不能被 PowerShell 的通用参数机制拦截")))
          (let [response (run-command root ["cmd.exe" "/d" "/c"
                                            (str "call \"" (io/file destination "arch-view.cmd") "\" scan . --out \"命令提示符 数据.edn\"")])]
            (check! (= 0 (:status response)) (str "命令提示符入口也必须支持导出：" (:body response)))
            (check! (.isFile (io/file root "命令提示符 数据.edn")) "命令提示符入口必须保留输出路径"))))))
  (println "PASS: Windows PowerShell、PowerShell 7 与命令提示符包装入口参数传递（非 Windows 跳过）"))

(defn- port-session! []
  ;; 使用真实监听端口验收默认回退和显式端口冲突，不关闭已有的用户服务。
  (with-project {"src/demo/main.clj" "(ns demo.main)"}
    (fn [root]
      (let [architecture (core/load-architecture root)
            reserved (try (server/start! architecture root {:port 7331} nil)
                          (catch clojure.lang.ExceptionInfo _ nil))]
        (try
          (let [app (server/start! architecture root {} nil)]
            (try
              (check! (and (pos? (:port app)) (not= 7331 (:port app))) "默认端口占用时必须自动改用空闲端口")
              (check! (= 200 (:status (get! app "/api/project"))) "改用端口后的服务必须可访问")
              (let [error (try (let [unexpected (server/start! architecture root {:port (:port app)} nil)]
                                ((:stop unexpected)) nil)
                              (catch clojure.lang.ExceptionInfo ex (.getMessage ex)))]
                (check! (and error (str/includes? error "已被占用")
                             (str/includes? error "--port 0") (str/includes? error "OwningProcess"))
                        "显式端口冲突必须给出端口选择和进程查询提示"))
              (check! (= 200 (:status (get! app "/api/project"))) "冲突失败不能接管或停止原服务")
              (finally ((:stop app)))))
          (finally (when reserved ((:stop reserved))))))))
  (println "PASS: 默认端口占用自动回退、显式端口冲突诊断与原服务保护"))
