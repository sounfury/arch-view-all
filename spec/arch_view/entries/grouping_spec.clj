;; 职责：验证入口业务分组：同构夹具上的架构文档归属验收、接入层判定、规则 domains、注解与推断来源、旁置分组和分组报告。
;; 核心入口：Speclj 业务分组测试。

(ns arch-view.entries.grouping-spec
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.entries.cli :as cli]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.grow-fixture :as grow]
            [arch-view.entries.report :as report]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(defn- attribution [scanned source]
  (into {} (for [entry (:entries scanned)]
             [(str (:label entry) " @ " (get-in entry [:anchor :symbol]))
              (or (:aside entry) (vec (map :label (get-in entry [:domains source]))))])))

(defn- by-handler [scanned source]
  (reduce (fn [acc entry]
            (assoc acc (first (str/split (get-in entry [:anchor :symbol]) #"#"))
                   (or (:aside entry) (mapv :label (get-in entry [:domains source])))))
          {} (:entries scanned)))

(defn- run-cli [files rules args]
  (fixtures/with-project files rules
    (fn [root]
      (with-redefs [cli-support/load-workspace (constantly (fixtures/workspace-of root))]
        (with-out-str (cli/run! args))))))

(def ^:private mixed-facade
  {"app/rpc/src/main/java/com/co/grow/rpc/aq/MixFacade.java"
   (java "com.co.grow.rpc.aq" "public interface MixFacade { String call(); }")
   "app/rpc/src/main/java/com/co/grow/rpc/aq/MixFacadeImpl.java"
   (java "com.co.grow.rpc.aq" "import com.alipay.sofa.runtime.api.annotation.*;\n"
         "import com.co.grow.service.aq.TaskService;\nimport com.co.grow.service.eventcenter.EventService;\n"
         "@SofaService(interfaceType = MixFacade.class, bindings = {@SofaServiceBinding(bindingType = \"tr\")})\n"
         "public class MixFacadeImpl implements MixFacade { TaskService a; EventService b;\n"
         "  public String call() { a.run(); b.run(); return \"\"; } }")})

(describe "架构文档分组（同构夹具验收）"
  (with scanned (fixtures/scan-project grow/files grow/rules))

  (it "§12.2 每行归属正确：向下进入、按实际子系统不按包名、处理类自身在业务子系统、唯一实现、多跳、基础设施与旁置"
    (let [handlers (by-handler @scanned :architecture)]
      (should= ["事件中心"] (handlers "UserAtomEventFacadeImpl"))
      (should= ["事件中心"] (handlers "EventCenterDataFacadeImpl"))
      (should= ["事件中心"] (handlers "UserTaskStatusUpdateConsumer"))
      (should= ["阿福增长活动"] (handlers "TaskRpcImpl"))
      (should= ["会员权益与交易"] (handlers "SendPrizeMessageHandler"))
      (should= ["会员权益与交易"] (handlers "MsgBrokerPublishHealthGiftPackImpl"))
      (should= ["事件中心"] (handlers "EventCenterUserTriggerConsumer"))
      (should= ["事件中心"] (handlers "EventCenterStoreOnlyConsumer"))
      (should= ["阿福增长活动"] (handlers "UserGrowthCoreConsumer"))
      (should= ["主动关怀"] (handlers "CareActionConsumer"))
      (should= ["基础设施/其它"] (handlers "MedContentSyncConsumerV2"))
      (should= "测试/示例" (handlers "RpcDemoImpl"))
      (should= "测试/示例" (handlers "BusinessTimeTestRpcImpl"))
      (should= ["阿福增长活动"] (handlers "ChallengeV4WeightLossTestFacadeImpl"))
      (should= ["主动关怀"] (handlers "ActiveCareV2TestFacadeImpl"))
      (should= ["推荐与卡片"] (handlers "RecFacadeImpl"))
      (should= ["归因与投放"] (handlers "DispatchJob"))
      (should= ["会员权益与交易"] (handlers "BenefitController"))))

  (it "订阅多个 topic 的类、同 topic 不同 group 都是独立入口，带业务子系统 id 供网页高亮"
    (let [entries (attribution @scanned :architecture)]
      (should= ["阿福增长活动"] (entries "TP_GROW_A @ UserGrowthCoreConsumer#onMessage"))
      (should= ["阿福增长活动"] (entries "TP_GROW_B @ UserGrowthCoreConsumer#onMessage"))
      (should= ["事件中心"] (entries "TP_USER_EVENT @ EventCenterStoreOnlyConsumer#onMessage"))
      (should= ["会员权益与交易"] (entries "TP_C_USER / EC_SNS_INSPIRE_PRIZE_NOTICE @ SendPrizeMessageHandler#handle"))
      (should= [{:id "eventcenter" :label "事件中心"}]
               (get-in (first (filter #(= "UserAtomEventFacade#call" (:label %)) (:entries @scanned))) [:domains :architecture]))))

  (it "接入层自动判定为 rpc、mq、scheduler，业务子系统为其直接下游；默认来源 architecture，未归类 0"
    (let [grouping (:grouping @scanned)
          checked (report/check @scanned)]
      (should= {:ids ["rpc" "mq" "scheduler"] :basis "auto"} (:entryLayers grouping))
      (should= {:ids ["eventcenter" "aq" "attribute" "activecare" "rec" "benefit"] :basis "downstream"}
               (:domainSubsystems grouping))
      (should= "architecture" (:default grouping))
      (should= {"RPC-TR" 7 "MQ" 7 "RPC-网关" 3 "MsgBroker" 2 "定时" 2 "HTTP" 1} (get-in checked [:summary :channels]))
      (should= 6 (get-in checked [:summary :domains]))
      (should= 0 (get-in checked [:summary :unclassified]))
      (should= [] (:warnings checked))
      (should= ["阿福增长活动" "事件中心" "主动关怀" "会员权益与交易" "归因与投放" "推荐与卡片" "基础设施/其它" "测试/示例"]
               (map #(get-in % [:domain :label]) (get-in checked [:grouping :groups])))))

  (it "check 的 Markdown 给出分组表、判定依据和汇总"
    (let [md (run-cli grow/files grow/rules ["check"])]
      (should-contain "## 业务分组（来源 architecture）" md)
      (should-contain "| 事件中心 | 5 |" md)
      (should-contain "接入层：rpc（RPC 与开放接口）、mq（消息消费）、scheduler（定时任务） — 自动判定" md)
      (should-contain "architecture：覆盖 95%，业务域 6，多归属 0，未归类 0（默认）" md)
      (should-contain "，业务域 6，未归类 0，警告 0。" md))))

(describe "接入层与业务子系统"
  (it "entryLayers 为空时处理类所在子系统即业务域"
    (let [scanned (fixtures/scan-project grow/files (assoc grow/rules :entryLayers []))
          handlers (by-handler scanned :architecture)]
      (should= {:ids [] :basis "rule"} (get-in scanned [:grouping :entryLayers]))
      (should= "no-entry-layer" (get-in scanned [:grouping :domainSubsystems :basis]))
      (should= ["RPC 与开放接口"] (handlers "RecFacadeImpl"))
      (should= ["外部服务集成"] (handlers "MedContentSyncConsumerV2"))
      (should= ["事件中心"] (handlers "UserTaskStatusUpdateConsumer"))))

  (it "domainSubsystems 覆盖自动判定；写了不存在的子系统时报警"
    (let [scanned (fixtures/scan-project grow/files (assoc grow/rules :domainSubsystems ["aq" "eventcenter"] :entryLayers ["rpc" "mq" "scheduler" "gateway"]))
          handlers (by-handler scanned :architecture)
          warnings (into {} (map (juxt :code :items)) (:warnings (report/check scanned)))]
      (should= ["事件中心"] (handlers "UserAtomEventFacadeImpl"))
      (should= ["基础设施/其它"] (handlers "RecFacadeImpl"))
      (should= ["entryLayers 中的 gateway"] (warnings "unknown-subsystem"))))

  (it "多归属入口在每个组下出现并标注；没有文档归属的处理类未归类"
    (let [files (merge grow/files mixed-facade
                       {"app/common/src/main/java/com/co/grow/util/StrayConsumer.java"
                        (java "com.co.grow.util" "import com.alipay.sofa.mq.MessageConsumer;\n"
                              "public class StrayConsumer { @MessageConsumer(topic = \"TP_STRAY\") public void on(String m) {} }")})
          scanned (fixtures/scan-project files grow/rules)
          entries (attribution scanned :architecture)
          md (run-cli files grow/rules ["list" "--domain" "事件中心"])]
      (should= ["事件中心" "阿福增长活动"] (entries "MixFacade#call @ MixFacadeImpl#call"))
      (should= [] (entries "TP_STRAY @ StrayConsumer#on"))
      (should-contain "| RPC-TR | MixFacade#call | MixFacadeImpl#call | app/rpc/src/main/java/com/co/grow/rpc/aq/MixFacadeImpl.java:7 | 多归属 |" md)
      (should-contain "# 入口（6）" md)
      (should-contain "## 事件中心（6）" md)
      (should-not-contain "阿福增长活动（" md)
      (should-contain "## 未归类（1）" (run-cli files grow/rules ["list"]))))

  (it "没有架构文档时 architecture 来源为空，默认退到推断"
    (let [scanned (fixtures/scan-project (dissoc grow/files "ARCHITECTURE.md") grow/rules)]
      (should= nil (get-in scanned [:grouping :document]))
      (should= "inferred" (get-in scanned [:grouping :default]))
      (should= [{:id "eventcenter" :label "eventcenter"}]
               (get-in (first (filter #(= "TP_TASK_STATUS" (:label %)) (:entries scanned))) [:domains :inferred])))))

(describe "规则、注解与推断来源"
  (with scanned
    (fixtures/scan-project
     {"src/main/java/com/x/order/web/OrderController.java"
      (java "com.x.order.web" "import org.springframework.web.bind.annotation.*;\n"
            "import io.swagger.v3.oas.annotations.tags.Tag;\n"
            "@RestController @Tag(name = \"订单管理\") @RequestMapping(\"/api/v1/orders\")\n"
            "public class OrderController {\n  @GetMapping(\"/{id}\") public void get() {}\n"
            "  @GetMapping(\"/{id}/refund/detail\") public void refund() {}\n}")
      "src/main/java/com/x/billing/job/BillJob.java"
      (java "com.x.billing.job" "import org.springframework.scheduling.annotation.Scheduled;\n"
            "public class BillJob { @Scheduled(cron = \"0 0 1 * * ?\") public void run() {} }")
      "src/main/java/com/x/pay/mq/PayConsumer.java"
      (java "com.x.pay.mq" "import org.springframework.kafka.annotation.KafkaListener;\n"
            "public class PayConsumer { @KafkaListener(topics = \"order-paid\") public void on(String m) {} }")}
     {:version 1 :rules []
      :domains [{:id "order" :label "订单" :paths ["/api/v1/orders"]}
                {:id "refund" :label "退款" :paths ["/api/v1/orders/{id}/refund"] :topics ["order-paid"]}
                {:id "money" :label "资金" :packages ["com.x.billing" "com.x.pay"]}
                {:id "ghost" :label "无人" :packages ["com.y"]}]
      :aside [{:label "示例" :classes ["*Demo"]}]}))

  (it "rules：路径按段取最长前缀，topic 全等优先于包前缀"
    (let [entries (attribution @scanned :rules)]
      (should= ["订单"] (entries "GET /api/v1/orders/{id} @ OrderController#get"))
      (should= ["退款"] (entries "GET /api/v1/orders/{id}/refund/detail @ OrderController#refund"))
      (should= ["资金"] (entries "cron 0 0 1 * * ? @ BillJob#run"))
      (should= ["退款"] (entries "order-paid @ PayConsumer#on"))
      (should= "rules" (get-in @scanned [:grouping :default]))))

  (it "annotation 取 group 属性，inferred 取路径首段或包名去掉技术词"
    (let [annotation (attribution @scanned :annotation)
          inferred (attribution @scanned :inferred)]
      (should= ["订单管理"] (annotation "GET /api/v1/orders/{id} @ OrderController#get"))
      (should= ["orders"] (inferred "GET /api/v1/orders/{id} @ OrderController#get"))
      (should= ["billing"] (inferred "cron 0 0 1 * * ? @ BillJob#run"))
      (should= ["pay"] (inferred "order-paid @ PayConsumer#on"))))

  (it "命中 0 的 domains 与 aside 报警；list 可按来源切换"
    (let [warnings (into {} (map (juxt :code :items)) (:warnings (report/check @scanned)))]
      (should= ["ghost"] (warnings "domain-zero-hit"))
      (should= ["示例"] (warnings "aside-zero-hit")))
    (let [json (fixtures/with-project
                {"src/main/java/com/x/pay/mq/PayConsumer.java"
                 (java "com.x.pay.mq" "import org.springframework.kafka.annotation.KafkaListener;\n"
                       "public class PayConsumer { @KafkaListener(topics = \"order-paid\") public void on(String m) {} }")}
                (fn [root]
                  (with-redefs [cli-support/load-workspace (constantly (fixtures/workspace-of root))]
                    (cli-support/read-json-text
                     (with-out-str (cli/run! ["list" "--group-by" "inferred" "--format" "json"]))))))]
      (should= "inferred" (:groupBy json))
      (should= [{:id "pay" :label "pay"}] (get-in json [:entries 0 :domains :inferred])))))

(describe "candidates 的未归类统计"
  (it "按默认来源列出未归类入口的路径与包，供起草 domains"
    (let [files (assoc grow/files "app/common/src/main/java/com/co/grow/util/StrayController.java"
                       (java "com.co.grow.util" "import org.springframework.web.bind.annotation.*;\n"
                             "@RestController public class StrayController { @GetMapping(\"/api/stray/x\") public void x() {} }"))
          md (run-cli files grow/rules ["candidates"])]
      (should-contain "## 未归类入口（来源 architecture，1）" md)
      (should-contain "- HTTP 路径：/api/stray ×1" md)
      (should-contain "- 处理类包：com.co.grow.util ×1" md)
      (should-not-contain "未归类入口" (run-cli grow/files grow/rules ["candidates"])))))
