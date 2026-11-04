;; 职责：验证网页入口接口：一次返回入口、分组与机制计数，随分析状态缓存，重新加载只重读规则；入口说明取 summary 与 Javadoc 首句。
;; 核心入口：Speclj 入口接口测试。

(ns arch-view.entries.web-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.core :as core]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.grow-fixture :as grow]
            [arch-view.entries.web :as web]
            [arch-view.web.model :as model]
            [clojure.java.io :as io]
            [speclj.core :refer :all]))

(defn- state-of [root]
  (model/create-state (core/load-architecture root {}) root {}))

(defn- call [state method path]
  ((get web/routes [method path]) state {}))

(describe "入口接口"
  (it "一次返回入口、规则状态、分组来源与机制计数；同一状态只计算一次"
    (fixtures/with-project grow/files grow/rules
      (fn [root]
        (let [state (state-of root)
              data (call state "GET" "/api/entries")]
          (should= 22 (count (:entries data)))
          (should= "java" (:language data))
          (should= "ok" (get-in data [:rules :status]))
          (should= "architecture" (get-in data [:grouping :default]))
          (should= [{:label "MQ" :rules ["sofamq"] :count 7} {:label "RPC-TR" :rules ["sofa-tr"] :count 7}] (take 2 (:channels data)))
          (should= 0 (get-in data [:summary :unclassified]))
          (should (identical? data (call state "GET" "/api/entries")))))))

  (it "改了规则文件后 reload 重算并替换缓存，不需要重新分析"
    (fixtures/with-project grow/files grow/rules
      (fn [root]
        (let [state (state-of root)
              before (call state "GET" "/api/entries")]
          (cli-support/write-json-file! (io/file root ".arch-view" "entry-rules.json") (dissoc grow/rules :aside))
          (should= before (call state "GET" "/api/entries"))
          (let [after (call state "POST" "/api/entries/reload")]
            (should= [] (keep :aside (:entries after)))
            (should= 2 (- (count (keep :aside (:entries before))) (count (keep :aside (:entries after)))))
            (should (identical? after (call state "GET" "/api/entries"))))))))

  (it "规则文件有错时仍返回内置规则识别的入口和错误信息"
    (fixtures/with-project grow/files "{\"version\": 2}"
      (fn [root]
        (let [data (call (state-of root) "GET" "/api/entries")]
          (should= "error" (get-in data [:rules :status]))
          (should-contain "version 必须为 1" (first (get-in data [:rules :errors])))
          (should (seq (:entries data))))))))

(describe "入口说明"
  (it "summary 属性优先，其次方法 Javadoc（实现类没有时取接口方法），最后取处理类 Javadoc 首句"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/d/OrderFacade.java"
                    (java "d" "public interface OrderFacade {\n"
                          "  /**\n   * 查询订单详情。包含明细。\n   * @param id 订单号\n   */\n  String get(Long id);\n"
                          "  /** Cancels the {@link Order order}. Second sentence. */\n  void cancel(Long id);\n}")
                    "src/main/java/d/OrderFacadeImpl.java"
                    (java "d" "import com.alipay.sofa.runtime.api.annotation.*;\n"
                          "/** 订单门面实现 */\n"
                          "@SofaService(interfaceType = OrderFacade.class, bindings = {@SofaServiceBinding(bindingType = \"bolt\")})\n"
                          "public class OrderFacadeImpl implements OrderFacade {\n"
                          "  public String get(Long id) { return \"\"; }\n  public void cancel(Long id) {}\n"
                          "  public void extra() {}\n}")
                    "src/main/java/d/Web.java"
                    (java "d" "import org.springframework.web.bind.annotation.*;\n"
                          "import io.swagger.v3.oas.annotations.Operation;\n"
                          "/** 网页接口。 */\n@RestController public class Web {\n"
                          "  /** 被 summary 覆盖 */\n  @Operation(summary = \"下单\") @PostMapping(\"/o\") public void o() {}\n"
                          "  @GetMapping(\"/p\") public void p() {}\n}")})
          summaries (into {} (map (juxt #(get-in % [:anchor :symbol]) :summary)) (:entries scanned))]
      (should= "查询订单详情。" (summaries "OrderFacadeImpl#get"))
      (should= "Cancels the order." (summaries "OrderFacadeImpl#cancel"))
      (should= "下单" (summaries "Web#o"))
      (should= "网页接口。" (summaries "Web#p")))))
