;; 职责：回归接口契约说明到入口列表、方法图与当前入口切片的传递及泛型/重载边界。
(ns arch-view.entries.contract-docs-spec
  (:require [arch-view.entries.contract-fixture :as mock]
            [arch-view.entries.core :as entries]
            [arch-view.entries.chain :as chain]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.web :as web]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(defn- with-api
  ([files f] (with-api files mock/rules f))
  ([files rules f]
   (fixtures/with-project files rules
     (fn [root]
       (let [state (fixtures/workspace-of root)
             scanned (entries/scan state)
             by-label (fixtures/by-label scanned)
             graph (force (:method-graph (meta scanned)))]
         (f by-label graph state))))))

(defn- slice-for [by-label graph label]
  (chain/slice graph (by-label label) {:depth 6 :limit 60}))

(defn- root-node [slice] (some #(when (= (:root slice) (:id %)) %) (:nodes slice)))

(describe "RPC 接口说明传递"
  (it "接口注解保留业务名、摘要和契约锚点，根节点保留技术身份与实现锚点，普通中间实现回退接口说明"
    (with-api mock/files
      (fn [by-label graph state]
        (let [entry (by-label "mock.app.rec")
              result ((get web/routes ["GET" "/api/entry/chain"]) state {"id" (:id entry)})
              node (root-node result)
              middle (first (filter #(= "demo.RecService" (:type %)) (:nodes result)))]
          (should= "服务推荐Rpc" (:businessName entry))
          (should= "按用户画像推荐服务" (:summary entry))
          (should= "服务推荐Rpc" (:businessName node))
          (should= "按用户画像推荐服务" (:summary node))
          (should= "服务推荐。" (:methodSummary node))
          (should= "AppRecRpcImpl#appRec" (:label node))
          (should= "mock.app.rec" (:operation node))
          (should= "src/main/java/demo/AppRecRpc.java" (get-in node [:contractAnchor :path]))
          (should= "src/main/java/demo/AppRecRpcImpl.java" (get-in node [:implementationAnchor :path]))
          (should= (:anchor node) (:implementationAnchor node))
          (should= "demo.AppRecRpc" (get-in node [:docSource :type]))
          (should= "annotation" (get-in node [:docSource :kind]))
          (should (some #(= "contract" (:kind %)) (:sources entry)))
          (should= "根据推荐策略获取服务。" (:summary middle))
          (should= "demo.RecPort" (get-in middle [:docSource :type]))
          (should= "src/main/java/demo/RecService.java" (get-in middle [:implementationAnchor :path]))
          (should-be-nil (get-in graph [:nodes (:id node) :businessName]))))))

  (it "同一实现对应多个入口时使用各自实际命中的契约，交错读取不污染共享图"
    (with-api mock/files
      (fn [by-label graph _]
        (doseq [[label name summary type] [["mock.z" "Z业务" "Z入口说明" "demo.ZContract"]
                                          ["mock.a" "A业务" "A入口说明" "demo.AContract"]
                                          ["mock.z" "Z业务" "Z入口说明" "demo.ZContract"]]]
          (let [node (root-node (slice-for by-label graph label))]
            (should= name (:businessName node))
            (should= summary (:summary node))
            (should= type (get-in node [:docSource :type]))
            (should= type (get-in node [:contractSource :type]))))
        (let [shared (get-in graph [:nodes "demo.SharedImpl#shared()"])
              middle (some #(when (= "demo.SharedImpl" (:type %)) %) (:nodes (slice-for by-label graph "mock.middle")))]
          (should= "ambiguous" (:docStatus shared))
          (should-be-nil (:summary shared))
          (should= #{"demo.AContract" "demo.ZContract"} (set (map :type (:docCandidates shared))))
          (should= "ambiguous" (:docStatus middle))
          (should-be-nil (:summary middle))
          (should-be-nil (:businessName middle))))))

  (it "实现有效 Javadoc 覆盖继承文档，入口注解说明独立保留；inheritDoc 空正文仍会回退"
    (with-api (update mock/files "src/main/java/demo/AppRecRpcImpl.java"
                      #(str/replace % "@Override" "/** 实现专属说明。 */ @Override"))
      (fn [by-label graph _]
        (let [node (root-node (slice-for by-label graph "mock.app.rec"))]
          (should= "服务推荐Rpc" (:businessName node))
          (should= "实现专属说明。" (:summary node))
          (should= "按用户画像推荐服务" (:entrySummary node))
          (should= "demo.AppRecRpcImpl" (get-in node [:docSource :type]))
          (should= "demo.AppRecRpc" (get-in node [:contractSource :type]))))))

  (it "泛型经多级接口替换，只把注解与说明关联到正确 String 重载"
    (with-api mock/files
      (fn [by-label graph _]
        (let [entry (by-label "mock.generic")
              node (root-node (slice-for by-label graph "mock.generic"))]
          (should= ["String"] (get-in entry [:handler :params]))
          (should= ["java.lang.String"] (:params node))
          (should= "泛型契约业务说明。" (:summary node))
          (should= "demo.GenericPort" (get-in node [:docSource :type]))
          (should-be-nil (get-in graph [:nodes "demo.GenericImpl#accept(java.lang.Integer)" :summary]))))))

  (it "同简单名同参数数且实现位于同一行的重载，分别定位完整参数类型与正确接口说明"
    (with-api mock/files
      (fn [by-label graph _]
        (doseq [[label param summary] [["mock.left" "left.Request" "左侧请求业务。"]
                                       ["mock.right" "right.Request" "右侧请求业务。"]]]
          (let [node (root-node (slice-for by-label graph label))]
            (should= [param] (:params node))
            (should= summary (:summary node))
            (should= [param] (get-in (by-label label) [:handler :signature])))))))

  (it "父类说明可回退，只有 value 且无文档时保留技术名和 operation"
    (with-api mock/files
      (fn [by-label graph _]
        (let [parent (root-node (slice-for by-label graph "mock.parent"))
              plain (root-node (slice-for by-label graph "mock.plain"))]
          (should= "父类模板说明。" (:summary parent))
          (should= "demo.Parent" (get-in parent [:docSource :type]))
          (should= "class" (get-in parent [:contractSource :declarationKind]))
          (should-be-nil (:summary plain))
          (should-be-nil (:businessName plain))
          (should= "PlainImpl#run" (:label plain))
          (should= "mock.plain" (:operation plain))))))

  (it "相同 operation 和实现通过不同规则暴露时保持独立入口 ID 与业务元数据"
    (with-api mock/files
      (update mock/rules :rules conj {:id "other-operation" :annotation "demo.OperationType" :as "kind:rpc"
                                     :channel "另一机制" :attrs {:value "operation" :desc "name" :name "summary"}})
      (fn [_ graph state]
        (let [found (filter #(= "mock.app.rec" (:label %)) (:entries (entries/scan state)))
              nodes (map #(root-node (chain/slice graph % {})) found)]
          (should= 2 (count found))
          (should= 2 (count (set (map :id found))))
          (should= #{"服务推荐Rpc" "按用户画像推荐服务"} (set (map :businessName nodes)))
          (should= #{"服务推荐Rpc" "按用户画像推荐服务"} (set (map :summary nodes)))))))

  (it "同一规则同一 operation 命中两个冲突契约时不任取来源，保留候选和明确警告"
    (with-api (update mock/files "src/main/java/demo/ZContract.java" #(str/replace % "mock.z" "mock.a"))
      (fn [by-label graph _]
        (let [entry (by-label "mock.a") node (root-node (slice-for by-label graph "mock.a"))]
          (should= "ambiguous" (:contractStatus entry))
          (should-contain "ambiguous-contract" (:warnings entry))
          (should= 2 (count (:contractCandidates entry)))
          (should= "ambiguous" (:contractStatus node))
          (should-be-nil (:contractAnchor node))
          (should-be-nil (:businessName node))
          (should-be-nil (:summary node))
          (should-be-nil (:docSource node))))))

  (it "多个接口说明一致时展示一致文本并保留所有来源，不伪造唯一契约锚点"
    (with-api (-> mock/files
                  (update "src/main/java/demo/AContract.java" #(str/replace % "A 契约说明。" "共同契约说明。"))
                  (update "src/main/java/demo/ZContract.java" #(str/replace % "Z 契约说明。" "共同契约说明。")))
      (fn [_ graph _]
        (let [node (get-in graph [:nodes "demo.SharedImpl#shared()"])]
          (should= "共同契约说明。" (:summary node))
          (should= "inherited" (:docStatus node))
          (should-be-nil (:docSource node))
          (should-be-nil (:contractAnchor node))
          (should= 2 (count (:docCandidates node)))))))

  (it "数组泛型与方法类型参数的前向界限正确替换，不把类型变量误当成同名业务类"
    (with-api (assoc mock/files
                "src/main/java/demo/V.java" (java "demo" "public class V {}")
                "src/main/java/demo/BoundedPort.java"
                (java "demo" "public interface BoundedPort { /** 前向泛型界限说明。 */ @OperationType(\"mock.bound\") <T extends V, V extends Number> void bounded(T value); }")
                "src/main/java/demo/BoundedImpl.java"
                (java "demo" "public class BoundedImpl implements BoundedPort { public void bounded(Number value) {} public void bounded(V value) {} }")
                "src/main/java/demo/ArrayPort.java"
                (java "demo" "public interface ArrayPort<T> { /** 数组泛型契约说明。 */ @OperationType(\"mock.array\") void array(T[] values); }")
                "src/main/java/demo/ArrayImpl.java"
                (java "demo" "public class ArrayImpl implements ArrayPort<String> { public void array(String[] values) {} }"))
      (fn [by-label graph _]
        (let [bounded (root-node (slice-for by-label graph "mock.bound"))
              array (root-node (slice-for by-label graph "mock.array"))]
          (should= ["java.lang.Number"] (:params bounded))
          (should= "前向泛型界限说明。" (:summary bounded))
          (should-be-nil (get-in graph [:nodes "demo.BoundedImpl#bounded(demo.V)" :summary]))
          (should= ["java.lang.String[]"] (:params array))
          (should= "数组泛型契约说明。" (:summary array))))))

  (it "静态同名方法不继承接口说明"
    (with-api (assoc mock/files
                "src/main/java/demo/StaticParent.java" (java "demo" "public class StaticParent { /** 静态父方法说明。 */ public static void helper(String s) {} }")
                "src/main/java/demo/StaticChild.java" (java "demo" "public class StaticChild extends StaticParent { public static void helper(String s) {} }"))
      (fn [_ graph _]
        (should-be-nil (get-in graph [:nodes "demo.StaticChild#helper(java.lang.String)" :summary]))))))
