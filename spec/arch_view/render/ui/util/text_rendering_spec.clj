(ns arch-view.render.ui.util.text-rendering-spec
  (:require [arch-view.render.ui.util.text-rendering :as sut]
            [quil.applet :as applet]
            [quil.core :as q]
            [speclj.core :refer :all])
  (:import [java.awt RenderingHints]
           [java.awt.image BufferedImage]
           [java.lang.reflect Modifier]
           [processing.awt PGraphicsJava2D PSurfaceAWT]
           [processing.core PApplet]))

(describe "Windows rendering regressions"
  (it "loads the Processing surface without the resize deadlock monitor"
    (let [method (.getDeclaredMethod PSurfaceAWT "render" (make-array Class 0))]
      (should= false (Modifier/isSynchronized (.getModifiers method)))))

  (it "overrides the animation thread so missing AWT buffers cannot kill drawing"
    (let [thread-class (Class/forName "processing.awt.PSurfaceAWT$9")
          location (-> thread-class .getProtectionDomain .getCodeSource .getLocation str)]
      (should-not-be-nil (.getDeclaredMethod thread-class "callDraw" (make-array Class 0)))
      (should= true (.contains location "classes"))))

  (it "allocates high resolution backing pixels even at Windows 125 percent scale"
    (doseq [[scale density] [[1.0 1] [1.25 2] [1.5 2] [2.0 2]]]
      (let [sketch (PApplet.)]
        (binding [applet/*applet* sketch]
          (with-redefs [sut/display-scale (constantly scale)]
            (sut/configure-density!)))
        (should= density (.-pixelDensity sketch)))))

  (it "applies font hinting again on a new graphics context"
    (let [graphics (PGraphicsJava2D.)
          image (BufferedImage. 100 100 BufferedImage/TYPE_INT_ARGB)]
      (dotimes [_ 2]
        (let [g2 (.createGraphics image)]
          (try
            (set! (.-g2 graphics) g2)
            (with-redefs [q/current-graphics (constantly graphics)]
              (sut/configure-text!))
            (should= RenderingHints/VALUE_TEXT_ANTIALIAS_GASP
                     (.getRenderingHint g2 RenderingHints/KEY_TEXT_ANTIALIASING))
            (should= RenderingHints/VALUE_FRACTIONALMETRICS_OFF
                     (.getRenderingHint g2 RenderingHints/KEY_FRACTIONALMETRICS))
            (finally (.dispose g2))))))))

(describe "CJK font selection"
  (it "matches Processing face names to family preferences"
    (should= "PingFangSC-Regular"
             (sut/choose-cjk-font ["PingFang SC" "SansSerif"]
                                  ["Arial" "PingFangSC-Regular" "SansSerif"])))

  (it "uses an exact available family name"
    (should= "Microsoft YaHei"
             (sut/choose-cjk-font ["Microsoft YaHei" "SimHei"]
                                  ["Microsoft YaHei" "Arial"])))

  (it "falls back to SansSerif when no CJK font is installed"
    (should= "SansSerif"
             (sut/choose-cjk-font ["PingFang SC" "Microsoft YaHei" "SansSerif"]
                                  ["Arial" "SansSerif"]))))
