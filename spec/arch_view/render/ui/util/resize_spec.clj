(ns arch-view.render.ui.util.resize-spec
  (:require [arch-view.render.ui.util.resize :as sut]
            [speclj.core :refer :all]))

(describe "window resizing"
  (it "coalesces continuous resize events and uses the final width"
    (let [calls (atom [])
          rebuild (fn [architecture path width]
                    (swap! calls conj [architecture path width]) :new-scene)
          initial {:architecture :arch :namespace-path ["app"] :viewport-width 1300
                   :scene :old-scene :routed-edges :old-routes}
          first-size (sut/resize-scene initial 1200 0 rebuild)
          last-size (sut/resize-scene (assoc first-size :viewport-width 1500) 1300 100 rebuild)
          dragging (sut/resize-scene last-size 1500 299 rebuild)
          settled (sut/resize-scene dragging 1500 300 rebuild)]
      (should= :old-scene (:scene dragging))
      (should= [[:arch ["app"] 1500]] @calls)
      (should= :new-scene (:scene settled))
      (should= nil (:routed-edges settled))
      (should= settled (sut/resize-scene settled 1500 1000 rebuild))
      (should= 1 (count @calls))))

  (it "does not rebuild for height-only changes or a scene without architecture"
    (let [rebuild (fn [& _] (throw (Exception. "unexpected rebuild")))
          state {:viewport-width 1200 :viewport-height 800 :scene :scene}]
      (should= state (sut/resize-scene state 1000 0 rebuild))
      (should= (assoc state :architecture :arch)
               (sut/resize-scene (assoc state :architecture :arch) 1200 0 rebuild)))))
