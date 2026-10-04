;; 工具函数：把架构数据转换为浏览器能读取的通用数据格式（JSON），并读取外部工具输出的 JSON 结果。

(ns arch-view.web.json
  "Small JSON encoder for HTTP responses, plus a data-only reader for external tool output; no evaluation."
  (:require [clojure.string :as str]))

(defn- quoted [s]
  (str "\""
       (apply str
              (map (fn [c]
                     (case c
                       \" "\\\"" \\ "\\\\" \newline "\\n" \return "\\r" \tab "\\t"
                       (if (< (int c) 32) (format "\\u%04x" (int c)) (str c))))
                   s))
       "\""))

(declare write-json)

(defn write-json [value]
  (cond
    (nil? value) "null"
    (string? value) (quoted value)
    (keyword? value) (quoted (name value))
    (boolean? value) (str value)
    (number? value) (str value)
    (map? value) (str "{" (str/join "," (map (fn [[k v]]
                                               (str (quoted (if (keyword? k) (name k) (str k)))
                                                    ":" (write-json v))) value)) "}")
    (coll? value) (str "[" (str/join "," (map write-json value)) "]")
    :else (throw (ex-info "Unsupported JSON value" {:type (type value)}))))

(defn read-json
  "只解析外部工具输出的 JSON 数据，不求值；对象键保留为字符串。"
  [^String text]
  (let [pos (volatile! 0)
        fail #(throw (ex-info (str "JSON 格式错误，位置 " @pos) {}))]
    (letfn [(current [] (when (< @pos (count text)) (.charAt text @pos)))
            (skip-space [] (while (some-> (current) Character/isWhitespace) (vswap! pos inc)))
            (expect [c] (skip-space) (when-not (= c (current)) (fail)) (vswap! pos inc))
            (read-string* []
              (expect \")
              (let [out (StringBuilder.)]
                (loop []
                  (let [c (or (current) (fail))]
                    (vswap! pos inc)
                    (cond
                      (= c \") (str out)
                      (= c \\) (let [e (or (current) (fail))]
                                 (vswap! pos inc)
                                 (if (= e \u)
                                   (do (when (> (+ @pos 4) (count text)) (fail))
                                       (.append out (char (Integer/parseInt (subs text @pos (+ @pos 4)) 16)))
                                       (vswap! pos + 4))
                                   (.append out (case e \" \" \\ \\ \/ \/ \b \backspace \f \formfeed
                                                  \n \newline \r \return \t \tab (fail))))
                                 (recur))
                      :else (do (.append out c) (recur)))))))
            (read-number []
              (let [start @pos]
                (while (some-> (current) (#(str/includes? "+-.eE0123456789" (str %)))) (vswap! pos inc))
                (let [token (subs text start @pos)]
                  (try (if (re-find #"[.eE]" token) (Double/parseDouble token) (Long/parseLong token))
                       (catch NumberFormatException _ (fail))))))
            (read-word [word value]
              (when-not (.startsWith text ^String word (int @pos)) (fail))
              (vswap! pos + (count word)) value)
            (read-items [close read-item]
              (vswap! pos inc) (skip-space)
              (if (= close (current)) (do (vswap! pos inc) [])
                  (loop [items [(read-item)]]
                    (skip-space)
                    (case (current)
                      \, (do (vswap! pos inc) (recur (conj items (read-item))))
                      (do (expect close) items)))))
            (read-value []
              (skip-space)
              (case (current)
                \{ (into {} (read-items \} #(let [k (read-string*)] (expect \:) [k (read-value)])))
                \[ (read-items \] read-value)
                \" (read-string*)
                \t (read-word "true" true)
                \f (read-word "false" false)
                \n (read-word "null" nil)
                (if (current) (read-number) (fail))))]
      (let [value (read-value)]
        (skip-space)
        (when (current) (fail))
        value))))
