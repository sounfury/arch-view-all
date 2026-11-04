;; 职责：验证 Java 语法解析的类型全名解析规则与注解属性静态求值。
;; 核心入口：Speclj 解析测试。

(ns arch-view.entries.java-source-spec
  (:require [arch-view.entries.fixtures :as fixtures]
            [arch-view.entries.java-source :as sut]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(defn- index-of [files]
  (fixtures/with-project files
    (fn [root] (sut/scan-files (map str (filter #(.endsWith (str %) ".java") (file-seq (io/file root))))))))

(defn- attrs-of [index type-name]
  (let [type (get-in index [:types type-name])]
    (update-vals (sut/annotation-attrs index type (first (:annotations type))) sut/display-value)))

(defn- resolved [index type-name]
  (let [type (get-in index [:types type-name])]
    (sut/annotation-type index type (first (:annotations type)))))

(describe "Java 类型全名解析"
  (it "按单类型导入、同文件、同包、java.lang、通配导入的顺序解析"
    (let [index (index-of {"src/a/Local.java" "package a; public @interface Local {}"
                           "src/a/One.java" "package a; import x.y.Ann; @Ann class One {}"
                           "src/a/Two.java" "package a; @Local class Two {}"
                           "src/a/Three.java" "package a; import x.y.*; @Deprecated class Three {}"
                           "src/a/Four.java" "package a; import x.y.*; @Ext class Four {}"
                           "src/a/Five.java" "package a; import x.y.*; import p.q.*; @Ext class Five {}"
                           "src/a/Six.java" "package a; @x.y.Ann class Six {}"})]
      (should= {:fqn "x.y.Ann"} (resolved index "a.One"))
      (should= {:fqn "a.Local"} (resolved index "a.Two"))
      (should= {:fqn "java.lang.Deprecated"} (resolved index "a.Three"))
      (should= {:fqn "x.y.Ext"} (resolved index "a.Four"))
      (should= {:candidates ["x.y.Ext" "p.q.Ext"]} (resolved index "a.Five"))
      (should= {:fqn "x.y.Ann"} (resolved index "a.Six"))))

  (it "Outer.Inner 写法先解析外层类型"
    (let [index (index-of {"src/a/Outer.java" "package a; public class Outer { public @interface In {} }"
                           "src/b/Use.java" "package b; import a.Outer; @Outer.In class Use {}"})]
      (should= {:fqn "a.Outer.In"} (resolved index "b.Use")))))

(describe "注解属性静态求值"
  (it "求值字面量、数组、跨类常量、静态导入、拼接、类字面量与嵌套注解"
    (let [index (index-of
                 {"src/c/Paths.java" "package c; public interface Paths { String BASE = \"/api\"; String ORDER = BASE + \"/orders\"; }"
                  "src/c/More.java" "package c; public class More { public static final String SUB = Paths.BASE + \"/sub\"; public static final int N = 3; }"
                  "src/d/Use.java"
                  (str "package d; import c.Paths; import c.More; import static c.Paths.BASE; import x.Bind;\n"
                       "@x.Ann(value = Paths.ORDER, more = {More.SUB, BASE + \"/x\"}, n = More.N, flag = true,\n"
                       "  type = Paths.class, bindings = {@Bind(kind = \"tr\")}, verb = RequestMethod.PUT, call = foo())\n"
                       "class Use {}")})]
      (should= {"value" "/api/orders" "more" ["/api/sub" "/api/x"] "n" 3 "flag" true
                "type" "c.Paths" "bindings" ["@x.Bind"] "verb" "RequestMethod.PUT" "call" "foo()"}
               (attrs-of index "d.Use"))
      (let [type (get-in index [:types "d.Use"])
            raw (sut/annotation-attrs index type (first (:annotations type)))]
        (should= "PUT" (get-in raw ["verb" :name]))
        (should= "tr" (get-in raw ["bindings" 0 :attrs "kind"]))
        (should (sut/unresolved? (get raw "call"))))))

  (it "省略属性名视为 value，类内常量与循环引用不会死循环"
    (let [index (index-of {"src/e/Loop.java"
                           (str "package e; @x.Ann(Loop.A) class Loop { static final String A = B; static final String B = A;\n"
                                "  static final String OK = \"ok\"; @x.Ann(OK) void m() {} }")})
          type (get-in index [:types "e.Loop"])
          method (first (:methods type))]
      (should (sut/unresolved? (get (sut/annotation-attrs index type (first (:annotations type))) "value")))
      (should= {"value" "ok"} (sut/annotation-attrs index type (first (:annotations method)))))))
