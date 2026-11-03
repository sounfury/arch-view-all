;; 职责：验证公共参数解析、JSON 文件与文本读写、id 规则和输出格式。
;; 核心入口：Speclj 命令公共工具规格。

(ns arch-view.cli-support-spec
  (:require [arch-view.cli-support :as support]
            [arch-view.notes.test-support :as fixture]
            [arch-view.config :as config]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "共享命令工具"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))

  (it "解析公共参数、专有参数、开关和位置参数，可重复源码目录"
    (should= {:options {:project-path "." :format "json" :source-paths ["src" "lib"]
                       :depth "2" :strict true} :positional ["deps" "app"]}
             (support/parse-args ["deps" "app" "--depth" "2" "--strict" "--source-path" "src"
                                  "--source-path" "lib" "--format" "json"]
                                 {:values #{"--depth"} :flags #{"--strict"}})))

  (it "默认 Markdown，拒绝未知参数、缺值和错误格式"
    (should= {:options {:project-path "." :format "md"} :positional []}
             (support/parse-args [] {}))
    (doseq [args [["--unknown"] ["--project-path"] ["--language" "--help"] ["--format" "xml"]]]
      (should-throw clojure.lang.ExceptionInfo (support/parse-args args {}))))

  (it "递归关键字化 JSON，保留 UTF-8 和嵌套数组，并自动创建父目录"
    (let [file (io/file (.toFile (:root @ws)) "nested/data.json")
          data {:标题 "解释\n正文" :anchors [{:startLine 2 :symbol "view"}] :empty [] :ok true :commit nil}]
      (should= file (support/write-json-file! file data))
      (should= data (support/read-json-file file))
      (should= data (support/read-json-text (slurp file :encoding "UTF-8")))
      (should-contain "\n  " (slurp file :encoding "UTF-8"))))

  (it "读取不存在的文件返回 404，错误 JSON 抛异常"
    (should= 404 (:status (fixture/error-data #(support/read-json-file (io/file (.toFile (:root @ws)) "gone.json")))))
    (should-throw Exception (support/read-json-text "{")))

  (it "id 限制长度、字符和开头，拒绝穿越"
    (doseq [id ["a" "0" "web-model-2" (apply str (repeat 64 "a"))]]
      (should (support/valid-id? id)))
    (doseq [id [nil 1 "" "A" "-a" "a_b" "../a" "a/b" "中文" (apply str (repeat 65 "a"))]]
      (should-not (support/valid-id? id))))

  (it "emit! 的 JSON 可解析，Markdown 调用渲染器"
    (should= {:status "fresh"}
             (support/read-json-text
              (with-out-str (support/emit! {:format "json"} {:status "fresh"} (constantly "不调用")))))
    (should= "状态：fresh\n"
             (with-out-str (support/emit! {} {:status "fresh"} #(str "状态：" (:status %))))))

  (it "加载工作区合并配置并把分析进度写到标准错误"
    (let [seen (atom nil)
          errors (java.io.StringWriter.)
          architecture (:architecture @ws)]
      (with-redefs [config/defaults (fn [& _] {:project-path "忽略" :out "忽略" :language "clojure"
                                               :source-paths ["default-src"]})
                    clojure.core/requiring-resolve
                    (fn [symbol]
                      (case symbol
                        arch-view.core/load-architecture
                        (fn [project opts] (reset! seen [project opts]) (println "分析进度") architecture)
                        arch-view.core/load-architecture-edn (fn [_] (throw (Exception. "不应加载 EDN")))))]
        (let [result (atom nil)
              stdout (binding [*err* errors]
                       (with-out-str (reset! result (support/load-workspace {:project-path (str (:root @ws))
                                                                            :source-paths ["src"]}))))]
          (should= "" stdout)
          (should= "分析进度\n" (str errors))
          (should= ["src"] (:source-paths (second @seen)))
          (should= "clojure" (get-in @result [:opts :language]))
          (should-not (contains? (:opts @result) :out))
          (should= architecture (:architecture @result))))))

  (it "in-edn 使用导出数据并沿用配置中的源码目录"
    (with-redefs [config/defaults (fn [& _] {:source-paths ["default-src"]})
                  clojure.core/requiring-resolve
                  (fn [symbol]
                    (case symbol
                      arch-view.core/load-architecture (fn [& _] (throw (Exception. "不应重新分析")))
                      arch-view.core/load-architecture-edn (fn [file] {:loaded file})))]
      (let [result (support/load-workspace {:project-path (str (:root @ws)) :in-edn "saved.edn"})]
        (should= {:loaded "saved.edn"} (:architecture result))
        (should= ["default-src"] (get-in result [:opts :source-paths])))))

  (it "公共帮助覆盖项目、语言、源码和 JSON 输出选项"
    (doseq [option ["--project-path" "--language" "--source-path" "--format" "--in-edn" "--env-file"]]
      (should-contain option (support/common-help)))))
