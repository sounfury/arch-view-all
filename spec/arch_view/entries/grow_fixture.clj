;; 职责：与真实增长中心同构的脱敏夹具：三层构建模块、架构文档数据流、公司注解规则与 MsgBroker 订阅，覆盖业务分组验收用例。
;; 核心入口：files、rules、architecture-doc。

(ns arch-view.entries.grow-fixture
  (:require [arch-view.entries.fixtures :refer [java]]
            [clojure.string :as str]))

(def flow
  (str "flowchart TD\n"
       "    client[\"前端 / 外部业务系统\"]\n    upstream[\"上游消息\"]\n    external[\"外部系统\"]\n"
       "    db[(\"OceanBase\")]\n"
       "    rpc[\"rpc：RPC 与开放接口\"]\n    mq[\"mq：消息消费\"]\n    scheduler[\"scheduler：定时任务\"]\n"
       "    eventcenter[\"eventcenter：事件中心\"]\n    aq[\"aq：阿福增长活动\"]\n    attribute[\"attribute：归因与投放\"]\n"
       "    activecare[\"activecare：主动关怀\"]\n    rec[\"rec：推荐与卡片\"]\n    benefit[\"benefit：会员权益与交易\"]\n"
       "    integration[\"integration：外部服务集成\"]\n    dal[\"dal：数据访问\"]\n"
       "    client -->|TR / 开放接口 / HTTP 请求| rpc\n    upstream -->|SOFAMQ 消息| mq\n"
       "    rpc -->|查询与活动操作| aq\n    rpc -->|事件查询| eventcenter\n    rpc -->|归因查询| attribute\n"
       "    rpc -->|关怀任务管理| activecare\n    rpc -->|推荐请求| rec\n    rpc -->|权益查询| benefit\n"
       "    mq -->|用户行为事件| eventcenter\n    mq -->|关怀动作| activecare\n    mq -->|点击与转化| attribute\n"
       "    scheduler -->|补偿、扫描| activecare\n    scheduler -->|派发投放| attribute\n"
       "    eventcenter -->|任务完成| aq\n    aq --> dal\n    eventcenter --> dal\n    activecare --> dal\n    dal --> db\n"
       "    aq -->|发奖| integration\n    rec -->|内容查询| integration\n    benefit -->|礼包查询| integration\n"
       "    integration -->|RPC 调用| external\n"))

(def ^:private subsystems
  [["rpc" "RPC 与开放接口" "rpc/com/co/grow/rpc" "rpc/com/co/grow/rpc、rpc/com/co/grow/web"]
   ["mq" "消息消费" "core/com/co/grow/mq" nil]
   ["scheduler" "定时任务" "core/com/co/grow/scheduler" nil]
   ["eventcenter" "事件中心" "core/com/co/grow/service/eventcenter" nil]
   ["aq" "阿福增长活动" "core/com/co/grow/service/aq" nil]
   ["attribute" "归因与投放" "core/com/co/grow/service/attribute" nil]
   ["activecare" "主动关怀" "core/com/co/grow/service/activecare" nil]
   ["rec" "推荐与卡片" "core/com/co/grow/service/rec" nil]
   ["benefit" "会员权益与交易" "core/com/co/grow/service/benefit" nil]
   ["integration" "外部服务集成" "common/com/co/grow/integration" nil]
   ["dal" "数据访问" "common/com/co/grow/dal" nil]])

(defn architecture-doc
  "flow-text 为数据流图 Mermaid 源码。"
  ([] (architecture-doc flow))
  ([flow-text]
   (str "# 增长中心架构总览\n\n## 项目说明\n\n夹具。\n\n## 数据流\n\n```mermaid\n" flow-text "```\n\n"
        "## 核心子系统与职责\n\n"
        (str/join (for [[id label module scopes] subsystems]
                    (str "### " id " · " label "\n\n- **实现状态**：已完成。\n- **职责**：" label "。\n"
                         (when scopes (str "- **覆盖范围**：" scopes "\n"))
                         "\n[探索这个子系统](#module=" module ")\n\n"))))))

(defn- src [module package & body]
  [(str "app/" module "/src/main/java/" (str/replace package "." "/") "/" (second (re-find #"(?:class|interface) (\w+)" (apply str body))) ".java")
   (apply java package body)])

(def ^:private sofa "import com.alipay.sofa.runtime.api.annotation.*;\n")

(defn- tr-facade
  "SOFA TR 服务：接口与实现放在 rpc 模块，实现类通过字段依赖下游。"
  [package facade dependency]
  (let [[dep-import dep-type] dependency]
    [(src "rpc" package "public interface " facade " { String call(); }")
     (src "rpc" package sofa "import " dep-import ";\n"
          "@SofaService(interfaceType = " facade ".class, bindings = {@SofaServiceBinding(bindingType = \"tr\")})\n"
          "public class " facade "Impl implements " facade " {\n  " dep-type " dep;\n"
          "  public String call() { dep.run(); return \"\"; }\n}")]))

(defn- service [package name & extra]
  (src "core" package "public class " name " { " (apply str extra) "public void run() {} }"))

(def ^:private mq "import com.alipay.sofa.mq.MessageConsumer;\n")

(defn- consumer [module package name annotation [dep-import dep-type]]
  (src module package mq "import " dep-import ";\n"
       "public class " name " {\n  " dep-type " dep;\n  " annotation " public void onMessage(String m) { dep.run(); }\n}"))

(def ^:private uniform-event-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:sofa=\"http://sofa\">\n"
       "  <sofa:consumer id=\"c2c\" group=\"S_medusergrowcenter_c2c\">\n"
       "    <sofa:listener ref=\"commonListener\"/>\n"
       "    <sofa:channels><sofa:channel value=\"TP_C_USER\">\n"
       "      <sofa:event eventType=\"direct\" eventCode=\"EC_SNS_INSPIRE_PRIZE_NOTICE\"/>\n"
       "    </sofa:channel></sofa:channels>\n  </sofa:consumer>\n"
       "  <sofa:consumer id=\"bill\" group=\"S-medusergrowcenter-member-benefit-event\">\n"
       "    <sofa:listener ref=\"commonListener\"/>\n"
       "    <sofa:channels><sofa:channel value=\"TP_C_BILL\">\n"
       "      <sofa:event eventType=\"direct\" eventCode=\"EC_member_benefit_event\"/>\n"
       "    </sofa:channel></sofa:channels>\n  </sofa:consumer>\n</beans>\n"))

(defn- msg-handler [name key]
  (src "core" "com.co.grow.service.benefit"
       "import com.co.msg.UniformEventMessageHandler;\n"
       "public class " name " implements UniformEventMessageHandler {\n  BenefitService dep;\n"
       "  public String getHandlerKey() { return \"" key "\"; }\n"
       "  @Override public void handle(Object event) { dep.run(); }\n}"))

(def ^:private ec ["com.co.grow.service.eventcenter.EventService" "EventService"])
(def ^:private aq ["com.co.grow.service.aq.TaskService" "TaskService"])
(def ^:private care ["com.co.grow.service.activecare.CareService" "CareService"])

(def files
  (into
   {"pom.xml" "<project><modules><module>app/rpc</module><module>app/core</module><module>app/common</module></modules></project>"
    "app/rpc/pom.xml" "<project><artifactId>rpc</artifactId></project>"
    "app/core/pom.xml" "<project><artifactId>core</artifactId></project>"
    "app/common/pom.xml" "<project><artifactId>common</artifactId></project>"
    "ARCHITECTURE.md" (architecture-doc)
    "app/core/src/main/resources/spring/biz-service-uniform-event.xml" uniform-event-xml}
   (concat
    ;; 业务子系统与基础设施
    [(service "com.co.grow.service.eventcenter" "EventService")
     (service "com.co.grow.service.aq" "TaskService")
     (service "com.co.grow.service.attribute" "AttrService")
     (service "com.co.grow.service.activecare" "CareService")
     (service "com.co.grow.service.rec" "RecService")
     (service "com.co.grow.service.benefit" "BenefitService")
     (src "core" "com.co.grow.api" "public interface CareOperator { void run(); }")
     (src "core" "com.co.grow.service.activecare" "import com.co.grow.api.CareOperator;\n"
          "public class CareOperatorImpl implements CareOperator { public void run() {} }")
     (src "core" "com.co.grow.biz" "import com.co.grow.service.rec.RecService;\n"
          "public class RecManager { RecService rec; public void run() { rec.run(); } }")
     (src "common" "com.co.grow.integration" "public class MedContentClient { public void run() {} }")]
    ;; RPC-TR
    (tr-facade "com.co.grow.rpc.eventcenter.impl" "UserAtomEventFacade" ec)
    (tr-facade "com.co.grow.rpc.aq" "EventCenterDataFacade" ec)
    (tr-facade "com.co.grow.rpc.aq" "ChallengeV4WeightLossTestFacade" aq)
    (tr-facade "com.co.grow.rpc.activecare" "ActiveCareV2TestFacade" ["com.co.grow.api.CareOperator" "CareOperator"])
    (tr-facade "com.co.grow.rpc.rec" "RecFacade" ["com.co.grow.biz.RecManager" "RecManager"])
    (tr-facade "com.co.grow.rpc.demo" "RpcDemo" aq)
    (tr-facade "com.co.grow.rpc.demo" "BusinessTimeTestRpc" aq)
    ;; RPC-网关：注解写在接口方法上
    [(src "rpc" "com.co.grow.rpc.aq" "import com.alipay.mobilegw.OperationType;\n"
          "public interface TaskRpc {\n  @OperationType(\"alipay.grow.task.list\") String list();\n"
          "  @OperationType(\"alipay.grow.task.accept\") String accept();\n"
          "  @OperationType(\"alipay.grow.task.finish\") String finish();\n}")
     (src "rpc" "com.co.grow.rpc.aq" "import com.co.grow.service.aq.TaskService;\n"
          "public class TaskRpcImpl implements TaskRpc {\n  TaskService dep;\n"
          "  public String list() { dep.run(); return \"\"; }\n  public String accept() { dep.run(); return \"\"; }\n"
          "  public String finish() { dep.run(); return \"\"; }\n}")
     ;; HTTP，处理类在 rpc 子系统的第二个覆盖范围里
     (src "rpc" "com.co.grow.web" "import org.springframework.web.bind.annotation.*;\n"
          "import com.co.grow.service.benefit.BenefitService;\n"
          "@RestController public class BenefitController {\n  BenefitService dep;\n"
          "  @GetMapping(\"/api/benefit/list\") public void list() { dep.run(); }\n}")
     ;; MQ
     (consumer "core" "com.co.grow.service.eventcenter.consumer" "UserTaskStatusUpdateConsumer"
               "@MessageConsumer(topic = \"TP_TASK_STATUS\", group = \"G_TASK\")" ec)
     (consumer "core" "com.co.grow.mq" "EventCenterUserTriggerConsumer"
               "@MessageConsumer(topic = \"TP_USER_EVENT\", group = \"G_TRIGGER\")" ec)
     (consumer "core" "com.co.grow.mq" "EventCenterStoreOnlyConsumer"
               "@MessageConsumer(topic = \"TP_USER_EVENT\", group = \"G_STORE\")" ec)
     (consumer "core" "com.co.grow.mq" "UserGrowthCoreConsumer"
               "@MessageConsumer(topic = {\"TP_GROW_A\", \"TP_GROW_B\"}, group = \"G_GROW\")" aq)
     (consumer "core" "com.co.grow.mq" "CareActionConsumer"
               "@MessageConsumer(topic = \"TP_CARE\", group = \"G_CARE\", orderly = true)" care)
     (consumer "common" "com.co.grow.integration" "MedContentSyncConsumerV2"
               "@MessageConsumer(topic = \"TP_CONTENT\", group = \"G_CONTENT\")"
               ["com.co.grow.integration.MedContentClient" "MedContentClient"])
     ;; MsgBroker：XML 订阅 + 公共 listener 按 key 分发
     (src "core" "com.co.grow.mq" "import com.co.msg.UniformEventMessageListener;\n"
          "import org.springframework.stereotype.Component;\n"
          "@Component public class CommonListener implements UniformEventMessageListener {\n"
          "  @Override public void onUniformEvent(Object e) {}\n}")
     (msg-handler "SendPrizeMessageHandler" "TP_C_USER_EC_SNS_INSPIRE_PRIZE_NOTICE")
     (msg-handler "MsgBrokerPublishHealthGiftPackImpl" "TP_C_BILL_EC_member_benefit_event")
     ;; 定时
     (src "core" "com.co.grow.scheduler" "import com.co.job.ISimpleJobHandler;\n"
          "import com.co.grow.service.attribute.AttrService;\n"
          "public class DispatchJob implements ISimpleJobHandler {\n  AttrService dep;\n"
          "  @Override public void handle() { dep.run(); }\n}")
     (src "core" "com.co.grow.scheduler" "import com.co.job.Scheduler;\n"
          "import com.co.grow.service.activecare.CareService;\n"
          "public class CompensateTask {\n  CareService dep;\n"
          "  @Scheduler(cron = \"0 0/5 * * * ?\") public void compensate() { dep.run(); }\n}")])))

(def rules
  {:version 1
   :rules [{:id "sofa-tr" :annotation "com.alipay.sofa.runtime.api.annotation.SofaService" :on "class"
            :as "kind:rpc" :channel "RPC-TR" :when {"bindings.bindingType" "tr"}
            :attrs {:interfaceType "service"} :methods {:interfaceFromAttr "interfaceType"}}
           {:id "mobilegw" :annotation "com.alipay.mobilegw.OperationType" :as "kind:rpc" :channel "RPC-网关"
            :attrs {:value "operation"}}
           {:id "sofamq" :annotation "com.alipay.sofa.mq.MessageConsumer" :as "kind:mq" :channel "MQ"
            :attrs {:topic "topic" :group "consumerGroup" :orderly "flag:顺序消费"}}
           {:id "msgbroker" :as "kind:mq" :channel "MsgBroker"
            :xml {:files ["spring/*.xml"]
                  :each "sofa:consumer/sofa:channels/sofa:channel/sofa:event"
                  :fields {:consumerGroup "../../../@group" :topic "../@value" :eventCode "@eventCode"
                           :bean "../../../sofa:listener/@ref"}}
            :label "{topic} / {eventCode}"
            :dispatch {:key "{topic}_{eventCode}"
                       :handlers {:implements "com.co.msg.UniformEventMessageHandler" :keyMethod "getHandlerKey"
                                  :entryMethods {:interfaceMethods true}}}}
           {:id "simple-job" :implements "com.co.job.ISimpleJobHandler" :as "kind:schedule" :channel "定时"}
           {:id "scheduler" :annotation "com.co.job.Scheduler" :as "kind:schedule" :channel "定时"
            :attrs {:cron "cron"}}]
   :aside [{:label "测试/示例" :classes ["RpcDemo" "BusinessTimeTestRpc"]}]})
