;; 职责：验证共享锚点的哈希规范化、路径安全、移动检测和重定位。
;; 核心入口：Speclj 锚点规格。

(ns arch-view.anchor-spec
  (:require [arch-view.anchor :as anchor]
            [arch-view.notes.test-support :as fixture]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(describe "共享代码锚点"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))
  (with source (io/file (.toFile (:root @ws)) "src/web.clj"))

  (it "默认 endLine 等于 startLine，保留 symbol，规范相对路径"
    (let [item (anchor/make-anchor @ws {:path "src/../src/web.clj" :startLine 2 :symbol "view"})]
      (should= "src/web.clj" (:path item))
      (should= 2 (:endLine item))
      (should= "view" (:symbol item))
      (should-not-be-nil (re-matches #"sha256:[a-f0-9]{64}" (:hash item)))
      (should= "fresh" (:status (anchor/check-anchor @ws item)))))

  (it "哈希忽略行尾空白和 CRLF，但保留缩进"
    (spit @source "  第一行  \r\n第二行\t\r\n")
    (let [item (anchor/make-anchor @ws {:path "src/web.clj" :startLine 1 :endLine 2})]
      (spit @source "  第一行\n第二行\n")
      (should= "fresh" (:status (anchor/check-anchor @ws item)))
      (spit @source "第一行\n第二行\n")
      (should= "stale" (:status (anchor/check-anchor @ws item)))))

  (it "拒绝项目外路径、不存在文件和无效行号"
    (doseq [item [{:path "../outside.clj" :startLine 1}
                  {:path "/etc/passwd" :startLine 1}
                  {:path "missing.clj" :startLine 1}
                  {:path "src/web.clj"}
                  {:path "src/web.clj" :startLine 0}
                  {:path "src/web.clj" :startLine 3 :endLine 2}
                  {:path "src/web.clj" :startLine 99}
                  {:path "src/web.clj" :startLine 1.5}]]
      (should= 400 (:status (fixture/error-data #(anchor/make-anchor @ws item))))))

  (it "移动保留等长区间，并选择离原位置最近的相同内容"
    (spit @source "a\nb\na\nb\nx\ny\n")
    (let [item (anchor/make-anchor @ws {:path "src/web.clj" :startLine 3 :endLine 4})]
      (spit @source "a\nb\nx\na\nb\ny\n")
      (let [checked (anchor/check-anchor @ws item)]
        (should= "moved" (:status checked))
        (should= 4 (:currentStartLine checked))
        (should= 5 (:currentEndLine checked))
        (should= (assoc item :startLine 4 :endLine 5) (anchor/relocate item checked)))))

  (it "原行号超出新文件时仍可找到移动区间，否则 stale"
    (let [item (anchor/make-anchor @ws {:path "src/web.clj" :startLine 2})]
      (spit @source "(defn view [] :ok)")
      (should= "moved" (:status (anchor/check-anchor @ws item)))
      (spit @source "changed")
      (should= "stale" (:status (anchor/check-anchor @ws item)))))

  (it "删除文件及项目外锚点返回 missing，非 moved 不改变行号"
    (let [item (anchor/make-anchor @ws {:path "src/web.clj" :startLine 2})]
      (.delete @source)
      (let [checked (anchor/check-anchor @ws item)]
        (should= "missing" (:status checked))
        (should= item (anchor/relocate item checked)))
      (should= "missing" (:status (anchor/check-anchor @ws (assoc item :path "../escape")))))))
