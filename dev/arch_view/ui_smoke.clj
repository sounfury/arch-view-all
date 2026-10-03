;; 验收工具：打开真实桌面窗口，反复调整窗口尺寸并保存截图，用于发现绘制或缩放问题。

(ns arch-view.ui-smoke
  "Optional desktop regression: resize a real window and save a completed frame."
  (:require [arch-view.core :as core]
            [arch-view.render.ui.quil.view :as view]
            [arch-view.render.ui.util.text-rendering :as text-rendering]
            [clojure.java.io :as io]
            [quil.applet :as applet])
  (:import [java.awt EventQueue]
           [java.lang.management ManagementFactory]))

(defn- start-watchdog! []
  (doto (Thread. (fn []
                   (Thread/sleep 30000)
                   (binding [*out* *err*]
                     (println "UI test timed out; thread dump:")
                     (doseq [info (.dumpAllThreads (ManagementFactory/getThreadMXBean) true true)]
                       (println (str info))))
                   (System/exit 2)))
    (.setDaemon true)
    (.start)))

(defn- exercise-resize! [sketch]
  (let [frame (.getFrame (.getNative (.getSurface sketch)))]
    (doseq [i (range 60)]
      (EventQueue/invokeAndWait
        (fn [] (.setSize frame (+ 1000 (* 7 (mod i 30)))
                               (+ 600 (* 3 (mod i 30))))))
      (Thread/sleep 20))))

(defn -main [& args]
  (start-watchdog!)
  (try
    (let [architecture (core/load-architecture-edn (or (first args) "target/zhiying-architecture.edn"))
          output (.getAbsolutePath (io/file (or (second args) "target/ui-smoke.png")))
          capture? (atom false)
          captured (promise)
          draw @#'view/draw-scene]
      (with-redefs [view/draw-scene (fn [state]
                                    (draw state)
                                    (when (compare-and-set! capture? true false)
                                      (.save (applet/current-applet) output)
                                      (deliver captured true)))]
        (let [sketch (view/show! (:scene architecture)
                               {:architecture architecture :title "Window resize verification"})]
          (try
            (Thread/sleep 1000)
            (exercise-resize! sketch)
            (Thread/sleep 500)
            (let [before (.-frameCount sketch)]
              (reset! capture? true)
              (assert (= true (deref captured 5000 :timeout)) "Drawing stopped after resizing")
              (Thread/sleep 100)
              (assert (> (.-frameCount sketch) before) "Animation stopped")
              (assert (nil? (.findDeadlockedThreads (ManagementFactory/getThreadMXBean)))))
            (println "PASS: 60 resizes; frames" (.-frameCount sketch)
                     "display-scale" (text-rendering/display-scale)
                     "density" (.-pixelDensity sketch)
                     "logical" [(.-width sketch) (.-height sketch)]
                     "pixels" [(.-pixelWidth sketch) (.-pixelHeight sketch)]
                     "screenshot" output)
            (finally (.exit sketch))))))
    (System/exit 0)
    (catch Throwable ex
      (.printStackTrace ex)
      (System/exit 1))))
