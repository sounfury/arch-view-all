;; 职责：安全读取主资源目录下的 Spring XML，按规则的简单路径取出声明单元和字段，并收集 XML 里的 bean 定义。
;; 核心入口：资源目录（resource-roots）；按通配找文件（matching-files）；解析（parse-file）；取单元（units）；取字段（field-value）。

(ns arch-view.entries.xml-source
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File StringReader]
           [java.nio.file FileSystems Path]
           [javax.xml XMLConstants]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax Attributes InputSource Locator]
           [org.xml.sax.helpers DefaultHandler]))

(declare ^:private chains)

(def max-bytes (* 1024 1024))

(defn resource-roots
  "各源码目录对应的 src/main/resources，以及项目根下的 src/main/resources；只返回存在的目录。"
  [^File root source-paths]
  (->> source-paths
       (keep #(when-let [[_ base] (re-matches #"(.*?)/?src/main/(java|kotlin)" (str/replace (str %) "\\" "/"))]
                (str base (when (seq base) "/") "src/main/resources")))
       (cons "src/main/resources")
       distinct
       (map #(let [file (io/file %)] (.getCanonicalFile (if (.isAbsolute file) file (io/file root %)))))
       (filter #(.isDirectory ^File %))
       distinct vec))

(defn xml-files
  "资源目录下不超过 1 MB 的 XML 文件：[{:file :relative 相对资源目录}]。"
  [roots]
  (for [^File root roots
        ^File file (file-seq root)
        :when (and (.isFile file) (str/ends-with? (.getName file) ".xml") (<= (.length file) max-bytes))]
    {:file file :relative (str/replace (str (.relativize (.toPath root) (.toPath file))) "\\" "/")}))

(defn matching-files
  "按规则 files 的通配匹配相对资源目录的路径；以 **/ 开头时也匹配顶层文件。"
  [files patterns]
  (let [matchers (for [pattern patterns
                       variant (cond-> [pattern] (str/starts-with? pattern "**/") (conj (subs pattern 3)))]
                   (.getPathMatcher (FileSystems/getDefault) (str "glob:" variant)))]
    (filter (fn [{:keys [relative]}]
              (let [path (Path/of relative (make-array String 0))]
                (some #(.matches ^java.nio.file.PathMatcher % path) matchers)))
            files)))

(defn- attrs-map [^Attributes attrs]
  (into {} (for [i (range (.getLength attrs))] [(.getQName attrs i) (.getValue attrs i)])))

(defn- parser-factory []
  (doto (SAXParserFactory/newInstance)
    (.setNamespaceAware false)
    (.setValidating false)
    (.setXIncludeAware false)
    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
    (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)))

(defn parse-file
  "解析为 {:tag :attrs :children :start :end} 树；不加载外部 DTD 与实体，开启安全处理限制实体展开。"
  [^File file]
  (let [stack (atom [{:children []}])
        locator (atom nil)
        line #(some-> ^Locator @locator .getLineNumber)
        handler (proxy [DefaultHandler] []
                  (setDocumentLocator [value] (reset! locator value))
                  (resolveEntity [_ _] (InputSource. (StringReader. "")))
                  (startElement [_ _ qname attrs]
                    (swap! stack conj {:tag qname :attrs (attrs-map attrs) :children [] :start (line)}))
                  (endElement [_ _ _]
                    (swap! stack (fn [items]
                                   (let [node (assoc (peek items) :end (line))
                                         items (pop items)]
                                     (conj (pop items) (update (peek items) :children conj node)))))))]
    (.parse (.newSAXParser (parser-factory)) file ^DefaultHandler handler)
    (first (:children (first @stack)))))

(defn units
  "each 的第一段在文档任意位置匹配，其后逐段为子元素；返回从根到单元的元素链，并记录第一段元素作为声明位置。"
  [root each]
  (let [[head & more] (str/split each #"/")
        starts (filter #(= head (:tag (peek %))) (chains [root]))]
    (map (fn [chain] {:chain chain :declaration (nth chain (- (count chain) 1 (count more)))})
         (reduce (fn [found segment]
                   (for [chain found child (:children (peek chain)) :when (= segment (:tag child))]
                     (conj chain child)))
                 starts more))))

(defn field-value
  "相对单元的路径：.. 为父元素，名称为第一个同名子元素，@属性 取值；取不到时返回 nil。"
  [chain path]
  (loop [chain chain [segment & more] (str/split path #"/")]
    (cond
      (nil? segment) nil
      (str/starts-with? segment "@") (when (empty? more) (get-in (peek chain) [:attrs (subs segment 1)]))
      (= ".." segment) (when (> (count chain) 1) (recur (pop chain) more))
      :else (when-let [child (first (filter #(= segment (:tag %)) (:children (peek chain))))]
              (recur (conj chain child) more)))))

(defn bean-classes
  "XML 中 <bean id/name class> 定义的 bean 名 → 类全名。"
  [roots]
  (reduce (fn [acc {:keys [tag attrs]}]
            (if (and (re-matches #"(.+:)?bean" tag) (get attrs "class"))
              (into acc (for [name (cons (get attrs "id") (str/split (str (get attrs "name")) #"[,;\s]+"))
                              :when (seq name)]
                          [name (get attrs "class")]))
              acc))
          {}
          (for [{:keys [file]} (xml-files roots)
                :let [root (try (parse-file file) (catch Exception _ nil))]
                :when root
                chain (chains [root])]
            (peek chain))))

(defn valid-path?
  "XML 路径只允许元素名、.. 和最后一段 @属性。"
  [path]
  (let [segments (str/split (str path) #"/")]
    (and (seq segments)
         (every? #(re-matches #"\.\.|[A-Za-z_][\w.:-]*" %) (butlast segments))
         (re-matches #"\.\.|@?[A-Za-z_][\w.:-]*" (last segments)))))

;; ===== 私有方法 =====

(defn- chains [chain]
  (cons chain (mapcat #(chains (conj chain %)) (:children (peek chain)))))
