;; 职责：验证链路网页接口摘要、错误隔离和用于图上高亮的完整字段。
;; 核心入口：Speclj 链路网页接口测试。

(ns arch-view.trace.web-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.trace.fixtures :as fixtures]
            [arch-view.trace.store :as store]
            [arch-view.trace.verify :as verify]
            [arch-view.trace.web :as web]
            [speclj.core :refer :all]))

(describe "链路网页接口"
  (with ws (fixtures/create!))
  (after (fixtures/cleanup! @ws))

  (it "列表返回字段齐全的即时摘要，坏 JSON 不拖垮整个接口"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a" "c")))
    (spit (fixtures/file @ws ".arch-view/traces/broken.json") "{坏 JSON" :encoding "UTF-8")
    (let [entries ((get web/routes ["GET" "/api/traces"]) @ws {})
          valid (second entries)]
      (should= "broken" (:id (first entries)))
      (should (string? (:error (first entries))))
      (should= #{:id :title :question :stepCount :summary :verdict} (set (keys valid)))
      (should= "ok" (:verdict valid))
      (should= "example" (:id valid))
      (should= 2 (:stepCount valid))
      (should= 1 (get-in valid [:summary :indirect]))))

  (it "校验失败的单条也转换为 error，剩余条目可用"
    (store/write-trace! @ws (verify/prepare @ws (fixtures/draft "a")))
    (cli-support/write-json-file! (fixtures/file @ws ".arch-view/traces/bad-anchor.json")
                                  (assoc (fixtures/draft "a") :id "bad-anchor"))
    (let [entries ((get web/routes ["GET" "/api/traces"]) @ws {})]
      (should (:error (first entries)))
      (should= 1 (get-in entries [1 :summary :fresh]))))

  (it "详情提供逐步模块、锚点状态、端点模块和间接路径"
    (let [trace (verify/prepare @ws (fixtures/draft "a" "c" "asset"))]
      (store/write-trace! @ws trace)
      (let [result ((get web/routes ["GET" "/api/trace"]) @ws {"id" "example"})]
        (should= trace (:trace result))
        (should= ["a" "c" nil] (mapv :module (:steps result)))
        (should= ["fresh" "fresh" "fresh"] (mapv #(get-in % [:anchor :status]) (:steps result)))
        (should= {:from 0 :to 1 :fromModule "a" :toModule "c" :status "indirect" :path ["a" "b" "c"]}
                 (first (:hops result)))
        (should= {:from 1 :to 2 :fromModule "c" :toModule nil :status "unmapped" :path []}
                 (second (:hops result))))))

  (it "详情不存在返回 404，非法 id 返回 400"
    (let [handler (get web/routes ["GET" "/api/trace"])]
      (should= 404 (get-in (fixtures/exception-data #(handler @ws {"id" "absent"})) [:data :status]))
      (should= 400 (get-in (fixtures/exception-data #(handler @ws {"id" "../outside"})) [:data :status]))
      (should= 400 (get-in (fixtures/exception-data #(handler @ws {})) [:data :status])))))
