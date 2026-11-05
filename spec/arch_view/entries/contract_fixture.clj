;; 职责：BUG-08 的独立 RPC 契约夹具，供 API 回归与真实浏览器验收共用。
(ns arch-view.entries.contract-fixture
  (:require [arch-view.entries.fixtures :refer [java]]))

(def rules
  {:version 1 :rules [{:id "operation" :annotation "demo.OperationType" :as "kind:rpc" :channel "网关 RPC"
                      :attrs {:value "operation" :name "name" :desc "summary"}}]})

(def files
  {"src/main/java/demo/OperationType.java"
   (java "demo" "public @interface OperationType { String value(); String name() default \"\"; String desc() default \"\"; }")
   "src/main/java/demo/BaseRecRequest.java"
   (java "demo" "public class BaseRecRequest {}")
   "src/main/java/demo/AppRecRpc.java"
   (java "demo" "import java.util.Map;\npublic interface AppRecRpc {\n"
         "  /** 服务推荐。 */\n"
         "  @OperationType(value=\"mock.app.rec\", name=\"服务推荐Rpc\", desc=\"按用户画像推荐服务\")\n"
         "  Map<String, String> appRec(BaseRecRequest request);\n}")
   "src/main/java/demo/AppRecRpcImpl.java"
   (java "demo" "import java.util.Map;\npublic class AppRecRpcImpl implements AppRecRpc {\n"
         "  RecService service;\n  @Override public Map<String, String> appRec(BaseRecRequest request) { return service.recommend(request); }\n}")
   "src/main/java/demo/RecPort.java"
   (java "demo" "import java.util.Map; public interface RecPort {\n"
         "  /** 根据推荐策略获取服务。 */\n  Map<String, String> recommend(BaseRecRequest request);\n}")
   "src/main/java/demo/RecService.java"
   (java "demo" "import java.util.Map; public class RecService implements RecPort {\n"
         "  /** {@inheritDoc} */\n  @Override public Map<String, String> recommend(BaseRecRequest request) { return Map.of(); }\n}")
   "src/main/java/demo/AContract.java"
   (java "demo" "public interface AContract {\n  /** A 契约说明。 */\n"
         "  @OperationType(value=\"mock.a\", name=\"A业务\", desc=\"A入口说明\") void shared();\n}")
   "src/main/java/demo/ZContract.java"
   (java "demo" "public interface ZContract {\n  /** Z 契约说明。 */\n"
         "  @OperationType(value=\"mock.z\", name=\"Z业务\", desc=\"Z入口说明\") void shared();\n}")
   "src/main/java/demo/SharedImpl.java"
   (java "demo" "public class SharedImpl implements ZContract, AContract { public void shared() {} }")
   "src/main/java/demo/Caller.java"
   (java "demo" "public class Caller { SharedImpl shared; @OperationType(\"mock.middle\") public void call() { shared.shared(); } }")
   "src/main/java/demo/GenericPort.java"
   (java "demo" "public interface GenericPort<T> {\n  /** 泛型契约业务说明。 */\n"
         "  @OperationType(\"mock.generic\") void accept(T request);\n}")
   "src/main/java/demo/MiddlePort.java"
   (java "demo" "public interface MiddlePort<U> extends GenericPort<U> {}")
   "src/main/java/demo/StringPort.java"
   (java "demo" "public interface StringPort extends MiddlePort<String> {}")
   "src/main/java/demo/GenericImpl.java"
   (java "demo" "public class GenericImpl implements StringPort { public void accept(String request) {} public void accept(Integer request) {} }")
   "src/main/java/left/Request.java" (java "left" "public class Request {}")
   "src/main/java/right/Request.java" (java "right" "public class Request {}")
   "src/main/java/demo/OverloadedPort.java"
   (java "demo" "public interface OverloadedPort {\n"
         "  /** 左侧请求业务。 */ @OperationType(\"mock.left\") void send(left.Request request);\n"
         "  /** 右侧请求业务。 */ @OperationType(\"mock.right\") void send(right.Request request);\n}")
   "src/main/java/demo/OverloadedImpl.java"
   (java "demo" "public class OverloadedImpl implements OverloadedPort { public void send(left.Request request) {} public void send(right.Request request) {} }")
   "src/main/java/demo/Parent.java"
   (java "demo" "public class Parent { /** 父类模板说明。 */ public void inherited(String request) {} }")
   "src/main/java/demo/Child.java"
   (java "demo" "public class Child extends Parent { @OperationType(\"mock.parent\") @Override public void inherited(String request) {} }")
   "src/main/java/demo/PlainPort.java"
   (java "demo" "public interface PlainPort { @OperationType(\"mock.plain\") void run(); }")
   "src/main/java/demo/PlainImpl.java"
   (java "demo" "public class PlainImpl implements PlainPort { public void run() {} }")})
