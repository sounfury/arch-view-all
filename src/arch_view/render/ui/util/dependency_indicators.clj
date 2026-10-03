;; 职责：生成并绘制模块边缘的依赖三角标记，提供鼠标悬停时的依赖详情。
;; 核心入口：生成依赖标记（dependency-indicators）；绘制标记（draw-dependency-indicators）。

;; mutation-tested: 2026-03-08
(ns arch-view.render.ui.util.dependency-indicators
  (:require [arch-view.render.ui.util.functional :as functional]
            [quil.core :as q]))

(def ^:private dependency-triangle-side 12.0)
(def ^:private dependency-triangle-height
  (* dependency-triangle-side 0.8660254037844386))
(def ^:private dependency-triangle-hover-tolerance 5.0)

(defn- rect-bottom
  [{:keys [y height]}]
  (+ y height))

(defn- rect-center-x
  [rect]
  (+ (:x rect) (/ (:width rect) 2.0)))

(defn- triangle-points
  [rect direction]
  (let [cx (rect-center-x rect)
        half-side (/ dependency-triangle-side 2.0)
        edge-y (if (= direction :incoming)
                 (:y rect)
                 (rect-bottom rect))]
    [[(- cx half-side) edge-y]
     [(+ cx half-side) edge-y]
     [cx (+ edge-y dependency-triangle-height)]]))

(defn- dependency-labels
  [dependency-entries full-name-by-module strip-top-namespace]
  (->> dependency-entries
       (reduce (fn [acc {:keys [module cycle-break?]}]
                 (update acc module (fn [existing]
                                      {:module module
                                       :cycle? (or cycle-break? (get existing :cycle? false))})))
               {})
       vals
       (sort-by :module)
       (mapv (fn [{:keys [module cycle?]}]
               {:text (or (get full-name-by-module module)
                          (strip-top-namespace (str module)))
                :cycle? (boolean cycle?)}))))

(defn dependency-indicators
  [scene declutter-mode strip-top-namespace]
  (let [edge-drawables (functional/declutter-edge-drawables scene declutter-mode)
        full-name-by-module (into {}
                                  (keep (fn [{:keys [module full-name]}]
                                          (when module [module full-name])))
                                  (:module-positions scene))
        incoming-by-module (reduce (fn [acc {:keys [from to cycle-break?]}]
                                     (update acc to (fnil conj [])
                                             {:module from :cycle-break? cycle-break?}))
                                   {}
                                   edge-drawables)
        outgoing-by-module (reduce (fn [acc {:keys [from to cycle-break?]}]
                                     (update acc from (fnil conj [])
                                             {:module to :cycle-break? cycle-break?}))
                                   {}
                                   edge-drawables)]
    (->> (:layer-rects scene)
         (mapcat (fn [{:keys [module] :as rect}]
                   (let [incoming (dependency-labels (get incoming-by-module module)
                                                    full-name-by-module
                                                    strip-top-namespace)
                         outgoing (dependency-labels (get outgoing-by-module module)
                                                    full-name-by-module
                                                    strip-top-namespace)]
                     (cond-> []
                       (seq incoming)
                       (conj {:module module
                              :direction :incoming
                              :triangle (triangle-points rect :incoming)
                              :cycle? (boolean (some :cycle? incoming))
                              :tooltip-lines incoming})

                       (seq outgoing)
                       (conj {:module module
                              :direction :outgoing
                              :triangle (triangle-points rect :outgoing)
                              :cycle? (boolean (some :cycle? outgoing))
                              :tooltip-lines outgoing})))))
         (sort-by (juxt :module :direction))
         vec)))

(defn draw-dependency-indicators
  [indicators]
  (q/no-stroke)
  (doseq [{:keys [triangle cycle?]} indicators
          :let [[[x1 y1] [x2 y2] [x3 y3]] triangle]]
    (if cycle?
      (q/fill 180 0 0)
      (q/fill 0 0 0))
    (q/triangle x1 y1 x2 y2 x3 y3)))

(defn- point->segment-distance
  [px py x1 y1 x2 y2]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        len2 (+ (* dx dx) (* dy dy))]
    (if (< len2 0.001)
      (Math/sqrt (+ (* (- px x1) (- px x1))
                    (* (- py y1) (- py y1))))
      (let [t (-> (/ (+ (* (- px x1) dx)
                        (* (- py y1) dy))
                     len2)
                  double
                  (max 0.0)
                  (min 1.0))
            proj-x (+ x1 (* t dx))
            proj-y (+ y1 (* t dy))]
        (Math/sqrt (+ (* (- px proj-x) (- px proj-x))
                      (* (- py proj-y) (- py proj-y))))))))

(defn- triangle-sign
  [[px py] [ax ay] [bx by]]
  (- (* (- px bx) (- ay by))
     (* (- ax bx) (- py by))))

(defn- point-in-triangle?
  [mx my [[ax ay :as a] [bx by :as b] [cx cy :as c]]]
  (let [p [mx my]
        d1 (triangle-sign p a b)
        d2 (triangle-sign p b c)
        d3 (triangle-sign p c a)
        has-neg? (or (neg? d1) (neg? d2) (neg? d3))
        has-pos? (or (pos? d1) (pos? d2) (pos? d3))]
    (not (and has-neg? has-pos?))))

(defn hovered-dependency
  [indicators mx my]
  (let [mx (double mx)
        my (double my)]
    (->> indicators
         (map (fn [{:keys [triangle] :as indicator}]
                (let [inside? (point-in-triangle? mx my triangle)
                      edges (map vector triangle (concat (rest triangle) [(first triangle)]))
                      nearest-edge (reduce min Double/MAX_VALUE
                                           (map (fn [[[x1 y1] [x2 y2]]]
                                                  (point->segment-distance mx my x1 y1 x2 y2))
                                                edges))]
                  (when (or inside? (<= nearest-edge dependency-triangle-hover-tolerance))
                    (assoc indicator :hover-distance nearest-edge)))))
         (remove nil?)
         (sort-by :hover-distance)
         first)))
