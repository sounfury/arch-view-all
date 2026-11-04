;; 职责：把 Javadoc/KDoc 注释正文转成 Markdown：段落、列表、代码、链接与强调标签，去掉 @author 等块标签。
;; 核心入口：转换注释正文（markdown）；提取入口说明首句（summary）。

(ns arch-view.web.javadoc
  (:require [clojure.string :as str]))

(declare ^:private description-lines ^:private stash-pre ^:private stash-inline
         ^:private html->markdown ^:private escape-markup ^:private paragraphs ^:private restore ^:private decode-entities)

;; 作者用 HTML 分段时，源码里的换行只是折行，按 Javadoc 的显示效果合并；否则保留作者的换行。
(def ^:private html-markup #"(?i)<(?:p|br|ul|ol|li|pre|h[1-6])\b[^>]*>|</p>")

(defn markdown
  "lines 为已去掉注释星号的正文行；返回 Markdown，没有正文时返回 nil。"
  [lines]
  (let [store (atom [])
        text (str/join "\n" (description-lines lines))
        html? (boolean (re-find html-markup text))
        text (-> text
                 (stash-pre store)
                 (stash-inline store)
                 html->markdown
                 escape-markup
                 (paragraphs html?))]
    (not-empty (str/trim (restore store text)))))

(defn summary
  "从 Javadoc 正文提取纯文本首句，复用 Markdown 转换处理内联标签与 HTML；最多 120 字。"
  [text]
  (when text
    (let [lines (map #(str/replace % #"^\s*\*+ ?" "") (str/split-lines text))
          plain (-> (or (markdown lines) "")
                    (str/replace #"\[([^\]]+)\]\([^)]*\)" "$1")
                    (str/replace #"\\([<>])" "$1")
                    (str/replace #"[`*_]" "")
                    decode-entities
                    (str/replace #"\s+" " ") str/trim)
          sentence (or (second (re-find #"^(.*?[。！？])" plain))
                       (second (re-find #"^(.*?\.)(?:\s|$)" plain)) plain)]
      (not-empty (if (> (count sentence) 120) (str (subs sentence 0 119) "…") sentence)))))

;; ===== 私有方法 =====

(defn- pre-state [in-pre? line]
  (cond (re-find #"(?i)</pre>" line) false
        (re-find #"(?i)<pre\b" line) true
        :else in-pre?))

(defn- description-lines
  "块标签（@author、@version 等）之后都是标签区；<pre> 示例代码里的注解不算。"
  [lines]
  (loop [remaining (seq lines) in-pre? false result []]
    (let [line (first remaining)]
      (if (or (nil? remaining) (and (not in-pre?) (re-find #"^\s*@[A-Za-z]" line)))
        result
        (recur (next remaining) (pre-state in-pre? line) (conj result line))))))

(defn- stash!
  "代码放进占位符，后续 HTML 转换和折行合并都不会改动它。"
  [store markdown]
  (let [index (count @store)]
    (swap! store conj markdown)
    (str "\u0001" index "\u0002")))

(defn- restore [store text]
  (str/replace text #"\u0001(\d+)\u0002" #(get @store (parse-long (second %)))))

(defn- decode-entities [text]
  (-> text
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&#64;" "@") (str/replace "&nbsp;" " ") (str/replace "&amp;" "&")))

(defn- code-span [text]
  (let [text (str/trim (str/replace text #"\s+" " "))]
    (if (str/includes? text "`") (str "`` " text " ``") (str "`" text "`"))))

(defn- dedent [text]
  (let [lines (str/split-lines (str/trimr (str/replace text #"\A(?:[ \t]*\n)+" "")))
        indents (keep #(when-not (str/blank? %) (count (re-find #"^[ \t]*" %))) lines)
        indent (if (seq indents) (apply min indents) 0)]
    (str/join "\n" (map #(subs % (min indent (count %))) lines))))

(defn- pre-body [body]
  (dedent (if-let [[_ inner] (re-matches #"(?s)\s*\{@code\s?(.*)\}\s*" body)]
            inner
            (decode-entities (str/replace body #"(?i)</?(?:code|tt)>" "")))))

(defn- stash-pre [text store]
  (str/replace text #"(?is)<pre[^>]*>(.*?)</pre>"
               (fn [[_ body]] (str "\n\n" (stash! store (str "```\n" (pre-body body) "\n```")) "\n\n"))))

(defn- link-text [[_ target label]]
  (if (str/blank? label)
    (code-span (str/replace target #"^#" ""))
    (str/trim label)))

(defn- stash-inline [text store]
  (let [code #(stash! store (code-span %))]
    (-> text
        (str/replace #"\{@code\s((?:[^{}]|\{[^{}]*\})*)\}" (fn [[_ body]] (code body)))
        (str/replace #"\{@value\s+([^}]*)\}" (fn [[_ body]] (code body)))
        (str/replace #"(?is)<(code|tt)>(.*?)</\1>" (fn [[_ _ body]] (code (decode-entities (str/replace body #"<[^>]+>" "")))))
        (str/replace #"\{@link(?:plain)?\s+([^\s}]+)(?:\s+([^}]*))?\}"
                     (fn [match] (let [text (link-text match)]
                                   (if (str/starts-with? text "`") (stash! store text) text))))
        (str/replace #"\{@inheritDoc\}" "")
        (str/replace #"\{@[A-Za-z]+\s*([^}]*)\}" (fn [[_ body]] body)))))

(def ^:private html-rules
  [[#"(?i)<br\s*/?>" "\n"]
   [#"(?i)</?p(?:\s[^>]*)?/?>" "\n\n"]
   [#"(?i)</?(?:ul|ol|dl|div|blockquote|table|tr)(?:\s[^>]*)?>" "\n\n"]
   ;; 列表项前的缩进和换行一并吃掉，否则会被当成空行，把列表拆成多段。
   [#"(?i)[ \t]*\n?[ \t]*<li(?:\s[^>]*)?>" "\n- "]
   [#"(?i)</li>[ \t]*" ""]
   [#"(?i)<(?:dt|dd)(?:\s[^>]*)?>" "\n"]
   [#"(?is)<h[1-6][^>]*>(.*?)</h[1-6]>" "\n\n**$1**\n\n"]
   [#"(?is)<(b|strong)>(.*?)</\1>" "**$2**"]
   [#"(?is)<(i|em)>(.*?)</\1>" "*$2*"]
   [#"(?is)<a\s[^>]*href\s*=\s*\"(https?://[^\"]+)\"[^>]*>(.*?)</a>" "[$2]($1)"]
   [#"(?i)</?(?:a|span|font|u|small|big|sup|sub|td|th|tbody|thead|li|dt|dd)(?:\s[^>]*)?>" ""]])

(defn- html->markdown [text]
  (reduce (fn [text [pattern replacement]] (str/replace text pattern replacement)) text html-rules))

(defn- escape-markup
  "剩下的尖括号多是泛型写法（List<String>），按原文显示，不当作 HTML 标签。"
  [text]
  (-> text
      (str/replace "<" "\\<")
      (str/replace #"(?m)^(\s*)>" (fn [[_ indent]] (str indent "\\>")))))

(def ^:private cjk #"[\u2E80-\u9FFF\u3000-\u303F\uFF00-\uFFEF]")
(def ^:private cjk-punctuation #"[\u3000-\u303F\uFF00-\uFFEF]")
;; 列表符号、序号和圈码开头的行保留换行，其余折行按 Javadoc 合并成一段。
(def ^:private line-start #"^(?:[-*+] |\d+[.)、]|[\u2460-\u2473\u2776-\u277F•·])")

(defn- joiner
  "折行合并时，中文之间或紧邻全角标点不加空格，其余按 HTML 折行补一个空格。"
  [left right]
  (let [l (subs left (dec (count left))) r (subs right 0 1)]
    (if (or (and (re-find cjk l) (re-find cjk r)) (re-find cjk-punctuation l) (re-find cjk-punctuation r)) "" " ")))

(defn- paragraph [html? block]
  (reduce (fn [result line]
            (cond (str/blank? result) line
                  (or (not html?) (re-find line-start line)) (str result "\n" line)
                  :else (str result (joiner result line) line)))
          ""
          (remove str/blank? (map str/trim (str/split-lines block)))))

(defn- paragraphs [text html?]
  (->> (str/split text #"\n\s*\n")
       (map #(paragraph html? %))
       (remove str/blank?)
       (str/join "\n\n")))
