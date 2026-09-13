(ns arch-view.render.ui.util.text-rendering
  (:require [quil.core :as q]
            [quil.applet :as applet])
  (:import [java.awt GraphicsEnvironment RenderingHints]
           [processing.awt PGraphicsJava2D]
           [processing.core PApplet]))

(defn configure-ui-scale!
  "Set the Windows default before AWT initializes; explicit JVM options win."
  []
  (when (and (.startsWith (System/getProperty "os.name" "") "Windows")
             (nil? (System/getProperty "sun.java2d.uiScale")))
    (System/setProperty "sun.java2d.uiScale" "1.25")))

(defn display-scale []
  (if (GraphicsEnvironment/isHeadless)
    1.0
    (apply max 1.0
           (for [device (.getScreenDevices (GraphicsEnvironment/getLocalGraphicsEnvironment))]
             (.getScaleX (.getDefaultTransform (.getDefaultConfiguration device)))))))

(defn pixel-density-for-scale [scale]
  (if (> scale 1.0) 2 1))

(defn configure-density!
  "Run in settings, before Processing creates the backing image."
  []
  ;; Processing rounds Windows 125% to density 1 and rejects pixelDensity(2).
  ;; Set its public settings field directly so fractional DPI also gets a 2x buffer.
  (set! (.-pixelDensity ^PApplet (applet/current-applet))
        (pixel-density-for-scale (display-scale))))

(defn configure-text!
  "Java2D creates a fresh Graphics2D each frame, so restore native font hinting."
  []
  (let [graphics (q/current-graphics)]
    (when (instance? PGraphicsJava2D graphics)
      (let [g2 (.-g2 ^PGraphicsJava2D graphics)]
        (.setRenderingHint g2 RenderingHints/KEY_TEXT_ANTIALIASING
                           RenderingHints/VALUE_TEXT_ANTIALIAS_GASP)
        (.setRenderingHint g2 RenderingHints/KEY_FRACTIONALMETRICS
                           RenderingHints/VALUE_FRACTIONALMETRICS_OFF)))))
