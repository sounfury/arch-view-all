;; 测试：检查项目语言、主源码目录与构建模块发现及稳定命名。

(ns arch-view.input.project-discovery-spec
  (:require [arch-view.input.project-discovery :as sut]
            [arch-view.input.languages :as languages]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(defn- write-source [root path contents]
  (let [file (io/file root path)]
    (.mkdirs (.getParentFile file))
    (spit file contents)))

(describe "构建模块发现"
  (with root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-build-modules" (make-array java.nio.file.attribute.FileAttribute 0))))
  (after (doseq [file (reverse (file-seq @root))] (.delete file)))

  (it "只收录带构建文件和主源码目录的模块，并包含项目根模块"
    (doseq [path ["pom.xml" "src/main/java/A.java"
                  "api/build.gradle" "api/src/main/java/B.java"
                  "tests/pom.xml" "tests/src/test/java/Test.java"
                  "plain/src/main/java/C.java" "aggregate/pom.xml"]]
      (write-source @root path ""))
    (should= [{:name (.getName @root) :build-tool "maven" :directory "." :source-paths ["src/main/java"]}
              {:name "api" :build-tool "gradle" :directory "api" :source-paths ["api/src/main/java"]}]
             (sut/build-modules (str @root) :java)))

  (it "忽略隐藏目录、构建输出、依赖和 buildSrc"
    (doseq [dir [".hidden" "target" "build" "buildSrc" "node_modules" "vendor"]]
      (write-source @root (str dir "/pom.xml") "")
      (write-source @root (str dir "/src/main/java/A.java") ""))
    (should= [] (sut/build-modules (str @root) :java)))

  (it "Kotlin 同时收录 Kotlin 和 Java 主源码目录，但不收录测试目录"
    (doseq [path ["lib/build.gradle.kts" "lib/src/main/kotlin/A.kt"
                  "lib/src/main/java/B.java" "lib/src/test/kotlin/Test.kt"
                  "java/pom.xml" "java/src/main/java/C.java"]]
      (write-source @root path ""))
    (should= [{:name "java" :build-tool "maven" :directory "java" :source-paths ["java/src/main/java"]}
              {:name "lib" :build-tool "gradle" :directory "lib" :source-paths ["lib/src/main/kotlin" "lib/src/main/java"]}]
             (sut/build-modules (str @root) :kotlin))
    (should= [] (sut/build-modules (str @root) :clojure)))

  (it "模块名转义句点，重名目录改用相对路径，结果不依赖创建顺序"
    (doseq [dir ["tools/core" "services/core" "user.api"]]
      (write-source @root (str dir "/build.gradle") "")
      (write-source @root (str dir "/src/main/java/A.java") ""))
    (should= ["services:core" "tools:core" "user_api"]
             (mapv :name (sut/build-modules (str @root) :java)))
    (let [reordered (io/file @root ".reordered")]
      (doseq [dir ["user.api" "services/core" "tools/core"]]
        (write-source reordered (str dir "/build.gradle") "")
        (write-source reordered (str dir "/src/main/java/A.java") ""))
      (should= (sut/build-modules (str @root) :java)
               (sut/build-modules (str reordered) :java))))

  (it "根模块名转义句点，与子目录重名时仍生成唯一名称"
    (let [project (io/file @root "root.name")]
      (doseq [path ["pom.xml" "src/main/java/A.java"
                    "root_name/pom.xml" "root_name/src/main/java/B.java"]]
        (write-source project path ""))
      (should= ["root_name" "root_name:2"]
               (mapv :name (sut/build-modules (str project) :java)))))

  (it "转义后仍重名时按排序追加序号"
    (doseq [dir ["a.b/core" "a_b/core" "other"]]
      (write-source @root (str dir "/pom.xml") "")
      (write-source @root (str dir "/src/main/java/A.java") ""))
    (should= ["a_b:core" "a_b:core:2" "other"]
             (mapv :name (sut/build-modules (str @root) :java)))))

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
