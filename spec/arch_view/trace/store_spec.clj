;; 职责：覆盖链路草稿的具体错误、路径安全及 JSON 存储和覆盖策略。
;; 核心入口：Speclj 链路存储测试。

(ns arch-view.trace.store-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.trace.fixtures :as fixtures]
            [arch-view.trace.store :as store]
            [speclj.core :refer :all]))

(describe "链路存储"
  (with ws (fixtures/create!))
  (after (fixtures/cleanup! @ws))

  (it "空目录列出空列表，读写 JSON 后按 id 排序"
    (should= [] (store/list-ids @ws))
    (let [draft (fixtures/draft "a")]
      (store/write-trace! @ws (assoc draft :id "z"))
      (store/write-trace! @ws draft)
      (should= ["example" "z"] (store/list-ids @ws))
      (should= draft (store/read-trace @ws "example"))
      (should= ["example" "z"] (mapv :id (store/list-traces @ws)))))

  (it "同 id 必须显式覆盖"
    (let [draft (fixtures/draft "a") updated (assoc draft :title "新标题")]
      (store/write-trace! @ws draft)
      (should= 409 (get-in (fixtures/exception-data #(store/write-trace! @ws updated)) [:data :status]))
      (should= draft (store/read-trace @ws "example"))
      (store/write-trace! @ws updated true)
      (should= updated (store/read-trace @ws "example"))))

  (it "缺失文件为 404，文件内 id 必须与文件名一致"
    (should= 404 (get-in (fixtures/exception-data #(store/read-trace @ws "missing")) [:data :status]))
    (cli-support/write-json-file! (fixtures/file @ws ".arch-view/traces/wrong.json") (fixtures/draft "a"))
    (should= 400 (get-in (fixtures/exception-data #(store/read-trace @ws "wrong")) [:data :status])))

  (it "读写两侧都拒绝 id 穿越和非法字符"
    (doseq [id ["../escape" "a/b" ".." "/tmp/escape" "a\\b" "Upper" "" nil (apply str (repeat 65 "a"))]]
      (should= 400 (get-in (fixtures/exception-data #(store/read-trace @ws id)) [:data :status]))
      (should= 400 (get-in (fixtures/exception-data #(store/write-trace! @ws (assoc (fixtures/draft "a") :id id)))
                          [:data :status]))))

  (it "必填字段、空步骤和非法 via 给出 400"
    (let [draft (fixtures/draft "a")]
      (doseq [bad [(dissoc draft :id) (dissoc draft :title) (dissoc draft :steps)
                   (assoc draft :steps []) (assoc draft :steps {})
                   (assoc-in draft [:steps 0 :title] " ")
                   (assoc-in draft [:steps 0 :anchor :startLine] 0)
                   (assoc-in draft [:steps 0 :anchor :endLine] 1)
                   (assoc-in draft [:steps 0 :via] "socket")
                   (assoc-in draft [:steps 0 :via] nil)]]
        (should= 400 (get-in (fixtures/exception-data #(store/validate-draft! bad)) [:data :status])))))

  (it "缺失锚点字段准确指出第三步"
    (let [draft (update-in (fixtures/draft "a" "b" "c") [:steps 2 :anchor] dissoc :path)
          error (fixtures/exception-data #(store/validate-draft! draft))]
      (should= "第 3 步缺少 anchor.path" (:message error))
      (should= 400 (get-in error [:data :status]))))

  (it "接受契约中的所有 via 值"
    (doseq [via ["call" "http" "event" "db" "config" "return" "other"]]
      (let [draft (assoc-in (fixtures/draft "a") [:steps 0 :via] via)]
        (should= draft (store/validate-draft! draft)))))

  (it "忽略非法文件名和其它后缀"
    (cli-support/write-json-file! (fixtures/file @ws ".arch-view/traces/Upper.json") {})
    (cli-support/write-json-file! (fixtures/file @ws ".arch-view/traces/notes.txt") {})
    (should= [] (store/list-ids @ws))))
