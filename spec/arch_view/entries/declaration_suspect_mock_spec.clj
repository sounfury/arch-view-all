;; 职责：用独立 Java mock 回归外部继承 RPC 声明、疑似入口证据分级与完整检查报告。
(ns arch-view.entries.declaration-suspect-mock-spec
  (:require [arch-view.entries.chain :as chain]
            [arch-view.entries.cli :as cli]
            [arch-view.entries.core :as entries]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.java-calls :as java-calls]
            [arch-view.entries.report :as report]
            [arch-view.entries.web :as web]
            [speclj.core :refer :all]))

(def ^:private rpc-rules
  {:version 1
   :rules [{:id "rpc-provider" :annotation "external.RpcProvider" :on "class"
            :as "kind:rpc" :channel "RPC-SPI" :attrs {:interfaceType "service"}
            :methods {:interfaceFromAttr "interfaceType"}}]})

(defn- connector [body]
  (java "p" "import external.RpcProvider;\nimport external.AbstractServiceConnector;\n"
        "import external.ServiceConnector;\n"
        "@RpcProvider(interfaceType=ServiceConnector.class)\n"
        "public class Connector extends AbstractServiceConnector {\n" body "\n}"))

(def ^:private hook-files
  {"src/main/java/p/Connector.java"
   (connector "@Override protected void doProcess() {}\n@Override protected String serviceConnectorDesc() { return \"feeds\"; }")})

(defn- notes-by-code [checked level]
  (into {} (map (juxt :code identity)) (get checked level)))

(describe "外部继承 RPC 声明 mock 回归"
  (it "保留类级发布事实、类锚点与未解析状态，绝不把 protected hook 选成公开入口"
    (let [scanned (fixtures/scan-project hook-files rpc-rules)
          entry (first (:entries scanned))
          warnings (notes-by-code (report/check scanned) :warnings)]
      (should= 1 (count (:entries scanned)))
      (should= "rpc" (:kind entry))
      (should= "RPC-SPI" (get-in entry [:channel :label]))
      (should= {:status "unresolved" :reason "external-inherited-method"
                :externalTypes ["external.AbstractServiceConnector"]} (:declaration entry))
      (should= {:type "p.Connector" :method "<unresolved>" :params []} (:handler entry))
      (should= {:path "src/main/java/p/Connector.java" :startLine 5 :endLine 9
                :symbol "Connector（入口方法未解析）"} (:anchor entry))
      (should= "external.ServiceConnector" (get-in entry [:attrs "service"]))
      (should= ["unresolved-declaration"] (:warnings entry))
      (should= [] (:suspects scanned))
      (should= 1 (get-in warnings ["unresolved-declaration" :count]))
      (should-not (get warnings "zero-hit"))
      (should-be-nil (chain/handler-root {:nodes {"hook" {:id "hook" :type "p.Connector"
                                                        :method "doProcess" :params []}}} entry))))

  (it "未解析声明的网页链路返回专门原因，并且不为其建立全仓方法图"
    (fixtures/with-project hook-files rpc-rules
      (fn [root]
        (let [state (fixtures/workspace-of root)
              entry (first (:entries (entries/scan state)))
              handler (get web/routes ["GET" "/api/entry/chain"])]
          (with-redefs [java-calls/analyze (fn [& _] (throw (Exception. "不应建立方法图")))]
            (let [error (try (handler state {"id" (:id entry)}) nil
                             (catch clojure.lang.ExceptionInfo ex ex))]
              (should= 422 (:status (ex-data error)))
              (should= "external-inherited-method" (:reason (ex-data error)))
              (should-contain "外部依赖" (.getMessage error))))))))

  (it "有可定位公有实现时仍生成普通方法入口"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Connector.java" (connector "@Override public void process() {}\nprotected void doProcess() {}")}
                   rpc-rules)
          entry (first (:entries scanned))]
      (should= 1 (count (:entries scanned)))
      (should= "process" (get-in entry [:handler :method]))
      (should-be-nil (:declaration entry))
      (should= [] (:warnings entry))))

  (it "没有外部继承时不虚构外部方法声明"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Empty.java"
                    (java "p" "import external.RpcProvider;\n@RpcProvider public class Empty {}")}
                   rpc-rules)]
      (should= [] (:entries scanned))
      (should= "framework" (:evidence (first (:suspects scanned))))))

  (it "when 不满足的发布注解不生成声明或框架候选"
    (let [scanned (fixtures/scan-project hook-files
                                        (assoc-in rpc-rules [:rules 0 :when] {:enabled true}))]
      (should= [] (:entries scanned))
      (should= [] (:suspects scanned))))

  (it "延迟方法图构建进度只写 stderr"
    (fixtures/with-project hook-files rpc-rules
      (fn [root]
        (let [out (java.io.StringWriter.) err (java.io.StringWriter.)]
          (with-redefs [java-calls/analyze (fn [& _] {})
                        chain/build (fn [& _] {:nodes {}})]
            (binding [*out* out *err* err]
              (let [scanned (entries/scan (fixtures/workspace-of root))]
                (force (:method-graph (meta scanned))))))
          (should= "" (str out))
          (should-contain "开始构建方法图" (str err))
          (should-contain "方法图构建完成" (str err)))))))

(describe "suspect 证据分级与完整报告 mock 回归"
  (with scanned
    (fixtures/scan-project
     (merge
      (into {} (for [i (range 28)]
                 [(str "src/main/java/p/Internal" i "Handler.java")
                  (java "p" "public class Internal" i "Handler { public void handle() {} }")]))
      (into {} (for [i (range 12)]
                 [(str "src/main/java/p/Unmapped" i ".java")
                  (java "p" "import org.springframework.web.bind.annotation.RestController;\n"
                        "@RestController public class Unmapped" i " { public void helper() {} }")]))
      {"src/main/java/p/Plain.java" (java "p" "public class Plain {}")
       "src/main/java/p/AbstractHandler.java" (java "p" "public abstract class AbstractHandler {}")
       "src/main/java/p/ActiveController.java"
       (java "p" "import org.springframework.web.bind.annotation.*;\n"
             "@RestController public class ActiveController { @GetMapping(\"/ok\") public void ok() {} }")})))

  (it "名称候选保留低证据等级，框架证据不限于类名后缀，已识别入口不再列入"
    (let [groups (group-by :evidence (:suspects @scanned))]
      (should= 28 (count (groups "name")))
      (should= 12 (count (groups "framework")))
      (should (every? #(= ["Handler"] (:basis %)) (groups "name")))
      (should (every? #(= ["spring-mvc"] (:basis %)) (groups "framework")))
      (should= 40 (count (:suspects @scanned)))))

  (it "名称推断只列信息；JSON 数据与 Markdown 均完整提供全部候选及证据"
    (let [checked (report/check @scanned)
          warnings (notes-by-code checked :warnings)
          infos (notes-by-code checked :infos)
          markdown (#'cli/check-markdown checked)]
      (should= 12 (get-in warnings ["suspect" :count]))
      (should= 12 (count (get-in warnings ["suspect" :items])))
      (should= 28 (get-in infos ["suspect-name" :count]))
      (should= 28 (count (get-in infos ["suspect-name" :items])))
      (should= 12 (get-in checked [:summary :warnings]))
      (doseq [{:keys [type basis]} (:suspects @scanned)]
        (should-contain type markdown)
        (should-contain (first basis) markdown))))

  (it "仅命名候选不让 strict 检查失败"
    (let [checked (report/check (fixtures/scan-project
                                 {"src/main/java/p/InternalHandler.java"
                                  (java "p" "public class InternalHandler {}")}))]
      (should= [] (:warnings checked))
      (should= 0 (get-in checked [:summary :warnings]))
      (should= 0 (#'cli/exit-code checked {:strict true})))))
