;; 工具函数：判断鼠标是否位于模块标题或层级标题上，确定需要显示说明的对象。

;; mutation-tested: 2026-03-08
(ns arch-view.render.ui.util.module-hover)

(defn- label-hitbox
  [{:keys [x y] :as module-position} rendered-label-lines label-width]
  (let [lines (rendered-label-lines module-position)
        width (apply max 0.0 (map label-width lines))
        line-count (max 1 (count lines))
        pad-x 8.0
        pad-y 6.0
        half-w (+ (/ width 2.0) pad-x)
        half-h (+ (/ (* 14.0 line-count) 2.0) pad-y)]
    {:left (- x half-w)
     :right (+ x half-w)
     :top (- y half-h)
     :bottom (+ y half-h)}))

(defn hovered-module
  [module-positions mx my rendered-label-lines label-width]
  (some (fn [{:keys [module] :as m}]
          (let [{:keys [left right top bottom]} (label-hitbox m rendered-label-lines label-width)]
            (when (and (<= left mx right)
                       (<= top my bottom))
              module)))
        module-positions))

(defn hovered-module-position
  [module-positions mx my rendered-label-lines label-width]
  (some (fn [m]
          (let [{:keys [left right top bottom]} (label-hitbox m rendered-label-lines label-width)]
            (when (and (<= left mx right)
                       (<= top my bottom))
              m)))
        module-positions))

(defn- layer-label-hitbox
  [{:keys [x y label]} label-width]
  (let [width (label-width label)]
    {:left (+ x 6.0)
     :right (+ x 10.0 width)
     :top (+ y 4.0)
     :bottom (+ y 22.0)}))

(defn hovered-layer-label
  [layer-rects mx my label-width]
  (some (fn [layer-rect]
          (let [{:keys [left right top bottom]} (layer-label-hitbox layer-rect label-width)]
            (when (and (<= left mx right)
                       (<= top my bottom))
              layer-rect)))
        layer-rects))
