;; 工具函数：读取默认配置文件和环境变量，并检查开关、缩放等配置值。

(ns arch-view.config
  (:refer-clojure :exclude [parse-boolean])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse-boolean [value]
  (case (str/lower-case (str value))
    ("true" "1" "yes") true
    ("false" "0" "no") false
    (throw (ex-info (str "Expected true or false, got: " value) {}))))

(defn parse-scale [value]
  (let [scale (try (Double/parseDouble (str value))
                   (catch NumberFormatException _ Double/NaN))]
    (when-not (and (Double/isFinite scale) (pos? scale))
      (throw (ex-info "UI scale must be a positive finite number." {:value value})))
    (str value)))

(defn parse-edge-scope [value]
  (let [scope (str/lower-case (str/trim (str value)))]
    (when-not (#{"focus" "all"} scope)
      (throw (ex-info "连线范围只能是 focus（当前模块）或 all（全部模块）。" {:value value})))
    scope))

(defn read-env [path required?]
  (let [file (io/file path)]
    (when (and required? (not (.isFile file)))
      (throw (ex-info (str "Env file does not exist: " path) {})))
    (if-not (.isFile file)
      {}
      (reduce (fn [values line]
                (let [line (str/trim line)]
                  (if (or (str/blank? line) (str/starts-with? line "#"))
                    values
                    (if-let [[_ key raw] (re-matches #"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)" line)]
                      (let [value (str/trim raw)
                            quoted? (and (>= (count value) 2)
                                         (#{\" \'} (first value))
                                         (= (first value) (last value)))]
                        (assoc values key (if quoted? (subs value 1 (dec (count value)))
                                             (str/trim (str/replace value #"\s+#.*$" "")))))
                      (throw (ex-info (str "Invalid env assignment in " path) {}))))))
              {} (str/split-lines (slurp file))))))

(defn default-env-file
  "全局配置放在工具安装目录，不读取被分析项目自己的 .env。"
  []
  (io/file (System/getProperty "arch-view.home" (System/getProperty "user.dir")) ".env"))

(defn defaults [env-file environment]
  (let [values (merge (read-env (or env-file (default-env-file)) (some? env-file)) environment)
        specs {"ARCH_VIEW_PROJECT_PATH" [:project-path identity]
               "ARCH_VIEW_LANGUAGE" [:language identity]
               "ARCH_VIEW_SOURCE_PATHS" [:source-paths #(vec (remove str/blank? (map str/trim (str/split % #";"))))]
               "ARCH_VIEW_NO_GUI" [:no-gui parse-boolean]
               "ARCH_VIEW_INCLUDE_TESTS" [:include-tests parse-boolean]
               "ARCH_VIEW_UI_SCALE" [:ui-scale parse-scale]
               "ARCH_VIEW_ZOOM" [:zoom #(Double/parseDouble (parse-scale %))]
               "ARCH_VIEW_EDGE_SCOPE" [:edge-scope parse-edge-scope]
               "ARCH_VIEW_PYTHON" [:python identity]
               "ARCH_VIEW_CRAP" [:crap identity]
               "ARCH_VIEW_CRAP_COMMAND" [:crap-command identity]
               "ARCH_VIEW_OUT" [:out identity]}]
    (reduce-kv (fn [opts key [option parse]]
                 (if-let [value (get values key)]
                   (if (str/blank? value) opts (assoc opts option (parse value)))
                   opts)) {} specs)))
