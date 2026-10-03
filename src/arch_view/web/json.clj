;; 工具函数：把架构数据转换为浏览器能读取的通用数据格式（JSON），正确处理引号和换行。

(ns arch-view.web.json
  "Small write-only JSON encoder for the HTTP responses; no reader or evaluation."
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
