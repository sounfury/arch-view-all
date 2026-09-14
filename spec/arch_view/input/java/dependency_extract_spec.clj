(ns arch-view.input.java.dependency-extract-spec
  (:require [arch-view.input.java.dependency-extract :as sut]
            [arch-view.input.languages :as languages]
            [clojure.java.io :as io]
            [arch-view.domain.architecture-projection :as projection]
            [speclj.core :refer :all]))

(defn with-project [files f]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "arch-view java 中文" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file content :encoding "UTF-8")))
      (f (.getAbsolutePath root))
      (finally (doseq [file (reverse (file-seq root))] (.delete file))))))

(describe "Java architecture adapter"
  (it "Given Java types, when analyzed, then resolves usage and abstract modules without external jars"
    (with-project
      {"src/p/Port.java" "package p; public interface Port {}"
       "src/p/Base.java" "package p; public abstract class Base {}"
       "src/p/Impl.java" "package p; import missing.External; public class Impl extends Base implements Port { External unknown; }"
       "src/q/App.java" "package q; import p.*; class App { Port p = new Impl(); }"}
      (fn [root]
        (let [g (sut/build-module-graph root ["src"])]
          (should= #{"p.Port" "p.Base" "p.Impl" "q.App"} (:nodes g))
          (should= #{"p.Port" "p.Base"} (:abstract-modules g))
          (should= #{{:from "p.Impl" :to "p.Base"} {:from "p.Impl" :to "p.Port"}
                     {:from "q.App" :to "p.Port"} {:from "q.App" :to "p.Impl"}} (:edges g))
          (should= (.getCanonicalPath (io/file root "src/p/Impl.java"))
                   (get-in g [:module->source-file "p.Impl"]))))))

  (it "Given nested types and static imports, when used, then maps dependencies to top-level owners"
    (with-project
      {"src/a/Owner.java" "package a; public class Owner { public static class Inner {} public static int value() { return 1; } }"
       "src/b/App.java" "package b; import a.Owner.Inner; import static a.Owner.value; class App { Inner x; int n = value(); } class Extra { a.Owner x; }"}
      (fn [root]
        (let [g (sut/build-module-graph root ["src"])]
          (should= #{"a.Owner" "b.App" "b.Extra"} (:nodes g))
          (should= #{{:from "b.App" :to "a.Owner"} {:from "b.Extra" :to "a.Owner"}} (:edges g))))))

  (it "Given annotations, generics, records and enums, when parsed, then keeps actual type references"
    (with-project
      {"src/p/Mark.java" "package p; public @interface Mark {}"
       "src/p/Value.java" "package p; public record Value(String text) {}"
       "src/p/Choice.java" "package p; enum Choice { YES; Value value; }"
       "src/p/App.java" "package p; @Mark class App<T extends Value> { java.util.List<Value> values; Choice choice; }"}
      (fn [root]
        (let [g (sut/build-module-graph root ["src"])]
          (should= #{"p.Mark"} (:abstract-modules g))
          (should= #{{:from "p.Choice" :to "p.Value"} {:from "p.App" :to "p.Mark"}
                     {:from "p.App" :to "p.Value"} {:from "p.App" :to "p.Choice"}} (:edges g))))))

  (it "Given misleading text and variable names, when analyzed, then avoids false dependencies"
    (with-project
      {"src/A.java" "class A { String text = \"new B()\"; /* B b; */ int B = 1; int n = B; }"
       "src/B.java" "class B {}"}
      (fn [root] (should= #{} (:edges (sut/build-module-graph root ["src"]))))))

  (it "Given overlapping roots and build output, when scanned, then deduplicates and ignores generated files"
    (with-project
      {"src/A.java" "class A {}" "src/build/Bad.java" "invalid"
       "src/.hidden/Bad.java" "invalid"
       "src/module-info.java" "module ignored {}"
       "src/package-info.java" "package ignored;" "other/B.java" "class B { A a; }"}
      (fn [root]
        (should= #{"A" "B"} (:nodes (sut/build-module-graph root ["src" (.getAbsolutePath (io/file root "src")) "other"]))))))

  (it "Given duplicate qualified types, when scanned, then reports the conflicting source files"
    (with-project {"one/A.java" "package p; class A {}" "two/A.java" "package p; class A {}"}
      (fn [root] (should-throw clojure.lang.ExceptionInfo #"Duplicate Java type"
                              (sut/build-module-graph root ["one" "two"])))))

  (it "Given malformed Java, when parsed, then fails with the source path"
    (with-project {"src/Broken.java" "class Broken {"}
      (fn [root] (should-throw clojure.lang.ExceptionInfo #"Broken.java"
                              (sut/build-module-graph root ["src"])))))

  (it "Given no Java files, when scanned, then returns an empty graph"
    (with-project {} (fn [root] (should= #{} (:nodes (sut/build-module-graph root ["src"]))))))

  (it "Given Maven or Gradle layout, when selecting defaults, then scans production Java only"
    (with-project {"src/main/java/A.java" "class A {}" "src/test/java/B.java" "class B {}"}
      (fn [root]
        (should= :java (languages/language-key "java"))
        (should= ["src/main/java"] (languages/default-source-paths root :java)))))

  (it "Given a flat project, when selecting defaults, then uses its root"
    (with-project {} (fn [root] (should= ["."] (languages/default-source-paths root :java))))))

(describe "Java package projection"
  (it "Given cyclic Java types, when drilling into their package, then preserves leaf sources and edges"
    (with-project {"src/p/A.java" "package p; class A { B b; }"
                   "src/p/B.java" "package p; class B { A a; }"}
      (fn [root]
        (let [graph (sut/build-module-graph root ["src"])
              architecture {:graph graph :guidance {:namespace-root-depth 0}
                            :classified-edges (map #(assoc % :type :direct) (:edges graph))}
              top (projection/view-architecture architecture [])
              nested (projection/view-architecture architecture ["p"])]
          (should= #{"p"} (get-in top [:graph :nodes]))
          (should= #{"A" "B"} (get-in nested [:graph :nodes]))
          (should= true (get-in nested [:module->leaf? "A"]))
          (should= true (.endsWith (get-in nested [:module->source-file "A"]) "A.java"))
          (should= 2 (count (get-in nested [:graph :edges]))))))))

(describe "Java reference precision"
  (it "Given unused imports, when analyzed, then creates no dependency"
    (with-project {"src/p/A.java" "package p; import q.B; class A {}"
                   "src/q/B.java" "package q; public class B {}"}
      (fn [root] (should= #{} (:edges (sut/build-module-graph root ["src"]))))))

  (it "Given a method reference and throws clause, when analyzed, then resolves their owners"
    (with-project {"src/p/A.java" "package p; class A { Runnable r = B::run; void f() throws Problem {} }"
                   "src/p/B.java" "package p; class B { static void run() {} }"
                   "src/p/Problem.java" "package p; class Problem extends Exception {}"}
      (fn [root]
        (should= #{{:from "p.A" :to "p.B"} {:from "p.A" :to "p.Problem"}}
                 (:edges (sut/build-module-graph root ["src"])))))))
