;; 职责：打开独立的源码查看窗口，显示带行号的代码和适用的语法着色。
;; 核心入口：打开源码窗口（open-source-file-window!）。

;; mutation-tested: 2026-03-08
(ns arch-view.render.ui.swing.source-window
  (:require [clojure.java.io :as io]
            [arch-view.render.ui.util.source-html :as source-html])
  (:import [javax.swing JFrame JEditorPane JScrollPane SwingUtilities]
           [java.awt Dimension]))

(defn html-escape
  [s]
  (source-html/html-escape s))

(defn colorize-clojure-html
  [source]
  (source-html/colorize-clojure-html source))

(defn expand-tabs
  [line]
  (source-html/expand-tabs line))

(defn source-lines->html
  [source]
  (source-html/source-lines->html source))

(defn source->html
  [title source]
  (source-html/source->html title source))

(defn- schedule-ui!
  [f]
  (SwingUtilities/invokeLater f))

(defn- existing-source-file?
  [source-file]
  (and source-file (.exists (io/file source-file))))

(defn- build-source-frame!
  [source-file content]
  (let [title (.getName (io/file source-file))
        frame (JFrame. (str "Source: " title))
        editor (JEditorPane. "text/html" (source->html source-file content))
        scroll (JScrollPane. editor)]
    (.setEditable editor false)
    (.setCaretPosition editor 0)
    (.setPreferredSize scroll (Dimension. 980 760))
    (.add (.getContentPane frame) scroll)
    (.pack frame)
    (.setLocationByPlatform frame true)
    (.setVisible frame true)))

(defn open-source-file-window!
  [source-file]
  (when (existing-source-file? source-file)
    (let [content (slurp source-file)
          show-frame (fn []
                       (build-source-frame! source-file content))]
      (schedule-ui! show-frame))))
