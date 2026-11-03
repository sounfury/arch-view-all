;; 职责：按网页的命名空间、包文档基址和子系统标题规则匹配架构职责。
;; 核心入口：查找目标及祖先包的职责小节（matching-sections）。

(ns arch-view.context.architecture
  (:require [arch-view.web.documents :as documents]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URLDecoder]))

(declare ^:private document-sections ^:private visible-parts ^:private document-base
         ^:private prefix? ^:private architecture-document? ^:private matching-document
         ^:private headings ^:private next-fence ^:private section ^:private plain
         ^:private role-target ^:private field ^:private role-sections ^:private decode-module)

(defn matching-sections
  "复用 discover；项目文档从根视图定位，包文档从目录对应的浏览路径定位。"
  [ws resolved]
  (let [docs (documents/discover (:root ws) (:architecture ws) (get-in ws [:opts :architecture-doc]))
        parts (visible-parts ws (:target resolved))]
    (vec (mapcat #(matching-document ws resolved parts %) (filter architecture-document? docs)))))

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

(defn- role-target [title body]
  (let [clean (-> title (str/replace #"^\d+[.、．]\s*" "")
                  (str/replace #"\s*[（(][^()（）]*/[^()（）]*[）)]\s*$" "") str/trim)
        direct (re-matches #"([\w\p{IsHan}/.\-]+)\s*[·：:|—–]\s*(.+)" clean)
        reverse (re-matches #"(.+?)\s*[（(]([\w\p{IsHan}/.\-]+)[）)]" clean)
        link (second (re-find #"\]\(#module=([^\s)]+)\)" body))
        target (if link (decode-module link) (or (second direct) (nth reverse 2 nil)))]
    (when target (vec (remove str/blank? (str/split target #"[/\.]"))))))

(defn- section [lines all index]
  (let [{:keys [start depth title] :as heading} (nth all index)
        end (or (:start (first (filter #(<= (:depth %) depth) (drop (inc index) all)))) (count lines))
        raw (str/join "\n" (subvec lines (inc start) end))
        status (some-> (field raw "实现状态") plain (str/replace #"[。；;]\s*$" ""))
        status (get {"已实现" "已完成" "待实现" "未完成"} status status)]
    (assoc heading :module (role-target title raw)
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
