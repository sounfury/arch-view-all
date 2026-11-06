;; 职责：将 Java 类型映射到架构图中的顶层模块，供入口和方法图共用。
(ns arch-view.entries.module
  (:require [clojure.string :as str]))

(defn for-type [{:keys [file-modules]} type]
  (let [top (or (last (:outers type)) (:fqn type))
        modules (map key (get file-modules (:file type)))]
    (or (first (filter #(or (= top %) (str/ends-with? % (str "." top))) modules))
        (first (sort modules))
        top)))
