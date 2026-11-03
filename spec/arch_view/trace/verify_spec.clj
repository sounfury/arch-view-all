;; 职责：覆盖逐跳状态、有界最短路径、锚点新鲜度和移动修正。
;; 核心入口：Speclj 链路校验测试。

(ns arch-view.trace.verify-spec
  (:require [arch-view.anchor :as anchor]
            [arch-view.trace.fixtures :as fixtures]
            [arch-view.trace.verify :as verify]
            [arch-view.workspace :as workspace]
            [speclj.core :refer :all]))

(describe "逐跳静态校验"
  (with ws (fixtures/create!))
  (after (fixtures/cleanup! @ws))

  (it "准备时补全哈希、UTC 时间、当前提交和默认作者"
    (with-redefs [workspace/git-head (constantly "commit-123")]
      (let [trace (verify/prepare @ws (fixtures/draft "a"))]
        (should= "ai" (:author trace))
        (should= "commit-123" (:commit trace))
        (should= (:createdAt trace) (str (java.time.Instant/parse (:createdAt trace))))
        (should= (anchor/make-anchor @ws {:path "a.clj" :startLine 2}) (get-in trace [:steps 0 :anchor])))))

  (it "保留显式作者和创建时间"
    (let [trace (verify/prepare @ws (assoc (fixtures/draft "a") :author "human" :createdAt "2026-11-03T10:00:00Z"))]
      (should= "human" (:author trace))
      (should= "2026-11-03T10:00:00Z" (:createdAt trace))))

  (it "锚点创建错误带具体步骤和 400"
    (let [draft (assoc-in (fixtures/draft "a" "b") [:steps 1 :anchor :startLine] 100)
          error (fixtures/exception-data #(verify/prepare @ws draft))]
      (should-contain "第 2 步" (:message error))
      (should= 400 (get-in error [:data :status]))))

  (it "逐一覆盖同模块、直接、两跳间接、反向、无证据和未映射"
    (doseq [[modules status path] [[["a" "a"] "same-module" ["a"]]
                                  [["a" "b"] "direct" ["a" "b"]]
                                  [["a" "c"] "indirect" ["a" "b" "c"]]
                                  [["b" "a"] "reverse" ["a" "b"]]
                                  [["a" "u"] "unverified" []]
                                  [["a" "asset"] "unmapped" []]
                                  [["asset" "asset"] "unmapped" []]]]
      (let [result (verify/verify @ws (verify/prepare @ws (apply fixtures/draft modules)))
            hop (first (:hops result))]
        (should= status (:status hop))
        (should= path (:path hop))
        (should= [0 1] [(:from hop) (:to hop)])
        (should= (mapv :module (:steps result)) [(:fromModule hop) (:toModule hop)])
        (should= 1 (get-in result [:summary (keyword status)]))
        (should= 2 (get-in result [:summary :fresh])))))

  (it "BFS 允许三跳、拒绝四跳，并能结束含环的搜索"
    (let [three (verify/verify @ws (verify/prepare @ws (fixtures/draft "a" "d")))
          four (verify/verify @ws (verify/prepare @ws (fixtures/draft "a" "e")))]
      (should= ["a" "b" "c" "d"] (get-in three [:hops 0 :path]))
      (should= "indirect" (get-in three [:hops 0 :status]))
      (should= "unverified" (get-in four [:hops 0 :status]))))

  (it "有多条路径时返回最短路径，优先前向路径而非反向直接边"
    (let [ws (update-in @ws [:architecture :graph :edges] into #{{:from "a" :to "c"} {:from "d" :to "a"}})
          result (verify/verify ws (verify/prepare ws (fixtures/draft "a" "d")))]
      (should= "indirect" (get-in result [:hops 0 :status]))
      (should= ["a" "c" "d"] (get-in result [:hops 0 :path]))))

  (it "汇总所有状态，包括零计数，并为无证据给 needs-review"
    (let [result (verify/verify @ws (verify/prepare @ws (fixtures/draft "a" "u")))]
      (should= "needs-review" (:verdict result))
      (should= {:fresh 2 :moved 0 :stale 0 :missing 0 :same-module 0 :direct 0 :indirect 0
                :reverse 0 :unverified 1 :unmapped 0} (:summary result))))

  (it "内容移动时仍为 ok，fix-moved 修正后重新检查为 fresh"
    (let [trace (verify/prepare @ws (fixtures/draft "a" "b"))
          file (fixtures/file @ws "a.clj")]
      (spit file (str "新增\n" (slurp file :encoding "UTF-8")) :encoding "UTF-8")
      (let [result (verify/verify @ws trace)
            fixed (verify/fix-moved trace result)]
        (should= "ok" (:verdict result))
        (should= 1 (get-in result [:summary :moved]))
        (should= 3 (get-in result [:steps 0 :anchor :currentStartLine]))
        (should= 3 (get-in fixed [:steps 0 :anchor :startLine]))
        (should= 3 (get-in fixed [:steps 0 :anchor :endLine]))
        (should= (get-in trace [:steps 0 :anchor :hash]) (get-in fixed [:steps 0 :anchor :hash]))
        (should= (second (:steps trace)) (second (:steps fixed)))
        (should-be-nil (get-in fixed [:steps 0 :anchor :status]))
        (should= 2 (get-in (verify/verify @ws fixed) [:summary :fresh])))))

  (it "内容变化与文件缺失分别统计，stale 结论优先于人工复核"
    (let [trace (verify/prepare @ws (fixtures/draft "a" "u" "b"))]
      (spit (fixtures/file @ws "a.clj") "内容完全变化\n" :encoding "UTF-8")
      (.delete (fixtures/file @ws "b.clj"))
      (let [result (verify/verify @ws trace)]
        (should= "stale" (:verdict result))
        (should= 1 (get-in result [:summary :stale]))
        (should= 1 (get-in result [:summary :missing]))
        (should= 2 (get-in result [:summary :unverified]))
        (should= trace (verify/fix-moved trace result))))))
