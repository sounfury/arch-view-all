(ns arch-view.render.ui.util.resize)

(def settle-ms 200)

(defn resize-scene
  "Keep drawing the existing scene while dragging; relayout once resizing settles."
  [state previous-width now rebuild]
  (cond
    (nil? (:architecture state)) state
    (not= (double (or previous-width 0)) (double (:viewport-width state)))
    (assoc state :resize-started-at now)
    (and (:resize-started-at state)
         (>= (- now (:resize-started-at state)) settle-ms))
    (-> state
        (assoc :scene (rebuild (:architecture state)
                              (vec (or (:namespace-path state) []))
                              (:viewport-width state))
               :routed-edges nil)
        (dissoc :resize-started-at))
    :else state))
