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

(defn- current [{:keys [^String text pos]}]
  (when (< @pos (count text)) (.charAt text @pos)))

(defn- advance! [parser n]
  (vswap! (:pos parser) + n))

(defn- fail! [{:keys [pos]}]
  (throw (ex-info (str "JSON 格式错误，位置 " @pos) {})))

(defn- skip-space! [parser]
  (while (some-> (current parser) Character/isWhitespace) (advance! parser 1)))

(defn- expect! [parser c]
  (skip-space! parser)
  (when-not (= c (current parser)) (fail! parser))
  (advance! parser 1))

(defn- read-escape! [{:keys [text pos] :as parser}]
  (let [e (or (current parser) (fail! parser))]
    (advance! parser 1)
    (if (= e \u)
      (do (when (> (+ @pos 4) (count text)) (fail! parser))
          (let [value (char (Integer/parseInt (subs text @pos (+ @pos 4)) 16))]
            (advance! parser 4)
            value))
      (case e \" \" \\ \\ \/ \/ \b \backspace \f \formfeed
            \n \newline \r \return \t \tab (fail! parser)))))

(defn- read-string* [parser]
  (expect! parser \")
  (let [out (StringBuilder.)]
    (loop []
      (let [c (or (current parser) (fail! parser))]
        (advance! parser 1)
        (cond
          (= c \") (str out)
          (= c \\) (do (.append out ^char (read-escape! parser)) (recur))
          :else (do (.append out ^char c) (recur)))))))

(defn- read-number* [{:keys [text pos] :as parser}]
  (let [start @pos]
    (while (some-> (current parser) (#(str/includes? "+-.eE0123456789" (str %))))
      (advance! parser 1))
    (let [token (subs text start @pos)]
      (try (if (re-find #"[.eE]" token) (Double/parseDouble token) (Long/parseLong token))
           (catch NumberFormatException _ (fail! parser))))))

(defn- read-word* [{:keys [^String text pos] :as parser} word value]
  (when-not (.startsWith text ^String word (int @pos)) (fail! parser))
  (advance! parser (count word))
  value)

(defn- read-items* [parser close read-item]
  (advance! parser 1)
  (skip-space! parser)
  (if (= close (current parser))
    (do (advance! parser 1) [])
    (loop [items [(read-item)]]
      (skip-space! parser)
      (case (current parser)
        \, (do (advance! parser 1) (recur (conj items (read-item))))
        (do (expect! parser close) items)))))

(declare ^:private read-value*)

(defn- read-entry* [parser]
  (let [k (read-string* parser)]
    (expect! parser \:)
    [k (read-value* parser)]))

(defn- read-value* [parser]
  (skip-space! parser)
  (case (current parser)
    \{ (into {} (read-items* parser \} #(read-entry* parser)))
    \[ (read-items* parser \] #(read-value* parser))
    \" (read-string* parser)
    \t (read-word* parser "true" true)
    \f (read-word* parser "false" false)
    \n (read-word* parser "null" nil)
    (if (current parser) (read-number* parser) (fail! parser))))

(defn read-json
  "只解析外部工具输出的 JSON 数据，不求值；对象键保留为字符串。"
  [^String text]
  (let [parser {:text text :pos (volatile! 0)}
        value (read-value* parser)]
    (skip-space! parser)
    (when (current parser) (fail! parser))
    value))
