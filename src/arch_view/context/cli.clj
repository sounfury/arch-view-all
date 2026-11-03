;; 职责：提供中文 context 命令帮助，解析上下文包选项并输出 Markdown 或 JSON。
;; 核心入口：执行 context 子命令（run!）。

(ns arch-view.context.cli
  (:refer-clojure :exclude [run!])
  (:require [arch-view.cli-support :as support]
            [arch-view.context.pack :as pack]
            [arch-view.web.complexity :as complexity]))

(declare ^:private help-text ^:private build-options)

(defn run! [args]
  (let [{:keys [options positional]} (support/parse-args args {:values #{"--max-source-lines" "--question" "--crap"}})]
    (if (:help options) (println (help-text))
      (do
        (when-not (= 1 (count positional))
          (throw (ex-info "用法：arch-view context <模块名|文件路径|包名>；用 --help 查看帮助。" {:status 400})))
        (let [opts (build-options options)
              ws (support/load-workspace options)
              opts (if (= "auto" (:crap opts)) (assoc opts :complexity (pack/analyze-complexity ws)) opts)
              result (pack/build ws (first positional) opts)]
          (support/emit! options result :markdown))))))

;; ===== 私有方法 =====

(defn- help-text []
  (str "用法：arch-view context <模块名|文件路径|包名> [选项]\n\n"
       "生成可交给 AI 解释的架构上下文包，包含职责、依赖、已有解释与源码。\n\n"
       "  --max-source-lines <N>  源码最多收录 N 行，默认 300；包只列成员说明\n"
       "  --question <文本>       追加问题和引用代码的作答要求\n"
       "  --crap <auto|off>       自动发现并同步分析复杂度；默认 off\n"
       (support/common-help)))

(defn- build-options [options]
  (let [raw (:max-source-lines options)
        limit (when raw (parse-long raw))]
    (when (and raw (not (and limit (pos? limit))))
      (throw (ex-info "--max-source-lines 必须是正整数" {:status 400})))
    {:max-source-lines limit :question (:question options)
     :crap (complexity/parse-mode (get options :crap "off"))}))
