;; 职责：验证解释准备、目标匹配、锚点状态汇总和移动修复。
;; 核心入口：Speclj 解释核心规格。

(ns arch-view.notes.core-spec
  (:require [arch-view.notes.core :as notes]
            [arch-view.notes.store :as store]
            [arch-view.notes.test-support :as fixture]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "解释准备与检查"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))
  (with source (io/file (.toFile (:root @ws)) "src/web.clj"))
  (with anchored (notes/prepare @ws (assoc fixture/draft :anchors [{:path "src/web.clj" :startLine 2}])) )

  (it "最小草稿补齐元数据，无锚点解释为 unanchored"
    (with-redefs [workspace/git-head (constantly "abc123")]
      (let [note (notes/prepare @ws fixture/draft)]
        (should= "ai" (:author note))
        (should= "manual" (:source note))
        (should= "abc123" (:commit note))
        (should-not-be-nil (java.time.Instant/parse (:createdAt note)))
        (should= [] (:anchors note))
        (should= "unanchored" (:status (notes/check @ws note)))
        (should= false (:targetMissing (notes/check @ws note))))))

  (it "保留显式元数据，规范源码目标和包目标"
    (let [note (notes/prepare @ws (assoc fixture/draft :targets ["src/web.clj" "app.web."]
                                       :author "开发者" :source "explain" :createdAt "2026-01-01T00:00:00Z"))]
      (should= ["app.web.model" "app.web"] (:targets note))
      (should= "开发者" (:author note))
      (should= "explain" (:source note))
      (should= "2026-01-01T00:00:00Z" (:createdAt note))))

  (it "目标解析失败转换为 400 并携带候选"
    (let [data (fixture/error-data #(notes/prepare @ws (assoc fixture/draft :targets ["model"])))]
      (should= 400 (:status data))
      (should= ["app.web.model"] (:candidates data))))

  (it "锚点生成哈希并检测 fresh、moved、stale、missing"
    (let [note @anchored]
      (should-not-be-nil (re-matches #"sha256:[a-f0-9]{64}" (get-in note [:anchors 0 :hash])))
      (should= "fresh" (:status (notes/check @ws note)))
      (spit @source "新增\n头部\n(defn view [] :ok)\n尾部\n")
      (should= "moved" (:status (notes/check @ws note)))
      (should= 3 (get-in (notes/check @ws note) [:anchors 0 :currentStartLine]))
      (spit @source "头部\n(defn view [] :changed)\n尾部\n")
      (should= "stale" (:status (notes/check @ws note)))
      (.delete @source)
      (should= "missing" (:status (notes/check @ws note)))))

  (it "已有哈希且 fresh 时仅保留规范锚点字段"
    (let [note @anchored
          item (assoc (first (:anchors note)) :symbol "view")
          draft (assoc note :anchors [(assoc item :status "moved" :currentStartLine 99
                                                 :currentEndLine 99 :extra "忽略")])
          saved (store/write-note! @ws (notes/prepare @ws draft))]
      (should= [item] (:anchors saved))
      (should= "fresh" (:status (notes/check @ws saved)))))

  (it "生成后修改引用源码，保存旧哈希返回 409 且不写入解释"
    (let [note @anchored]
      (spit @source "头部\n(defn view [] :changed)\n尾部\n")
      (try (store/write-note! @ws (notes/prepare @ws note))
           (should-fail "旧哈希必须拒绝保存")
           (catch clojure.lang.ExceptionInfo ex
             (should= {:status 409} (ex-data ex))
             (should= "引用源码在生成解释后已变化，请重新解释或核对后再保存" (.getMessage ex))))
      (should= [] (store/list-notes @ws))))

  (it "生成后删除引用源码，保存旧哈希返回 409"
    (let [note @anchored]
      (.delete @source)
      (should= 409 (:status (fixture/error-data #(notes/prepare @ws note))))))

  (it "已有哈希的引用内容移动后保存新行号和原哈希，去掉检查字段"
    (let [note (notes/prepare @ws (assoc fixture/draft :anchors [{:path "src/web.clj" :startLine 1 :endLine 2
                                                                 :symbol "view"}]))
          item (first (:anchors note))]
      (spit @source "新增\n头部\n(defn view [] :ok)\n尾部\n")
      (let [draft (assoc note :anchors (:anchors (notes/check @ws note)))
            saved (store/write-note! @ws (notes/prepare @ws draft))]
        (should= [(assoc item :startLine 2 :endLine 3)] (:anchors saved))
        (should= "fresh" (:status (notes/check @ws saved))))))

  (it "不带哈希的 CLI 草稿仍按当前源码生成哈希并保存"
    (let [original-hash (get-in @anchored [:anchors 0 :hash])]
      (spit @source "头部\n(defn view [] :changed)\n尾部\n")
      (let [draft (assoc fixture/draft :anchors [{:path "src/../src/web.clj" :startLine 2}])
            saved (store/write-note! @ws (notes/prepare @ws draft))]
        (should-not= original-hash (get-in saved [:anchors 0 :hash]))
        (should= "src/web.clj" (get-in saved [:anchors 0 :path]))
        (should= 2 (get-in saved [:anchors 0 :endLine]))
        (should= "fresh" (:status (notes/check @ws saved))))))

  (it "多个锚点按 missing、stale、moved、fresh 取最差"
    (let [note (notes/prepare @ws (assoc fixture/draft :anchors [{:path "src/web.clj" :startLine 1}
                                                               {:path "src/web.clj" :startLine 2}]))]
      (spit @source "新增\n头部\n内容改变\n尾部\n")
      (should= ["moved" "stale"] (mapv :status (:anchors (notes/check @ws note))))
      (should= "stale" (:status (notes/check @ws note)))
      (let [mixed (update note :anchors conj {:path "gone.clj" :startLine 1 :endLine 1 :hash "sha256:none"})]
        (should= "missing" (:status (notes/check @ws mixed))))))

  (it "单独标记目标失效，不影响锚点状态"
    (let [note @anchored
          changed (assoc-in @ws [:architecture :graph :nodes] #{"app.data"})
          result (notes/check changed note)]
      (should= "fresh" (:status result))
      (should= true (:targetMissing result))
      (should= true (:targetMissing (notes/check changed (assoc note :targets ["app.web"]))))))

  (it "fix-moved 只更新移动锚点行号，保留正文、哈希和元数据，不落盘"
    (let [note @anchored]
      (spit @source "新增\n头部\n(defn view [] :ok)\n尾部\n")
      (let [checked (notes/check @ws note)
            fixed (notes/fix-moved note checked)]
        (should= 3 (get-in fixed [:anchors 0 :startLine]))
        (should= 3 (get-in fixed [:anchors 0 :endLine]))
        (should= (dissoc note :anchors) (dissoc fixed :anchors))
        (should= (get-in note [:anchors 0 :hash]) (get-in fixed [:anchors 0 :hash]))
        (should-not (contains? (first (:anchors fixed)) :status))
        (should= "fresh" (:status (notes/check @ws fixed))))))

  (it "未移动的锚点在修复后保持原样"
    (should= @anchored (notes/fix-moved @anchored (notes/check @ws @anchored)))))

(describe "解释目标匹配"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))
  (with entries [{:id "ancestor" :targets ["app"] :createdAt "2026-03-01"}
                 {:id "package" :targets ["app.web"] :createdAt "2026-02-01"}
                 {:id "old" :targets ["app.web.model"] :createdAt "2026-01-01"}
                 {:id "new" :targets ["app.web.model" "app.data"] :createdAt "2026-01-02"}
                 {:id "sub" :targets ["app.web.sub"] :createdAt "2026-04-01"}
                 {:id "child" :targets ["app.web.sub.api"] :createdAt "2026-05-01"}
                 {:id "neighbor" :targets ["app.webbing"] :createdAt "2026-06-01"}])

  (it "模块命中直接解释及祖先包，直接命中优先再按时间倒序"
    (should= ["new" "old" "ancestor" "package"]
             (mapv :id (notes/matching @entries @ws "app.web.model"))))
  (it "包包含祖先、后代模块及子包，不包含名称相近的邻居"
    (should= ["package" "child" "sub" "ancestor" "new" "old"]
             (mapv :id (notes/matching @entries @ws "app.web"))))
  (it "源码路径与模块匹配结果相同，多目标解释只出现一次"
    (should= (notes/matching @entries @ws "app.web.model")
             (notes/matching @entries @ws "src/web.clj"))))
