;; 工具函数：集中提供桌面界面的文字处理和布局计算，支持按依赖类型筛选显示内容。

;; mutation-tested: 2026-03-08
(ns arch-view.render.ui.util.functional
  (:require [arch-view.render.ui.util.labels :as labels]
            [arch-view.render.ui.util.layout :as layout]))

(def layer-y layout/layer-y)
(def dominant-component layout/dominant-component)
(def module-positions-for-layer layout/module-positions-for-layer)

(def abbreviate-module-name labels/abbreviate-module-name)
(def strip-top-namespace labels/strip-top-namespace)
(def label-width labels/label-width)
(def rendered-label labels/rendered-label)
(def rendered-label-lines labels/rendered-label-lines)
(def split-label-lines labels/split-label-lines)
(def declutter-modes labels/declutter-modes)
(def next-declutter-mode labels/next-declutter-mode)
(def declutter-label labels/declutter-label)
(def needs-stagger? labels/needs-stagger?)
(def stagger-offset labels/stagger-offset)
(def apply-layer-stagger labels/apply-layer-stagger)

(def arrowhead-for layout/arrowhead-for)
(def track-width-for layout/track-width-for)
(def track-x-for layout/track-x-for)
(def dependency-pairs-by-layer layout/dependency-pairs-by-layer)
(def incoming-counts-by-layer layout/incoming-counts-by-layer)
(def edge-point layout/edge-point)
(def orientation layout/orientation)
(def segment-crosses? layout/segment-crosses?)
(def edge-cross? layout/edge-cross?)
(def assign-layer-slots layout/assign-layer-slots)
(def default-slot-scoring layout/default-slot-scoring)
(def build-scene layout/build-scene)

(defn declutter-edge-drawables
  [scene mode]
  (case mode
    :concrete (->> (:edge-drawables scene)
                   (filter #(= :direct (:type %)))
                   vec)
    :abstract (->> (:edge-drawables scene)
                   (filter #(= :abstract (:type %)))
                   vec)
    (:edge-drawables scene)))
