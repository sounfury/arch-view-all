;; 职责：验证解释草稿校验、自动编号、冲突处理和存储路径安全。
;; 核心入口：Speclj 存储规格。

(ns arch-view.notes.store-spec
  (:require [arch-view.notes.store :as store]
            [arch-view.notes.test-support :as fixture]
            [arch-view.cli-support :as support]
            [clojure.java.io :as io]
            [speclj.core :refer :all])
  (:import [java.nio.file Files]))

(describe "解释存储"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))

  (it "空目录返回空列表"
    (should= [] (store/list-notes @ws)))

  (it "最小草稿有效，缺失或空白的必填字段返回 400"
    (should= fixture/draft (store/validate-draft fixture/draft))
    (doseq [draft [nil [] (dissoc fixture/draft :title) (assoc fixture/draft :body " ")
                   (assoc fixture/draft :targets []) (assoc fixture/draft :targets "app")
                   (assoc fixture/draft :targets [nil])]]
      (should= 400 (:status (fixture/error-data #(store/validate-draft draft))))))

  (it "anchors 可空，每项必须有 path 和正整数 startLine"
    (doseq [anchors [nil [] [{:path "src/web.clj" :startLine 2}]]]
      (should-not-be-nil (store/validate-draft (assoc fixture/draft :anchors anchors))))
    (doseq [anchors [[{}] [{:path "src/web.clj"}] [{:startLine 2}]
                     [{:path "src/web.clj" :startLine 0}] [{:path "src/web.clj" :startLine "2"}] "bad"]]
      (should= 400 (:status (fixture/error-data #(store/validate-draft (assoc fixture/draft :anchors anchors)))))))

  (it "ASCII 标题生成 slug 并连续处理冲突，force 不覆盖自动编号"
    (let [draft (assoc fixture/draft :title "Web Model: overview!")]
      (should= "web-model-overview" (:id (store/write-note! @ws draft)))
      (should= "web-model-overview-2" (:id (store/write-note! @ws draft true)))
      (should= "web-model-overview-3" (:id (store/write-note! @ws draft)))))

  (it "中文标题生成稳定哈希，长标题和编号始终符合 id 规则"
    (let [first-note (store/write-note! @ws fixture/draft)
          next-note (store/write-note! @ws fixture/draft)
          long-draft (assoc fixture/draft :title (apply str (repeat 100 "a")))
          long-note (store/write-note! @ws long-draft)
          long-next (store/write-note! @ws long-draft)]
      (should-not-be-nil (re-matches #"note-[a-f0-9]{10}" (:id first-note)))
      (should= (str (:id first-note) "-2") (:id next-note))
      (should (support/valid-id? (:id long-note)))
      (should (support/valid-id? (:id long-next)))
      (should= 64 (count (:id long-next)))))

  (it "显式 id 冲突返回 409，force 允许覆盖并保留 UTF-8 正文"
    (let [draft (assoc fixture/draft :id "web")]
      (store/write-note! @ws draft)
      (should= 409 (:status (fixture/error-data #(store/write-note! @ws draft))))
      (store/write-note! @ws (assoc draft :body "更新正文") true)
      (should= "更新正文" (:body (store/read-note @ws "web")))
      (should= ["web"] (mapv :id (store/list-notes @ws)))))

  (it "读写拒绝非法 id 和路径穿越"
    (doseq [id ["../escape" "a/b" "/tmp/x" "A" "" nil "a.json"]]
      (should= 400 (:status (fixture/error-data #(store/read-note @ws id))))
      (should= 400 (:status (fixture/error-data #(store/write-note! @ws (assoc fixture/draft :id id)))))))

  (it "拒绝指向 notes 目录以外的符号链接"
    (let [dir (io/file (.toFile (:root @ws)) ".arch-view/notes")
          outside (io/file (.toFile (:root @ws)) "outside.json")]
      (.mkdirs dir)
      (spit outside "{}")
      (Files/createSymbolicLink (.toPath (io/file dir "escape.json")) (.toPath outside)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (should= 400 (:status (fixture/error-data #(store/read-note @ws "escape"))))
      (should= 400 (:status (fixture/error-data #(store/write-note! @ws (assoc fixture/draft :id "escape") true))))))

  (it "不存在的解释为 404"
    (should= 404 (:status (fixture/error-data #(store/read-note @ws "absent")))))
  (it "检测文件名和内部 id 不一致"
    (support/write-json-file! (io/file (.toFile (:root @ws)) ".arch-view/notes/a.json")
                              (assoc fixture/draft :id "b"))
    (should= 400 (:status (fixture/error-data #(store/read-note @ws "a"))))))
