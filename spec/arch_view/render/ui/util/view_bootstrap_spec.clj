(ns arch-view.render.ui.util.view-bootstrap-spec
  (:require [arch-view.render.ui.util.view-bootstrap :as sut]
            [speclj.core :refer :all]))

(describe "view bootstrap"
  (it "computes viewport dimensions with floor and ceiling bounds"
    (should= 400 (sut/viewport-height-for-scene {:layer-rects []} (constantly 9999)))
    (should= 900 (sut/viewport-height-for-scene {:layer-rects [1]} (constantly 1200)))
    (should= 550 (sut/viewport-height-for-scene {:layer-rects [1]} (constantly 550)))
    (should= 1200 (sut/viewport-width-for-scene {} (constantly 600)))
    (should= 1448 (sut/viewport-width-for-scene {} (constantly 1420))))

  (it "builds initial sketch state"
    (let [state (sut/initial-sketch-state {:scene {:x 1}
                                           :architecture {:a 1}
                                           :reload-architecture :reload-fn
                                           :has-architecture? true
                                           :viewport-height 700
                                           :viewport-width 1300})]
      (should= {:x 1} (:scene state))
      (should= {:a 1} (:architecture state))
      (should= :reload-fn (:reload-architecture state))
      (should= [] (:namespace-path state))
      (should= 700 (:viewport-height state))
      (should= 1300 (:viewport-width state)))))

(describe "configured diagram zoom"
  (it "initializes rendering and hit-testing with the same configured zoom"
    (should= 1.4 (:zoom (sut/initial-sketch-state {:zoom 1.4})))
    (should= 1.0 (:zoom (sut/initial-sketch-state {})))))
