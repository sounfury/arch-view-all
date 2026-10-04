;; 测试：检查外部 JSON 的读取，以及 crap 复杂度结果按源码文件汇总、开关和失败处理。

(ns arch-view.web.complexity-spec
  (:require [arch-view.web.complexity :as sut]
            [arch-view.web.json :as json]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(def ^:private windows? (.startsWith (.toLowerCase (System/getProperty "os.name")) "windows"))

(defn- temp-project []
  (let [root (.toFile (java.nio.file.Files/createTempDirectory "arch-crap" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (io/make-parents (io/file root "src" "demo" "core.py"))
    (spit (io/file root "src" "demo" "core.py") "def pick(x):\n    return x\n")
    root))

(defn- fake-crap
  "生成只输出固定 JSON 的假 crap 命令，避免测试依赖本机安装。"
  [root output]
  (let [report (io/file root "report.json")
        script (io/file root (if windows? "crap.cmd" "crap"))]
    (spit report output :encoding "UTF-8")
    (spit script (if windows? (str "@type \"" report "\"\r\n") (str "#!/bin/sh\ncat '" report "'\n")))
    (.setExecutable script true)
    (str script)))

(defn- wait-until-done [state]
  (loop [n 0]
    (when (and (= "running" (get-in @state [:complexity :status])) (< n 200))
      (Thread/sleep 50) (recur (inc n))))
  @state)

(describe "JSON reader"
  (it "reads nested data, escapes and numbers without evaluating"
    (should= {"a" [1 -25.0 true nil "x\n中"] "b" {}}
             (json/read-json "{\"a\": [1, -2.5e1, true, null, \"x\\n\\u4e2d\"], \"b\": {}}")))

  (it "rejects malformed and trailing content"
    (should-throw clojure.lang.ExceptionInfo (json/read-json "{\"a\": }"))
    (should-throw clojure.lang.ExceptionInfo (json/read-json "[1] 2"))))

(describe "crap complexity"
  (it "indexes functions by canonical source file and aggregates per module"
    (let [root (temp-project)
          command (fake-crap root (str "{\"projects\": [{\"project_path\": " (json/write-json (str root)) ", \"entries\": ["
                                       "{\"symbol\": \"pick\", \"file\": \"src/demo/core.py\", \"line\": 1, \"complexity\": 3},"
                                       "{\"symbol\": \"old\", \"location\": \"src/demo/core.py:9\", \"complexity\": 12}]}]}"))
          state (wait-until-done (sut/start! (atom {}) (.toPath (.getCanonicalFile root)) {:crap-command command}))
          file (.getCanonicalPath (io/file root "src" "demo" "core.py"))
          metrics (sut/node-metrics (:complexity state) [["demo.core" file] ["demo.other" nil]])]
      (should= {:status "ready" :limit 10 :max 12 :total 15 :count 2} (sut/summary (:complexity state)))
      (should= [12 3] (map :complexity (:functions metrics)))
      (should= {:symbol "old" :line 9 :complexity 12 :module "demo.core"} (first (:functions metrics)))
      (should-be-nil (sut/node-metrics (:complexity state) [["demo.other" nil]]))))

  (it "uses the max_complexity from the project's crap.toml gate"
    (let [root (temp-project)]
      (spit (io/file root "crap.toml") "[project]\nmax_complexity = 3\n\n[gate]\nmode = \"complexity\"\nmax_complexity = 12\n")
      (should= 12 (:limit (sut/summary (:complexity @(sut/start! (atom {}) (.toPath root) {:crap "off"})))))))

  (it "stays out of the way when disabled and reports failures instead of throwing"
    (let [root (.toPath (temp-project))]
      (should= {:status "unavailable" :limit 10} (sut/summary (:complexity @(sut/start! (atom {}) root {:crap "off"}))))
      (let [state (wait-until-done (sut/start! (atom {}) root {:crap-command (fake-crap (.toFile root) "not json")}))]
        (should= "failed" (get-in state [:complexity :status]))
        (should-contain "crap 未返回有效结果" (get-in state [:complexity :error])))))

  (it "only accepts auto or off as the mode"
    (should= "off" (sut/parse-mode " OFF "))
    (should-throw clojure.lang.ExceptionInfo (sut/parse-mode "yes"))))
