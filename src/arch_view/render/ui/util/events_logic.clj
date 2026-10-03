;; 工具函数：计算缩放、滚动时的坐标变化，并判断常用操作按键。

;; mutation-tested: 2026-03-08
(ns arch-view.render.ui.util.events-logic)

(defn plus-key?
  [k]
  (contains? #{:+ \+ \=} k))

(defn minus-key?
  [k]
  (contains? #{:- \- \_} k))

(defn world-y-at-screen
  [screen-y scroll-y zoom]
  (+ (/ (double screen-y) (double zoom))
     (/ (double (or scroll-y 0.0)) (double zoom))))

(defn world-x-at-screen
  [screen-x scroll-x zoom]
  (+ (/ (double screen-x) (double zoom))
     (/ (double (or scroll-x 0.0)) (double zoom))))

(defn scroll-for-world-y
  [world-y screen-y zoom]
  (- (* (double zoom) (double world-y))
     (double screen-y)))

(defn scroll-for-world-x
  [world-x screen-x zoom]
  (- (* (double zoom) (double world-x))
     (double screen-x)))

(defn point-in-toolbar?
  [mx my toolbar-height]
  (and (>= mx 0.0)
       (<= my toolbar-height)))
