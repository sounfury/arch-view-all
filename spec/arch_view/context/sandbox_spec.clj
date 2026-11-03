;; 职责：验证沙箱公开契约、真实命令包装，以及不越过符号链接的清理。
;; 核心入口：只读沙箱 Speclj 示例；所有写入拒绝测试只使用临时文件。

(ns arch-view.context.sandbox-spec
  (:require [arch-view.context.sandbox :as sandbox]
            [clojure.java.io :as io]
            [speclj.core :refer :all])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util.concurrent TimeUnit]))

(defn- run-command [session argv]
  (let [builder (ProcessBuilder. ^java.util.List ((:wrap session) argv))]
    (.putAll (.environment builder) (:env session))
    (let [process (.start builder)]
      (.close (.getOutputStream process))
      (try
        (when-not (.waitFor process 15 TimeUnit/SECONDS)
          (throw (ex-info "测试命令超时" {})))
        {:exit (.exitValue process)
         :out (slurp (.getInputStream process))
         :err (slurp (.getErrorStream process))}
        (finally (.destroyForcibly process))))))

(defn- temp-directory []
  (.toRealPath (Files/createTempDirectory "sandbox-spec-" (make-array FileAttribute 0))
               (make-array LinkOption 0)))

(describe "强制只读文件系统沙箱"
  (it "创建独立、owner-only 临时目录，暴露环境变量和独立清理函数"
    (let [first-session (sandbox/open! {})
          second-session (sandbox/open! {})
          directory (:directory first-session)
          path (.getAbsolutePath directory)]
      (try
        (should (instance? java.io.File directory))
        (should (.isDirectory directory))
        (should-not= directory (:directory second-session))
        (should= {"TMPDIR" path "TMP" path "TEMP" path} (:env first-session))
        (should= "rwx------" (PosixFilePermissions/toString
                               (Files/getPosixFilePermissions (.toPath directory) (make-array LinkOption 0))))
        (should-contain (:kind first-session) #{"linux-landlock" "macos-sandbox"})
        (finally
          ((:cleanup first-session))
          ((:cleanup second-session))))
      (should-not (.exists directory))
      ((:cleanup first-session))))

  (it "Linux 包装使用隔离 Python 启动，不经 shell，参数原样保留"
    (with-redefs-fn {#'sandbox/platform (constantly :linux)}
      (fn []
        (let [session (sandbox/open! {:opts {:python "/configured/python3"}})
              argv ["agent" "两个 参数" "$(touch injected); echo hello"]
              wrapped ((:wrap session) argv)]
          (try
            (should= ["/configured/python3" "-I" "-B"] (subvec wrapped 0 3))
            (should (.isFile (io/file (nth wrapped 3))))
            (should= ["--scratch" (.getAbsolutePath (:directory session)) "--"]
                     (subvec wrapped 4 7))
            (should= argv (subvec wrapped 7))
            (should= "linux-landlock" (:kind session))
            (finally ((:cleanup session))))))))

  (it "默认 Python 为 python3，配置为空时失败关闭"
    (with-redefs-fn {#'sandbox/platform (constantly :linux)}
      (fn []
        (let [session (sandbox/open! {})]
          (try
            (should= "python3" (first ((:wrap session) ["agent"])))
            (finally ((:cleanup session)))))
        (try
          (sandbox/open! {:opts {:python " "}})
          (should-fail "应拒绝空 Python 命令")
          (catch clojure.lang.ExceptionInfo ex
            (should= 503 (:status (ex-data ex))))))))

  (it "真实包装命令可读宿主、写 scratch，并禁止覆盖宿主临时文件"
    (let [host-directory (temp-directory)
          host (.toFile (.resolve host-directory "source.txt"))
          session (sandbox/open! {})]
      (try
        (spit host "host original")
        (let [code (str "import errno,os,sys\nfrom pathlib import Path\n"
                        "host=Path(sys.argv[1]); scratch=Path(os.environ['TMPDIR'])\n"
                        "assert host.read_text() == 'host original'\n"
                        "(scratch/'result.txt').write_text('scratch writable')\n"
                        "try:\n host.write_text('escaped')\n"
                        "except OSError as error:\n assert error.errno in (errno.EPERM,errno.EACCES)\n"
                        "else:\n raise AssertionError('宿主写入成功')\n"
                        "print('sandbox works')\n")
              result (run-command session ["python3" "-I" "-B" "-c" code (.getAbsolutePath host)])]
          (should= 0 (:exit result))
          (should= "sandbox works\n" (:out result))
          (should= "scratch writable" (slurp (io/file (:directory session) "result.txt")))
          (should= "host original" (slurp host)))
        (finally
          ((:cleanup session))
          (Files/deleteIfExists (.toPath host))
          (Files/deleteIfExists host-directory)))))

  (it "清理嵌套目录与链接，但保留链接指向的目录和文件"
    (let [outside (temp-directory)
          target (.resolve outside "keep.txt")
          session (sandbox/open! {})
          directory (.toPath (:directory session))]
      (try
        (spit (.toFile target) "keep outside")
        (Files/createSymbolicLink (.resolve directory "directory-link") outside (make-array FileAttribute 0))
        (Files/createSymbolicLink (.resolve directory "file-link") target (make-array FileAttribute 0))
        (Files/createSymbolicLink (.resolve directory "broken-link") (.resolve outside "missing")
                                  (make-array FileAttribute 0))
        (io/make-parents (io/file (:directory session) "nested/deep/file"))
        (spit (io/file (:directory session) "nested/deep/file") "scratch")
        ((:cleanup session))
        (should-not (.exists (:directory session)))
        (should= "keep outside" (slurp (.toFile target)))
        (should (Files/isDirectory outside (make-array LinkOption 0)))
        (finally
          ((:cleanup session))
          (Files/deleteIfExists target)
          (Files/deleteIfExists outside)))))

  (it "scratch 本身若被换成符号链接，清理仍只删除链接"
    (let [outside (temp-directory)
          target (.resolve outside "keep.txt")
          session (sandbox/open! {})
          directory (.toPath (:directory session))]
      (try
        (spit (.toFile target) "keep outside")
        ((:cleanup session))
        (Files/createSymbolicLink directory outside (make-array FileAttribute 0))
        ((:cleanup session))
        (should= "keep outside" (slurp (.toFile target)))
        (should-not (Files/exists directory (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
        (finally
          ((:cleanup session))
          (Files/deleteIfExists target)
          (Files/deleteIfExists outside)))))

  (it "macOS profile 安全转义引号、反斜杠，并拒绝控制字符"
    (should= "\"/tmp/a\\\"b\\\\c\"" (#'sandbox/profile-string "/tmp/a\"b\\c"))
    (should-throw clojure.lang.ExceptionInfo (#'sandbox/profile-string "/tmp/a\n(injected)")))

  (it "Windows 与其它平台明确返回 503，不提供普通执行回退"
    (with-redefs-fn {#'sandbox/platform (constantly :unsupported)}
      (fn []
        (try
          (sandbox/open! {})
          (should-fail "应拒绝不支持的平台")
          (catch clojure.lang.ExceptionInfo ex
            (should= 503 (:status (ex-data ex)))
            (should-contain "暂无系统级文件隔离" (.getMessage ex))))))))
