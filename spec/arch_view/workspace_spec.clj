;; 职责：验证共享工作区的目标解析、源码映射、路径规范化和 Git 提交读取。
;; 核心入口：Speclj 工作区规格。

(ns arch-view.workspace-spec
  (:require [arch-view.workspace :as workspace]
            [arch-view.notes.test-support :as fixture]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [speclj.core :refer :all]))

(describe "共享工作区"
  (with ws (fixture/create-workspace))
  (after (fixture/delete-workspace! @ws))

  (it "create 规范根目录并保存 opts，data-dir 不创建目录"
    (let [created (workspace/create (:architecture @ws) (str (:root @ws) "/src/..") {:language "clojure"})]
      (should= (:root @ws) (:root created))
      (should= {:language "clojure"} (:opts created))
      (should= (io/file (.toFile (:root @ws)) ".arch-view") (workspace/data-dir created))
      (should-not (.exists (workspace/data-dir created)))))

  (it "相对和绝对路径映射到同一模块，并规范 dot 段"
    (should= "src/web.clj" (workspace/relative @ws "src/../src/web.clj"))
    (should= "app.web.model" (workspace/module-for-path @ws "src/web.clj"))
    (should= "app.web.model" (workspace/module-for-path @ws (str (:root @ws) "/src/web.clj")))
    (should= "src/web.clj" (workspace/source-file @ws "app.web.model"))
    (should-be-nil (workspace/source-file @ws "unknown"))
    (should-be-nil (workspace/module-for-path @ws nil)))

  (it "完整模块优先，其次源码文件，再次按分段匹配包"
    (should= {:kind :module :target "app.web.model" :modules ["app.web.model"]}
             (workspace/resolve-target @ws " app.web.model "))
    (should= {:kind :file :target "app.web.model" :path "src/web.clj" :modules ["app.web.model"]}
             (workspace/resolve-target @ws "src/web.clj"))
    (should= {:kind :package :target "app.web" :modules ["app.web.model" "app.web.routes" "app.web.sub.api"]}
             (workspace/resolve-target @ws "app.web.")))

  (it "构建模块名称可以作为包，未知目标附最多十条已排序候选"
    (let [changed (assoc-in @ws [:architecture :graph :nodes]
                            (set (map #(str "backend.com.demo.Type" %) (range 12))))]
      (should= :package (:kind (workspace/resolve-target changed "backend")))
      (let [data (fixture/error-data #(workspace/resolve-target changed "Type"))]
        (should= 404 (:status data))
        (should= 10 (count (:candidates data)))
        (should= (sort (:candidates data)) (:candidates data)))))

  (it "空目标返回 400，未知目标返回 404 和候选"
    (should= 400 (:status (fixture/error-data #(workspace/resolve-target @ws " ")))))
  (it "未知目标返回 404 和候选"
    (should= {:status 404 :candidates ["app.web.model"]}
             (fixture/error-data #(workspace/resolve-target @ws "model"))))

  (it "Git 成功时去除换行，非仓库或缺少 Git 时返回 nil"
    (with-redefs [shell/sh (fn [& _] {:exit 0 :out "abcdef\n"})]
      (should= "abcdef" (workspace/git-head @ws)))
    (with-redefs [shell/sh (fn [& _] {:exit 128 :out ""})]
      (should-be-nil (workspace/git-head @ws)))
    (with-redefs [shell/sh (fn [& _] (throw (java.io.IOException. "没有 git")))]
      (should-be-nil (workspace/git-head @ws)))))
