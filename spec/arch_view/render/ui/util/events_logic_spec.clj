;; 回归测试：检查缩放、滚动坐标和按键判断工具是否符合已有行为。

(ns arch-view.render.ui.util.events-logic-spec
  (:require [arch-view.render.ui.util.events-logic :as sut]
            [speclj.core :refer :all]))

(describe "events logic"
  (it "detects plus and minus zoom keys"
    (should= true (sut/plus-key? :+))
    (should= true (sut/plus-key? \=))
    (should= false (sut/plus-key? \-))
    (should= true (sut/minus-key? :-))
    (should= true (sut/minus-key? \_))
    (should= false (sut/minus-key? \+)))

  (it "converts between world and screen coordinates"
    (should= 60.0 (sut/world-y-at-screen 20.0 100.0 2.0))
    (should= 40.0 (sut/world-x-at-screen 10.0 70.0 2.0))
    (should= 90.0 (sut/scroll-for-world-y 50.0 10.0 2.0))
    (should= 80.0 (sut/scroll-for-world-x 45.0 10.0 2.0)))

  (it "treats toolbar boundary as inclusive and x as non-negative"
    (should= true (sut/point-in-toolbar? 0.0 38.0 38.0))
    (should= false (sut/point-in-toolbar? -0.1 10.0 38.0))
    (should= false (sut/point-in-toolbar? 10.0 38.1 38.0))))
