;; 职责：按网页的命名空间、包文档基址和子系统标题规则匹配架构职责。
;; 核心入口：查找目标及祖先包的职责小节（matching-sections）；读取子系统小节与数据流源码（subsystem-roles、flow-sources）。

(ns arch-view.context.architecture
  (:require [arch-view.web.documents :as documents]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URLDecoder]))

(declare ^:private document-sections ^:private visible-parts ^:private document-base
         ^:private prefix? ^:private architecture-document? ^:private matching-document
         ^:private headings ^:private next-fence ^:private section ^:private plain
         ^:private role-target ^:private field ^:private role-sections ^:private decode-module
         ^:private title-parts ^:private path-parts)

(defn matching-sections
  "复用 discover；项目文档从根视图定位，包文档从目录对应的浏览路径定位。"
  [ws resolved]
  (let [docs (documents/discover (:root ws) (:architecture ws) (get-in ws [:opts :architecture-doc]))
        parts (visible-parts ws (:target resolved))]
    (vec (mapcat #(matching-document ws resolved parts %) (filter architecture-document? docs)))))

(defn subsystem-roles
  "子系统小节：{:id 标题标识 :label 显示名 :scopes [代码路径段…]}，与网页 parseArchitectureDocument 同一套规则；
  scopes 为探索链接（无链接时取标识）加覆盖范围。"
  [content]
  (vec (for [{:keys [id label module scopes]} (role-sections (document-sections content))]
         {:id (or id (str/join "/" module)) :label label
          :scopes (vec (distinct (remove empty? (cons module scopes))))})))

(defn flow-sources
  "「数据流」二级小节（没有时全文）中的 Mermaid flowchart 源码。"
  [content]
  (let [sections (document-sections content)
        flow (some #(when (and (= 2 (:depth %)) (= "数据流" (:title %))) %) sections)]
    (vec (for [[_ lang body] (re-seq #"(?ms)^\s{0,3}```(\w*)[^\n]*\n(.*?)^\s{0,3}```" (if flow (:body flow) content))
               :when (and (= "mermaid" (str/lower-case lang))
                          (re-find #"^\s*(?:%%[^\n]*\n\s*)*(?:flowchart|graph)\s" body))]
           body))))

;; ===== 私有方法 =====

(defn- plain [text]
  (-> text
      (str/replace #"\[([^\]]*)\]\([^)]*\)" "$1")
      (str/replace #"<[^>]+>" "")
      (str/replace #"[`*]" "")
      (str/replace #"\s+" " ") str/trim))

(defn- prefix? [prefix parts]
  (and (seq prefix) (<= (count prefix) (count parts))
       (= (vec prefix) (subvec (vec parts) 0 (count prefix)))))

(defn- visible-parts [ws module]
  (vec (drop (get-in ws [:architecture :guidance :namespace-root-depth] 1)
             (str/split module #"\."))))

(defn- architecture-document? [{:keys [path]}]
  (= "architecture.md" (str/lower-case (.getName (io/file path)))))

(defn- document-base [ws resolved {:keys [scope directory]}]
  (if (= scope :project) []
    ;; 对应 app.js navigate：从目标所在浏览路径向祖先查找目录后缀。
    (let [parts (visible-parts ws (:target resolved))
          path (if (= :package (:kind resolved)) parts (vec (butlast parts)))]
      (->> (range (count path) 0 -1)
           (map #(subvec path 0 %))
           (filter #(str/ends-with? directory (str/replace (str/join "/" %) "-" "_")))
           first))))

(defn- matching-document [ws resolved parts doc]
  (let [base (document-base ws resolved doc)
        source-paths (keep #(workspace/source-file ws %) (:modules resolved))
        local? (some #(str/starts-with? % (str (:directory doc) "/")) source-paths)]
    (when (or (= :project (:scope doc)) (and base local?))
      (for [entry (role-sections (document-sections (:content (documents/read-document (:root ws) (:id doc)))))
            :let [key (into (vec base) (:module entry))]
            :when (prefix? key parts)]
        (assoc (select-keys entry [:title :status :body]) :path (:path doc)
               :module (str/join "/" key))))))

(defn- next-fence [fence marker]
  (cond
    (nil? marker) fence
    (nil? fence) marker
    (and (= (first fence) (first marker)) (>= (count marker) (count fence))) nil
    :else fence))

(defn- headings [lines]
  (loop [index 0 fence nil result []]
    (if (= index (count lines)) result
      (let [line (nth lines index)
            marker (second (re-find #"^\s{0,3}(`{3,}|~{3,})" line))
            match (when-not fence (re-matches #"^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$" line))]
        (recur (inc index) (next-fence fence marker)
               (if match (conj result {:start index :depth (count (second match))
                                      :title (plain (nth match 2))}) result))))))

(defn- field [body label]
  (some (fn [line]
          (second (re-matches (re-pattern (str "^\\s*[-*+]\\s+" label "\\s*[:：]\\s*(.+)$"))
                              (str/replace line #"[`*]" ""))))
        (str/split-lines body)))

(defn- decode-module [value]
  (try (URLDecoder/decode (str/replace value "+" "%2B") "UTF-8")
       (catch IllegalArgumentException _ value)))

(defn- title-parts
  "标题「标识 · 名称」或「名称（标识）」拆成 {:id :label}。"
  [title]
  (let [clean (-> title (str/replace #"^\d+[.、．]\s*" "")
                  (str/replace #"\s*[（(][^()（）]*/[^()（）]*[）)]\s*$" "") str/trim)
        direct (re-matches #"([\w\p{IsHan}/.\-]+)\s*[·：:|—–]\s*(.+)" clean)
        reverse (re-matches #"(.+?)\s*[（(]([\w\p{IsHan}/.\-]+)[）)]" clean)]
    {:id (or (second direct) (nth reverse 2 nil))
     :label (or (nth direct 2 nil) (second reverse) clean)}))

(defn- path-parts [target]
  (vec (remove str/blank? (str/split (str/replace target #"\|file$" "") #"[/\.]"))))

(defn- role-target [title body]
  (let [link (second (re-find #"\]\(#module=([^\s)]+)\)" body))
        target (if link (decode-module link) (:id (title-parts title)))]
    (when target (path-parts target))))

(defn- section [lines all index]
  (let [{:keys [start depth title] :as heading} (nth all index)
        end (or (:start (first (filter #(<= (:depth %) depth) (drop (inc index) all)))) (count lines))
        raw (str/join "\n" (subvec lines (inc start) end))
        status (some-> (field raw "实现状态") plain (str/replace #"[。；;]\s*$" ""))
        status (get {"已实现" "已完成" "待实现" "未完成"} status status)]
    (assoc heading :module (role-target title raw)
           :id (:id (title-parts title)) :label (:label (title-parts title))
           :scopes (some->> (field raw "覆盖范围") plain (#(str/replace % #"[。；;]\s*$" ""))
                            (#(str/split % #"[、,，;；\s]+")) (remove str/blank?) (mapv path-parts))
           :status (if (#{"已完成" "未完成" "进行中"} status) status "")
           :body (->> (str/split-lines raw)
                      (remove #(re-find #"\]\(#module=[^\s)]+\)" %))
                      (str/join "\n") str/trim))))

(defn- document-sections [content]
  (let [lines (vec (str/split-lines content)) all (headings lines)]
    (mapv #(section lines all %) (range (count all)))))

(defn- role-sections [sections]
  (let [subsystems (some #(when (and (= 2 (:depth %)) (= "核心子系统与职责" (:title %))) %) sections)
        after (if subsystems (drop-while #(<= (:start %) (:start subsystems)) sections) sections)
        scoped (if subsystems (take-while #(> (:depth %) 2) after) after)]
    (filter #(and (>= (:depth %) 2) (seq (:module %))) scoped)))
