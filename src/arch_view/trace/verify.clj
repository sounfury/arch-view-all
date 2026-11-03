;; 职责：补全链路锚点，按静态依赖逐跳验证，并修正已移动的锚点行号。
;; 核心入口：准备（prepare）；校验（verify）；修正移动（fix-moved）。

(ns arch-view.trace.verify
  (:require [arch-view.anchor :as anchor]
            [arch-view.trace.store :as store]
            [arch-view.workspace :as workspace])
  (:import [java.time Instant]))

(declare ^:private prepare-step ^:private check-step ^:private check-hop ^:private hop-evidence
         ^:private shortest-path ^:private extend-paths ^:private summarize ^:private verdict)

(defn prepare [ws draft]
  (store/validate-draft! draft)
  (assoc draft
         :steps (mapv #(prepare-step ws %1 %2) (range) (:steps draft))
         :createdAt (or (:createdAt draft) (str (Instant/now)))
         :commit (workspace/git-head ws)
         :author (or (:author draft) "ai")))

(defn verify
  "步骤及跳转索引从 0 起；所有跳都含端点模块及路径，便于网页叠加。"
  [ws trace]
  (let [steps (mapv #(check-step ws %1 %2) (range) (:steps trace))
        edges (into #{} (map (juxt :from :to)) (get-in ws [:architecture :graph :edges]))
        adjacency (reduce (fn [m [from to]] (update m from (fnil conj #{}) to)) {} edges)
        hops (mapv #(check-hop edges adjacency %1 %2) steps (rest steps))
        summary (summarize steps hops)]
    {:trace trace :steps steps :hops hops :summary summary :verdict (verdict summary)}))

(defn fix-moved [trace verified]
  (update trace :steps
          (fn [steps]
            (mapv (fn [step checked]
                    (update step :anchor anchor/relocate (:anchor checked)))
                  steps (:steps verified)))))

;; ===== 私有方法 =====

(defn- prepare-step [ws index step]
  (try
    (update step :anchor #(anchor/make-anchor ws %))
    (catch clojure.lang.ExceptionInfo ex
      (throw (ex-info (str "第 " (inc index) " 步：" (.getMessage ex)) (ex-data ex) ex)))))

(defn- check-step [ws index step]
  {:index index
   :module (workspace/module-for-path ws (get-in step [:anchor :path]))
   :anchor (anchor/check-anchor ws (:anchor step))})

(defn- check-hop [edges adjacency from to]
  (merge {:from (:index from) :to (:index to)
          :fromModule (:module from) :toModule (:module to)}
         (hop-evidence edges adjacency (:module from) (:module to))))

(defn- hop-evidence [edges adjacency from to]
  (cond
    (or (nil? from) (nil? to)) {:status "unmapped" :path []}
    (= from to) {:status "same-module" :path [from]}
    (contains? edges [from to]) {:status "direct" :path [from to]}
    :else (if-let [path (shortest-path adjacency from to)]
            {:status "indirect" :path path}
            (if (contains? edges [to from])
              {:status "reverse" :path [to from]}
              {:status "unverified" :path []}))))

(defn- extend-paths [adjacency path seen]
  (mapv #(conj path %) (sort (remove seen (get adjacency (peek path))))))

(defn- shortest-path
  "有界 BFS：最多 3 条边；入队即标记，保证最短路径且不重复展开环。"
  [adjacency from to]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY [from]) seen #{from}]
    (when (seq queue)
      (let [path (peek queue)
            node (peek path)]
        (cond
          (= node to) path
          (= 4 (count path)) (recur (pop queue) seen)
          :else (let [paths (extend-paths adjacency path seen)]
                  (recur (into (pop queue) paths) (into seen (map peek paths)))))))))

(defn- summarize [steps hops]
  (merge (zipmap [:fresh :moved :stale :missing :same-module :direct :indirect :reverse :unverified :unmapped]
                 (repeat 0))
         (frequencies (map #(keyword (get-in % [:anchor :status])) steps))
         (frequencies (map #(keyword (:status %)) hops))))

(defn- verdict [{:keys [stale missing unverified]}]
  (cond
    (pos? (+ stale missing)) "stale"
    (pos? unverified) "needs-review"
    :else "ok"))
