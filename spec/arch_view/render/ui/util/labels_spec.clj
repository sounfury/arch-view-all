;; 回归测试：检查模块名称缩写、长标题拆分和文字位置是否符合已有行为。

(ns arch-view.render.ui.util.labels-spec
  (:require [arch-view.render.ui.util.labels :as sut]
            [speclj.core :refer :all]))

(describe "label helpers"
  (it "abbreviates module names and strips extensions/suffixes"
    (should= "u.core" (sut/abbreviate-module-name "empire.ui.core"))
    (should= "u.core" (sut/abbreviate-module-name "empire.ui.core.cljc"))
    (should= "u.core" (sut/abbreviate-module-name "empire.ui.core|file"))
    (should= "module" (sut/abbreviate-module-name "module")))

  (it "strips top namespace and computes rendered labels"
    (should= "ui.core" (sut/strip-top-namespace "empire.ui.core"))
    (should= "empire" (sut/strip-top-namespace "empire"))
    (should= "display" (sut/rendered-label {:display-label "display" :label "fallback"}))
    (should= "fallback" (sut/rendered-label {:label "fallback"})))

  (it "computes label width with multiline content"
    (should= 35.0 (sut/label-width "abcde"))
    (should= 49.0 (sut/label-width "a\n1234567")))

  (it "splits labels only on convenient separators when possible"
    (should= ["movement_" "resolution"]
             (sut/split-label-lines "movement_resolution" 16))
    (should= ["alpha-beta" "gamma"]
             (sut/split-label-lines "alpha-beta.gamma" 12)))

  (it "falls back to hard split when no separators are available"
    (should= ["abcdef" "ghijkl"]
             (sut/split-label-lines "abcdefghijkl" 6))
    (should= ["abc" "def"]
             (sut/split-label-lines "abcdef" 3)))

  (it "returns single line when max chars is non-positive or already short"
    (should= ["abc"] (sut/split-label-lines "abc" 0))
    (should= ["abc"] (sut/split-label-lines "abc" 5))
    (should= [""] (sut/split-label-lines nil 5))
    (should= ["label"] (sut/rendered-label-lines {:display-label "label" :max-label-chars 20})))

  (it "cycles declutter modes and provides labels"
    (should= :abstract (sut/next-declutter-mode :all))
    (should= :concrete (sut/next-declutter-mode :abstract))
    (should= :all (sut/next-declutter-mode :concrete))
    (should= :all (sut/next-declutter-mode :unknown))
    (should= "View: All" (sut/declutter-label :all))
    (should= "View: Abstract" (sut/declutter-label :abstract))
    (should= "View: Concrete" (sut/declutter-label :concrete)))

  (it "applies vertical stagger only when neighboring labels overlap"
    (let [overlapping [{:x 100.0 :y 10.0 :label "abcdefghij"}
                       {:x 110.0 :y 10.0 :label "abcdefghij"}
                       {:x 260.0 :y 10.0 :label "abcdefghij"}]
          spaced [{:x 10.0 :y 10.0 :label "abc"}
                  {:x 200.0 :y 10.0 :label "def"}]
          staggered (vec (sut/apply-layer-stagger overlapping))
          unchanged (vec (sut/apply-layer-stagger spaced))]
      (should= true (sut/needs-stagger? overlapping))
      (should= false (sut/needs-stagger? spaced))
      (should= 0.0 (sut/stagger-offset 0))
      (should= 10.0 (sut/stagger-offset 1))
      (should= 10.0 (sut/stagger-offset 3))
      (should= 10.0 (- (:y (second staggered)) (:y (second overlapping))))
      (should= spaced unchanged))))
