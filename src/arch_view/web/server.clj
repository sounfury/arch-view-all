;; 职责：启动本机网页服务，提供架构浏览、源码查看、重新分析、复杂度展示和人工实现状态保存功能。
;; 核心入口：主启动函数（-main）；启动服务（start!）。

(ns arch-view.web.server
  "Independent localhost Web UI. The existing desktop entry point is unchanged."
  (:require [arch-view.core :as core]
            [arch-view.config :as config]
            [arch-view.web.complexity :as complexity]
            [arch-view.web.json :as json]
            [arch-view.web.model :as model]
            [arch-view.web.documents :as documents]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder URI BindException]
           [java.awt Desktop]
           [java.util.concurrent Executors]))

(declare ^:private same-origin? ^:private route! ^:private json! ^:private parse-options ^:private usage
         ^:private bind-server ^:private port-conflict-message)

(defn start! [architecture project-path opts reload!]
  (let [state (atom (assoc (model/create-state architecture project-path opts) :can-reanalyze (boolean reload!)))
        {:keys [server requested-port]} (bind-server opts)
        executor (Executors/newFixedThreadPool 4)
        port (.getPort (.getAddress server))
        analyze-complexity! #(complexity/start! state (:root @state) opts)]
    (analyze-complexity!)
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try
                          (if (same-origin? exchange port)
                            (route! exchange state reload! analyze-complexity!)
                            (json! exchange 403 {:error "仅允许本机同源访问"}))
                          (catch Exception ex
                            (json! exchange (get (ex-data ex) :status 500) {:error (.getMessage ex)}))
                          (finally (.close ^HttpExchange exchange))))))
    (.setExecutor server executor)
    (.start server)
    {:port port :requested-port requested-port :url (str "http://127.0.0.1:" port)
     :stop (fn [] (.stop server 0) (.shutdownNow executor))}))

(defn -main [& args]
  (try
    (let [opts (parse-options args)]
      (if (:help opts) (println (usage))
        (let [_ (println (str "正在分析项目：" (.getCanonicalPath (io/file (:project-path opts)))))
              _ (flush)
              load! #(model/create-state (core/load-architecture (:project-path opts) opts) (:project-path opts) opts)
              architecture (if (:in-edn opts) (core/load-architecture-edn (:in-edn opts))
                               (core/load-architecture (:project-path opts) opts))
              _ (println "分析完成，正在启动网页服务……")
              _ (flush)
              app (start! architecture (:project-path opts) opts (when-not (:in-edn opts) load!))]
          (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:stop app)))
          (when (not= (:requested-port app) (:port app))
            (when (pos? (:requested-port app))
              (println (str "默认端口 " (:requested-port app) " 已被占用，已改用空闲端口 " (:port app) "。"))))
          (println (str "架构 Web UI: " (:url app)))
          (println (str "服务已就绪；进程号（PID）：" (.pid (java.lang.ProcessHandle/current))))
          (println "网页服务会持续占用前台。按 Ctrl+C 停止；自动化调用请使用持久或后台进程。")
          (flush)
          (when-not (:no-browser opts)
            (try
              (if (and (Desktop/isDesktopSupported) (.isSupported (Desktop/getDesktop) java.awt.Desktop$Action/BROWSE))
                (.browse (Desktop/getDesktop) (URI. (:url app)))
                (println "当前环境不支持自动打开浏览器，请打开上方地址。"))
              (catch Exception _ (println "请在浏览器中打开上方地址。")))))))
    (catch Exception ex
      (binding [*out* *err*] (println (.getMessage ex)))
      (System/exit 1))))

;; ===== 私有方法 =====

(defn- bind-server [opts]
  (let [port (int (get opts :port 7331))
        bind! #(HttpServer/create (InetSocketAddress. "127.0.0.1" (int %)) 0)]
    (try
      {:server (bind! port) :requested-port port}
      (catch BindException ex
        (if (contains? opts :port)
          (throw (ex-info (port-conflict-message port) {:port port} ex))
          {:server (bind! 0) :requested-port port})))))

(defn- port-conflict-message [port]
  (str "端口 " port " 已被占用，无法启动网页服务。\n"
       "可改用 --port 0 自动分配空闲端口，或指定其他端口。\n"
       "Windows 查询占用进程：Get-NetTCPConnection -State Listen -LocalPort " port
       " | Select-Object LocalAddress,LocalPort,OwningProcess\n"
       "确认原服务是否仍在使用后，可在其终端按 Ctrl+C 停止。"))

(defn- usage []
  (str "用法: clj -M:web [--project-path <目录>] [--language auto|clojure|python|kotlin|java]\n"
       "  --source-path <目录>        源码目录，可重复指定\n"
       "  --architecture-doc <文件>  可选：项目内已有的 Markdown 说明\n"
       "  --env-file <文件>          配置文件，默认读取工具安装目录的 .env\n"
       "  --edge-scope <focus|all>    默认连线范围：当前模块或全部模块\n"
       "  --crap <auto|off>          发现 crap 命令时在后台分析函数复杂度（默认 auto）\n"
       "  --crap-command <命令>      crap 不在 PATH 上时指定其路径\n"
       "  --in-edn <文件>            加载导出的架构（源码按 --project-path 定位）\n"
       "  --port <端口>              默认 7331，被占用时自动换端口；0 自动选择\n"
       "  --no-browser               不自动打开浏览器\n"
       "  --help                     显示帮助\n"
       "服务就绪后输出地址和进程号（PID），持续占用前台；Ctrl+C 停止。\n"
       "明确指定的端口被占用时提示错误；自动化调用使用持久或后台进程与 --no-browser。\n"))

(defn- parse-options [args]
  (loop [args (seq args) opts {:project-path "."}]
    (if-not args
      (if (:help opts) opts
        ;; 与桌面版共用全局配置，命令行参数覆盖配置；项目路径已有默认值，不受配置影响。
        (let [opts (merge (config/defaults (:env-file opts) (into {} (System/getenv))) opts)]
          (assoc opts :edge-scope (config/parse-edge-scope (or (:edge-scope opts) "focus"))
                      :crap (complexity/parse-mode (or (:crap opts) "auto")))))
      (let [[arg value] args]
        (cond
          (= arg "--help") (recur (next args) (assoc opts :help true))
          (= arg "--no-browser") (recur (next args) (assoc opts :no-browser true))
          (contains? #{"--project-path" "--language" "--source-path" "--architecture-doc" "--in-edn" "--port" "--env-file" "--edge-scope"
                      "--crap" "--crap-command"} arg)
          (do
            (when (or (nil? value) (str/starts-with? value "--"))
              (throw (ex-info (str "缺少参数值: " arg) {})))
            (recur (nnext args)
                   (case arg
                     "--source-path" (update opts :source-paths (fnil conj []) value)
                     "--port" (let [port (Integer/parseInt value)]
                                (when-not (<= 0 port 65535) (throw (ex-info "端口范围为 0–65535" {})))
                                (assoc opts :port port))
                     (assoc opts (keyword (subs arg 2)) value))))
          :else (throw (ex-info (str "未知参数: " arg) {})))))))

(defn- query-params [^HttpExchange exchange]
  (into {} (for [pair (str/split (or (.getRawQuery (.getRequestURI exchange)) "") #"&")
                 :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(defn- send! [^HttpExchange exchange status type body]
  (let [bytes (if (string? body) (.getBytes ^String body "UTF-8") body)
        headers (.getResponseHeaders exchange)]
    (.set headers "Content-Type" type)
    (.set headers "Cache-Control" "no-store")
    (.set headers "X-Content-Type-Options" "nosniff")
    (.set headers "Content-Security-Policy"
          (if (= type "image/svg+xml")
            "sandbox; default-src 'none'; style-src 'unsafe-inline'"
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"))
    (.sendResponseHeaders exchange status (long (alength ^bytes bytes)))
    (with-open [output (.getResponseBody exchange)] (.write output ^bytes bytes))))

(defn- json! [exchange status value]
  (send! exchange status "application/json; charset=utf-8" (json/write-json value)))

(def ^:private assets
  {"/" ["index.html" "text/html; charset=utf-8"]
   "/favicon.svg" ["favicon.svg" "image/svg+xml"]
   "/app.css" ["app.css" "text/css; charset=utf-8"]
   "/app.js" ["app.js" "text/javascript; charset=utf-8"]
   "/architecture_document.js" ["architecture_document.js" "text/javascript; charset=utf-8"]
   "/vendor/mermaid.min.js" ["vendor/mermaid.min.js" "text/javascript; charset=utf-8"]
   "/vendor/marked.umd.js" ["vendor/marked.umd.js" "text/javascript; charset=utf-8"]
   "/vendor/purify.min.js" ["vendor/purify.min.js" "text/javascript; charset=utf-8"]})

(defn- same-origin? [^HttpExchange exchange port]
  (let [headers (.getRequestHeaders exchange)
        host (.getFirst headers "Host")
        origin (.getFirst headers "Origin")
        allowed #{(str "127.0.0.1:" port) (str "localhost:" port)}]
    (and (contains? allowed host)
         (or (nil? origin) (contains? (set (map #(str "http://" %) allowed)) origin)))))

(defn- route! [exchange state reload! analyze-complexity!]
  (let [path (.getPath (.getRequestURI ^HttpExchange exchange))
        method (.getRequestMethod ^HttpExchange exchange)
        params (query-params exchange)]
    (cond
      (and (= method "GET") (contains? assets path))
      (let [[file type] (get assets path)]
        (send! exchange 200 type (slurp (io/resource (str "arch_view/web/assets/" file)) :encoding "UTF-8")))
      (and (= method "GET") (= path "/api/project")) (json! exchange 200 (model/project-data @state))
      (and (= method "GET") (= path "/api/complexity")) (json! exchange 200 (complexity/summary (:complexity @state)))
      (and (= method "GET") (= path "/api/view"))
      (json! exchange 200 (model/view-data @state (vec (remove str/blank? (str/split (get params "path" "") #"/")))))
      (and (= method "GET") (= path "/api/source")) (json! exchange 200 (model/source-data @state (get params "module")))
      (and (= method "GET") (= path "/api/locate")) (json! exchange 200 (model/locate-data @state (get params "module")))
      (and (= method "GET") (= path "/api/document")) (json! exchange 200 (model/document-data @state (get params "id")))
      (and (= method "POST") (= path "/api/subsystem-status"))
      (json! exchange 200 (model/update-subsystem-status! @state (get params "id") (get params "heading") (get params "status")))
      (and (= method "GET") (= path "/api/image"))
      (let [root (:root @state)
            file (documents/contained-file root (io/file (.toFile ^java.nio.file.Path root) (get params "path" "")))
            ext (str/lower-case (last (str/split (.getName file) #"\.")))
            type (get {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp" "svg" "image/svg+xml"} ext)]
        (if (and type (.isFile file))
          (send! exchange 200 type (java.nio.file.Files/readAllBytes (.toPath file)))
          (json! exchange 404 {:error "图片不存在或格式不支持"})))
      (and (= method "POST") (= path "/api/reanalyze"))
      (if reload! (do (locking state (reset! state (assoc (reload!) :can-reanalyze true)) (analyze-complexity!))
                      (json! exchange 200 (model/project-data @state)))
          (json! exchange 409 {:error "EDN 快照不支持重新分析"}))
      :else (json! exchange 404 {:error "接口不存在"}))))
