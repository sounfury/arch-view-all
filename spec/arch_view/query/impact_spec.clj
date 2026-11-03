;; 职责：在临时 Git 仓库验证工作区、暂存区、分叉基准及项目子目录的改动影响。
;; 核心入口：Speclj Git 影响分析用例。

(ns arch-view.query.impact-spec
  (:require [arch-view.query.fixtures :as fixtures]
            [arch-view.query.impact :as impact]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(defn- git! [root & args]
  (let [{:keys [exit out err]} (apply shell/sh (concat ["git"] args [:dir (.toFile ^java.nio.file.Path root)]))]
    (when-not (zero? exit) (throw (ex-info err {:args args})))
    (str/trim out)))

(defn- initialize! [root]
  (git! root "init" "--quiet")
  (git! root "config" "user.email" "query-test@example.invalid")
  (git! root "config" "user.name" "查询测试")
  (git! root "config" "commit.gpgsign" "false"))

(defn- commit! [root]
  (git! root "add" ".")
  (git! root "commit" "--quiet" "-m" "查询测试基线"))

(describe "Git 改动影响"
  (with root (fixtures/temporary-root))
  (with ws (fixtures/workspace @root))
  (before @ws (initialize! @root) (commit! @root))
  (after (fixtures/delete-tree! @root))

  (it "默认包含暂存、未暂存、删除与未跟踪文件，并汇总上游和相关环"
    (spit (io/file (.toFile @root) "src/port.clj") ";; 已修改\n")
    (git! @root "add" "src/port.clj")
    (spit (io/file (.toFile @root) "src/a.clj") ";; 未暂存\n")
    (.delete (io/file (.toFile @root) "src/orphan.clj"))
    (spit (io/file (.toFile @root) "new config\nline.txt") "新文件")
    (let [data (impact/impact @ws {})]
      (should= ["app.web.a" "lib.port" "orphan"] (:modules data))
      (should= ["new config\nline.txt"] (:unmapped data))
      (should= [["app.web.b" "client" "lib.impl"] ["app.webby"]]
               (mapv #(mapv :module (:items %)) (:upstream data)))
      (should= [1 2] (mapv :distance (:upstream data)))
      (should= 4 (:upstream-count data))
      (should= [["lib.impl" "lib.port"]] (mapv :modules (:cycles data)))))

  (it "staged 仅列暂存区，不包含随后未暂存的修改或未跟踪文件"
    (spit (io/file (.toFile @root) "src/a.clj") "暂存")
    (git! @root "add" "src/a.clj")
    (spit (io/file (.toFile @root) "src/b.clj") "工作区")
    (spit (io/file (.toFile @root) "loose.txt") "未跟踪")
    (should= ["src/a.clj"] (mapv :path (:files (impact/impact @ws {:staged true})))))

  (it "since 从分叉点包含已提交、工作区和未跟踪改动"
    (git! @root "branch" "reference")
    (spit (io/file (.toFile @root) "src/a.clj") "已提交")
    (commit! @root)
    (spit (io/file (.toFile @root) "src/b.clj") "工作区")
    (spit (io/file (.toFile @root) "loose.txt") "未跟踪")
    (let [data (impact/impact @ws {:since "reference"})]
      (should= (git! @root "merge-base" "reference" "HEAD") (:base data))
      (should= ["loose.txt" "src/a.clj" "src/b.clj"] (mapv :path (:files data)))))

  (it "since 即使工作区恢复到分叉点内容，也保留相对 HEAD 的改动"
    (let [original (slurp (io/file (.toFile @root) "src/a.clj"))]
      (git! @root "branch" "reference")
      (spit (io/file (.toFile @root) "src/a.clj") "已提交")
      (commit! @root)
      (spit (io/file (.toFile @root) "src/a.clj") original)
      (should= ["app.web.a"] (:modules (impact/impact @ws {:since "reference"})))))

  (it "无改动时输出空集合"
    (let [data (impact/impact @ws {})]
      (should= [] (:files data))
      (should= [] (:upstream data))
      (should= [] (:cycles data))))

  (it "引用错误和互斥参数明确报错"
    (should-throw clojure.lang.ExceptionInfo (impact/impact @ws {:since "unknown-ref"}))
    (should-throw clojure.lang.ExceptionInfo (impact/impact @ws {:since "--help"}))
    (should-throw clojure.lang.ExceptionInfo "--since 与 --staged 不能同时使用：请选择工作区比较或仅暂存区"
                  (impact/impact @ws {:since "HEAD" :staged true}))))

(describe "Git 路径与错误处理"
  (with root (fixtures/temporary-root))
  (after (fixtures/delete-tree! @root))

  (it "仓库子目录的源码按项目相对路径映射，仓库其他文件单独列出"
    (let [project (.resolve @root "nested")
          ws (fixtures/workspace project)]
      (initialize! @root)
      (spit (io/file (.toFile @root) "outside.txt") "基线")
      (commit! @root)
      (spit (io/file (.toFile project) "src/a.clj") "项目改动")
      (spit (io/file (.toFile project) "new.txt") "项目新增")
      (spit (io/file (.toFile @root) "outside.txt") "仓库其他改动")
      (spit (io/file (.toFile @root) "outside-new.txt") "仓库新增")
      (git! @root "config" "diff.relative" "true")
      (let [data (impact/impact ws {})]
        (should= ["app.web.a"] (:modules data))
        (should= ["../outside-new.txt" "../outside.txt" "new.txt"] (:unmapped data))
        (should= "src/a.clj" (:path (first (filter :module (:files data))))))))

  (it "没有 HEAD 的新仓库仍可查询暂存区"
    (let [ws (fixtures/workspace @root)]
      (initialize! @root)
      (git! @root "add" "src/a.clj")
      (should= ["app.web.a"] (:modules (impact/impact ws {:staged true})))
      (should-throw clojure.lang.ExceptionInfo (impact/impact ws {}))))

  (it "非 Git 仓库给出清楚错误"
    (should-throw clojure.lang.ExceptionInfo #"请确认项目位于 Git 仓库内"
                  (impact/impact (fixtures/workspace @root) {})))

  (it "找不到 Git 命令给出安装提示"
    (with-redefs [shell/sh (fn [& _] (throw (java.io.IOException. "无法启动")))]
      (should-throw clojure.lang.ExceptionInfo "无法执行 git：请安装 Git 并确保命令位于 PATH 中"
                    (impact/impact {:root @root} {})))))
