;; 回归测试：检查桌面窗口状态判断和关闭等待工具是否符合已有行为。

(ns arch-view.render.ui.util.quil-lifecycle-spec
  (:require [arch-view.render.ui.util.quil-lifecycle :as sut]
            [speclj.core :refer :all]))

(describe "quil lifecycle utility"
  (it "returns false when looping check throws"
    (should= false (sut/safe-looping? (Object.))))

  (it "returns fallback when displayable check cannot inspect native component"
    (should= true (sut/safe-displayable? nil true))
    (should= false (sut/safe-displayable? (Object.) false)))

  (it "stops polling once displayable is false"
    (let [looping-calls (atom 0)]
      (with-redefs [sut/safe-looping? (fn [_] (swap! looping-calls inc) true)
                    sut/safe-displayable? (fn [_ _] false)]
        (sut/wait-until-closed! :sketch))
      (should= 1 @looping-calls)
      (sut/wait-until-closed! nil)))

  (it "polls repeatedly while sketch is looping and displayable"
    (let [looping-calls (atom 0)]
      (with-redefs [sut/safe-looping? (fn [_]
                                        (swap! looping-calls inc)
                                        (< @looping-calls 3))
                    sut/safe-displayable? (fn [_ _] true)]
        (sut/wait-until-closed! :sketch))
      (should= 3 @looping-calls))))
