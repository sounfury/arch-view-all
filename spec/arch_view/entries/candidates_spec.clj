;; 职责：验证入口候选统计：排除已覆盖与常见非入口注解，给出层级、属性、取值分布、外部父类型和 XML 声明。
;; 核心入口：Speclj 候选测试。

(ns arch-view.entries.candidates-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.entries.cli :as cli]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [speclj.core :refer :all]))

(def ^:private files
  {"app/src/main/java/f/FacadeImpl.java"
   (java "f" "import com.alipay.sofa.runtime.api.annotation.*;\nimport lombok.Data;\n"
         "@Data @SofaService(interfaceType = Facade.class, bindings = {@SofaServiceBinding(bindingType = \"tr\")})\n"
         "public class FacadeImpl implements Facade { public String save() { return \"\"; } }")
   "app/src/main/java/f/LocalImpl.java"
   (java "f" "import com.alipay.sofa.runtime.api.annotation.*;\n"
         "@SofaService(interfaceType = Facade.class, bindings = {@SofaServiceBinding(bindingType = \"jvm\")})\n"
         "public class LocalImpl implements Facade { public String save() { return \"\"; } }")
   "app/src/main/java/f/OtherImpl.java"
   (java "f" "import com.alipay.sofa.runtime.api.annotation.*;\n"
         "@SofaService(interfaceType = Facade.class, bindings = {@SofaServiceBinding(bindingType = \"tr\")})\n"
         "public class OtherImpl implements Facade { public String save() { return \"\"; } }")
   "app/src/main/java/f/Facade.java" (java "f" "public interface Facade { String save(); }")
   "app/src/main/java/g/TaskRpc.java"
   (java "g" "import com.co.gw.OperationType;\n"
         "public interface TaskRpc { @OperationType(\"a.list\") String list(); @OperationType(\"a.add\") String add(); }")
   "app/src/main/java/w/Ctl.java"
   (java "w" "import com.co.web.*;\nimport org.springframework.beans.factory.annotation.Autowired;\n"
         "@XxApi(\"/app\") public class Ctl { @Autowired Object x; @XxGet(value = \"/a\", desc = \"说明\") public void a() {}\n"
         "  @XxGet(\"/b\") public void b() {} }")
   "app/src/main/java/j/Job.java"
   (java "j" "public class Job implements com.co.job.ISimpleJobHandler { public void handle() {} }")
   "app/src/main/resources/spring/mq.xml"
   "<beans xmlns:sofa=\"x\" xmlns:context=\"y\">\n  <context:component-scan base-package=\"a\"/>\n  <sofa:consumer group=\"g\"><sofa:listener ref=\"l\"/></sofa:consumer>\n</beans>"})

(defn- run [rules]
  (fixtures/with-project files rules
    (fn [root]
      (with-redefs [cli-support/load-workspace (constantly (fixtures/workspace-of root))]
        (let [json (cli-support/read-json-text (with-out-str (cli/run! ["candidates" "--format" "json"])))
              md (with-out-str (cli/run! ["candidates"]))]
          {:json json :md md})))))

(describe "entries candidates"
  (with result (run nil))

  (it "方法级注解排在类级前，接口方法单独计数，并给出属性与同类注解"
    (let [annotations (get-in @result [:json :annotations])
          by-fqn (into {} (map (juxt :fqn identity)) annotations)]
      (should= ["com.co.gw.OperationType" "com.co.web.XxGet" "com.co.web.XxApi"] (map :fqn annotations))
      (should= {:method 2} (:levels (by-fqn "com.co.web.XxGet")))
      (should= {:interfaceMethod 2} (:levels (by-fqn "com.co.gw.OperationType")))
      (should= ["desc" "value"] (:attrs (by-fqn "com.co.web.XxGet")))
      (should= [{:annotation "com.co.web.XxApi" :count 1}] (:with (by-fqn "com.co.web.XxGet")))
      (should= "app/src/main/java/w/Ctl.java:4" (:sample (by-fqn "com.co.web.XxGet")))))

  (it "排除 Lombok、Spring 装配注解和内置规则已覆盖的注解；内置规则只收的 SOFA 仍不重复列出"
    (let [fqns (set (map :fqn (get-in @result [:json :annotations])))]
      (should-not-contain "lombok.Data" fqns)
      (should-not-contain "org.springframework.beans.factory.annotation.Autowired" fqns)
      (should-not-contain "com.alipay.sofa.runtime.api.annotation.SofaService" fqns)
      (should-contain "com.co.web.XxApi" fqns)))

  (it "列出外部父类型与 XML 声明元素"
    (should= [{:kind "supertype" :fqn "com.co.job.ISimpleJobHandler" :count 1 :sample "app/src/main/java/j/Job.java:2"}]
             (get-in @result [:json :supertypes]))
    (should= [{:element "sofa:consumer" :count 1 :sample "app/src/main/resources/spring/mq.xml:3"}]
             (get-in @result [:json :xml])))

  (it "Markdown 列出取值分布，写了规则后对应候选消失"
    (should-contain "2. 注解 `com.co.web.XxGet` 方法 ×2 · 示例 app/src/main/java/w/Ctl.java:4" (:md @result))
    (should-not-contain "取值 value" (:md @result))
    (should-contain "同时出现：com.co.web.XxApi（类 ×1）" (:md @result))
    (let [after (run {:version 1 :rules [{:id "xx-get" :annotation "com.co.web.XxGet" :as "spring:GetMapping"}
                                         {:id "job" :implements "com.co.job.ISimpleJobHandler" :as "kind:schedule"}]})]
      (should-not-contain "com.co.web.XxGet" (set (map :fqn (get-in after [:json :annotations]))))
      (should= [] (get-in after [:json :supertypes])))))

(describe "取值分布"
  (it "嵌套注解的低基数属性给出分布"
    (let [result (run {:version 1 :disableBuiltin ["sofa-service"] :rules []})
          sofa (first (filter #(= "com.alipay.sofa.runtime.api.annotation.SofaService" (:fqn %))
                              (get-in result [:json :annotations])))]
      (should= [{:path "bindings.bindingType" :values {:jvm 1 :tr 2}}] (:values sofa)))))
