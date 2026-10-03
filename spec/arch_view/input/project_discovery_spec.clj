;; 测试：检查项目语言识别和 Java 源码目录发现。

(ns arch-view.input.project-discovery-spec
  (:require [arch-view.input.project-discovery :as sut]
            [arch-view.input.languages :as languages]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(defn- write-source [root path contents]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file contents)))

(describe "project discovery"
  (with root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-discovery" (make-array java.nio.file.attribute.FileAttribute 0))))
  (after (doseq [file (reverse (file-seq @root))] (.delete file)))

  (it "detects each supported language from source files"
    (doseq [[extension language] [["cljc" :clojure] ["py" :python] ["java" :java]]]
      (write-source @root (str "custom/code." extension) "")
      (should= language (sut/detect-language (str @root) nil))
      (.delete (io/file @root (str "custom/code." extension)))))

  (it "prefers root build markers over helper scripts"
    (write-source @root "pom.xml" "<project/>")
    (write-source @root "tools/helper.py" "")
    (should= :java (sut/detect-language (str @root) nil))
    (should= :python (sut/detect-language (str @root) ["tools"])))

  (it "uses source counts when markers are absent or ambiguous"
    (write-source @root "src/A.java" "")
    (write-source @root "src/B.java" "")
    (write-source @root "tool.py" "")
    (should= :java (sut/detect-language (str @root) nil))
    (write-source @root "pom.xml" "")
    (write-source @root "pyproject.toml" "")
    (should= :java (sut/detect-language (str @root) nil)))

  (it "asks for explicit language on empty or tied projects"
    (should-throw clojure.lang.ExceptionInfo (sut/detect-language (str @root) nil))
    (write-source @root "a.py" "")
    (write-source @root "A.java" "")
    (should-throw clojure.lang.ExceptionInfo (sut/detect-language (str @root) nil))
    (should= :java (languages/resolve-language (str @root) nil :java)))

  (it "ignores generated, hidden and dependency directories"
    (doseq [dir ["target" "build" ".git" "node_modules" "venv"]]
      (write-source @root (str dir "/src/main/java/Noise.java") ""))
    (write-source @root "app/src/main/java/App.java" "")
    (should= ["app/src/main/java"] (sut/java-source-paths (str @root)))
    (write-source @root "pom.xml" "")
    (should= :java (sut/detect-language (str @root) nil)))

  (it "finds root and nested Java modules while excluding test roots"
    (doseq [path ["src/main/java/A.java" "app/api/src/main/java/B.java"
                  "app/web/src/main/java/C.java" "app/web/src/test/java/Test.java"]]
      (write-source @root path ""))
    (should= ["app/api/src/main/java" "app/web/src/main/java" "src/main/java"]
             (sut/java-source-paths (str @root))))

  (it "retains fallback roots for nonstandard Java projects"
    (should= ["."] (sut/java-source-paths (str @root)))
    (write-source @root "src/App.java" "")
    (should= ["src"] (sut/java-source-paths (str @root)))))
