;; 职责：回归缺依赖调用的有界候选查询，以及可直接按文档归类时不建立方法图。
(ns arch-view.entries.performance-mock-spec
  (:require [arch-view.entries.chain :as chain]
            [arch-view.entries.exits :as exits]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.grow-fixture :as grow]
            [arch-view.entries.java-calls :as calls]
            [speclj.core :refer :all]))

(describe "方法图候选查询 mock 回归"
  (it "保留当前类型优先、父类回退、重载不确定性和参数数量约束，重复查询复用结果"
    (let [supers-count (atom 0)
          ctx {:supers (fn [type] (swap! supers-count inc) (if (= "p.Child" type) #{"p.Base"} #{}))}
          nodes [{:id "child-int" :type "p.Child" :method "handle" :params ["int"]}
                 {:id "child-string" :type "p.Child" :method "handle" :params ["String"]}
                 {:id "child-noargs" :type "p.Child" :method "handle" :params []}
                 {:id "base-handle" :type "p.Base" :method "handle" :params ["String"]}
                 {:id "base-run" :type "p.Base" :method "run" :params []}]
          nodes (concat nodes (for [i (range 2000)] {:id (str "other" i) :type (str "p.Other" i) :method "handle" :params ["String"]}))
          calls (for [i (range 50) method ["handle" "run"]]
                  {:from "entry" :receiver "p.Child" :method method :args (if (= "handle" method) [nil] [])
                   :anchor {:startLine i} :written (str method "()")})
          graph (with-redefs [exits/matcher (fn [_ _] (constantly nil))]
                  (chain/build ctx {:nodes (into {} (map (juxt :id identity)) nodes) :calls calls}))]
      (should= #{"child-int" "child-string" "base-run"} (set (map :to (:edges graph))))
      (should= 150 (count (:edges graph)))
      (should= 2 @supers-count)
      (should (every? #(= "dashed" (:style %)) (:edges graph)))
      (should (some #(re-find #"未解析重载（2）" (:reason %)) (:edges graph)))
      (should= "child-string"
               (chain/handler-root graph {:handler {:type "p.Child" :method "handle" :params ["String"]}})))))

(describe "按架构直接归类的延迟分析 mock 回归"
  (it "入口自身已绑定业务域时不建立全仓方法图，并保留原有分组"
    (with-redefs [calls/analyze (fn [& _] (throw (Exception. "不应建立方法图")))]
      (let [scanned (fixtures/scan-project grow/files (assoc grow/rules :entryLayers []))
            rec (first (filter #(= "RecFacade#call" (:label %)) (:entries scanned)))]
        (should= 22 (count (:entries scanned)))
        (should= [{:id "rpc" :label "RPC 与开放接口"}] (get-in rec [:domains :architecture]))
        (should-not (realized? (:method-graph (meta scanned)))))))

  (it "入口需向下追踪时仍建立方法图，按不同匿名回调进入各自业务域"
    (let [files (assoc grow/files
                  "app/rpc/src/main/java/com/co/grow/rpc/template/Callback.java"
                  (java "com.co.grow.rpc.template" "public abstract class Callback { public abstract void execute(); }")
                  "app/rpc/src/main/java/com/co/grow/rpc/template/ExecuteTemplate.java"
                  (java "com.co.grow.rpc.template" "public class ExecuteTemplate { public static void execute(Callback c) { c.execute(); } }")
                  "app/rpc/src/main/java/com/co/grow/rpc/rec/CallbackRpc.java"
                  (java "com.co.grow.rpc.rec" "import com.alipay.mobilegw.OperationType;\n"
                        "import com.co.grow.rpc.template.*; import com.co.grow.service.rec.RecService; import com.co.grow.service.benefit.BenefitService;\n"
                        "public class CallbackRpc { RecService rec; BenefitService benefit;\n"
                        "  @OperationType(\"mock.rec\") public void appRec() { ExecuteTemplate.execute(new Callback() { public void execute() { rec.run(); } }); }\n"
                        "  @OperationType(\"mock.benefit\") public void issue() { ExecuteTemplate.execute(new Callback() { public void execute() { benefit.run(); } }); }\n}"))
          scanned (fixtures/scan-project files grow/rules)
          by-label (fixtures/by-label scanned)]
      (should (realized? (:method-graph (meta scanned))))
      (should= [{:id "rec" :label "推荐与卡片"}] (get-in (by-label "mock.rec") [:domains :architecture]))
      (should= [{:id "benefit" :label "会员权益与交易"}] (get-in (by-label "mock.benefit") [:domains :architecture])))))
