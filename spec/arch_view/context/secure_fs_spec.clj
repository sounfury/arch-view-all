;; 职责：验证无 SecureDirectoryStream 时仍拒绝符号链接、只删除链接本身，以及清理失败不覆盖原异常。

(ns arch-view.context.secure-fs-spec
  (:require [arch-view.context.sandbox :as sandbox]
            [arch-view.context.secure-fs :as sfs]
            [clojure.java.io :as io]
            [speclj.core :refer :all])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(def ^:private nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
(def ^:private attrs
  (into-array FileAttribute [(PosixFilePermissions/asFileAttribute
                              (PosixFilePermissions/fromString "rwx------"))]))

(defn- temp-directory []
  (.toRealPath (Files/createTempDirectory "secure-fs-spec-" (make-array FileAttribute 0))
               (make-array LinkOption 0)))

(describe "安全目录操作"
  (it "清理失败挂到原异常上，不覆盖原因；成功结果不被清理失败盖住"
    (try
      (sfs/with-cleanup #(throw (Exception. "cleanup"))
        #(throw (ex-info "original" {:status 409})))
      (should-fail "应抛出原始异常")
      (catch clojure.lang.ExceptionInfo ex
        (should= "original" (.getMessage ex))
        (should= 409 (:status (ex-data ex)))
        (should= "cleanup" (.getMessage (aget (.getSuppressed ex) 0)))))
    (should= :ok (sfs/with-cleanup #(throw (Exception. "cleanup")) (constantly :ok))))

  (it "路径回退时拒绝经符号链接进入目录"
    (let [root (temp-directory)
          real (Files/createDirectory (.resolve root "real") attrs)
          link (Files/createSymbolicLink (.resolve root "link") real (make-array FileAttribute 0))]
      (try
        (binding [sfs/*force-path-ops* true]
          (try
            (sfs/with-directory link false (constantly :ok))
            (should-fail "应拒绝符号链接")
            (catch clojure.lang.ExceptionInfo ex
              (should= 502 (:status (ex-data ex)))
              (should-contain "符号链接" (.getMessage ex)))))
        (finally
          (Files/deleteIfExists link)
          (Files/deleteIfExists real)
          (Files/deleteIfExists root)))))

  (it "无安全目录句柄时清理嵌套目录与链接，保留链接目标"
    (let [outside (temp-directory)
          target (.resolve outside "keep.txt")
          session (binding [sfs/*force-path-ops* true] (sandbox/open! {}))
          directory (.toPath (:directory session))]
      (try
        (spit (.toFile target) "keep outside")
        (Files/createSymbolicLink (.resolve directory "directory-link") outside (make-array FileAttribute 0))
        (Files/createSymbolicLink (.resolve directory "file-link") target (make-array FileAttribute 0))
        (io/make-parents (io/file (:directory session) "nested/deep/file"))
        (spit (io/file (:directory session) "nested/deep/file") "scratch")
        (binding [sfs/*force-path-ops* true] ((:cleanup session)))
        (should-not (.exists (:directory session)))
        (should= "keep outside" (slurp (.toFile target)))
        (should (Files/isDirectory outside (make-array LinkOption 0)))
        (finally
          (binding [sfs/*force-path-ops* true] ((:cleanup session)))
          (Files/deleteIfExists target)
          (Files/deleteIfExists outside)))))

  (it "无安全目录句柄时 scratch 被换成符号链接，清理仍只删除链接"
    (let [outside (temp-directory)
          target (.resolve outside "keep.txt")
          session (binding [sfs/*force-path-ops* true] (sandbox/open! {}))
          directory (.toPath (:directory session))]
      (try
        (spit (.toFile target) "keep outside")
        (binding [sfs/*force-path-ops* true] ((:cleanup session)))
        (Files/createSymbolicLink directory outside (make-array FileAttribute 0))
        (binding [sfs/*force-path-ops* true] ((:cleanup session)))
        (should= "keep outside" (slurp (.toFile target)))
        (should-not (Files/exists directory nofollow))
        (finally
          (binding [sfs/*force-path-ops* true] ((:cleanup session)))
          (Files/deleteIfExists target)
          (Files/deleteIfExists outside))))))
