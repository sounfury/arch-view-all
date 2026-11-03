;; 职责：直接调用解释路由，验证最小草稿保存、筛选、覆盖和错误状态。
;; 核心入口：Speclj 网页接口规格。

(ns arch-view.notes.web-spec
  (:require [arch-view.notes.web :as web]
            [arch-view.notes.test-support :as fixture]
            [arch-view.web.json :as json]
            [speclj.core :refer :all]))

(describe "解释网页路由"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))

  (it "最小草稿保存后返回正文、默认字段和检查状态"
    (let [saved ((web/routes ["POST" "/api/note"]) @ws {"body" (json/write-json fixture/draft)})
          shown ((web/routes ["GET" "/api/note"]) @ws {"id" (:id saved)})
          listed ((web/routes ["GET" "/api/notes"]) @ws {})]
      (should= "解释正文" (:body saved))
      (should= "unanchored" (:status saved))
      (should= false (:targetMissing saved))
      (should= "ai" (:author saved))
      (should= saved shown)
      (should= [saved] listed)))

  (it "AI 回答的 explain 来源得到保留，自动重名生成新解释"
    (let [params {"body" (json/write-json (assoc fixture/draft :source "explain"))}
          first-note ((web/routes ["POST" "/api/note"]) @ws params)
          second-note ((web/routes ["POST" "/api/note"]) @ws (assoc params "force" "true"))]
      (should= "explain" (:source first-note))
      (should= (str (:id first-note) "-2") (:id second-note))))

  (it "显式 id 冲突返回 409，force=true 允许覆盖"
    (let [params {"body" (json/write-json (assoc fixture/draft :id "web"))}
          save #( (web/routes ["POST" "/api/note"]) @ws %)]
      (save params)
      (should= 409 (:status (fixture/error-data #(save params))))
      (should= 409 (:status (fixture/error-data #(save (assoc params "force" "false")))))
      (should= "web" (:id (save (assoc params "force" "true"))))))

  (it "列表支持包目标且每条包含正文和锚点状态"
    ((web/routes ["POST" "/api/note"]) @ws
     {"body" (json/write-json (assoc fixture/draft :anchors [{:path "src/web.clj" :startLine 2}]))})
    (let [listed ((web/routes ["GET" "/api/notes"]) @ws {"target" "app.web"})]
      (should= 1 (count listed))
      (should= "解释正文" (:body (first listed)))
      (should= "fresh" (get-in listed [0 :anchors 0 :status])))
    (should= [] ((web/routes ["GET" "/api/notes"]) @ws {"target" "app.data"})))

  (it "拒绝错误 JSON、缺字段、不存在的目标和穿越 id"
    (doseq [text ["{" "null" "{}" (json/write-json (assoc fixture/draft :targets ["gone"]))]]
      (should= 400 (:status (fixture/error-data #((web/routes ["POST" "/api/note"]) @ws {"body" text})))))
    (should= 400 (:status (fixture/error-data #((web/routes ["GET" "/api/note"]) @ws {"id" "../escape"}))))
    (should= 404 (:status (fixture/error-data #((web/routes ["GET" "/api/note"]) @ws {"id" "missing"}))))))
