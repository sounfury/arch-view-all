;; 职责：声明常见出口的默认规则：下游 HTTP/RPC、数据库与消息发布；与项目 role=exit 规则使用同一套匹配语义。
;; 核心入口：内置出口规则（specs）。

(ns arch-view.entries.exit-specs)

(def specs
  (concat
   [{:id "exit-feign" :role "exit" :annotation "org.springframework.cloud.openfeign.FeignClient"
     :as "kind:http" :attrs {"name" "service" "value" "service" "url" "service"}}
    {:id "exit-mapper" :role "exit" :annotation "org.apache.ibatis.annotations.Mapper" :as "kind:db"}
    {:id "exit-jpa" :role "exit" :implements "org.springframework.data.repository.Repository" :as "kind:db"}
    {:id "exit-jpa" :role "exit" :implements "org.springframework.data.jpa.repository.JpaRepository" :as "kind:db"}
    {:id "exit-jpa" :role "exit" :implements "org.springframework.data.repository.CrudRepository" :as "kind:db"}
    {:id "exit-kafka" :role "exit" :type "org.springframework.kafka.core.KafkaTemplate" :method "send"
     :as "kind:mq" :args {"0" "topic"}}
    {:id "exit-rabbit" :role "exit" :type "org.springframework.amqp.rabbit.core.RabbitTemplate" :method "convertAndSend"
     :as "kind:mq" :args {"0" "topic"}}
    {:id "exit-rocketmq" :role "exit" :type "org.apache.rocketmq.spring.core.RocketMQTemplate" :method "convertAndSend"
     :as "kind:mq" :args {"0" "topic"}}
    {:id "exit-rocketmq" :role "exit" :type "org.apache.rocketmq.spring.core.RocketMQTemplate" :method "syncSend"
     :as "kind:mq" :args {"0" "topic"}}]
   (for [type ["org.springframework.jdbc.core.JdbcTemplate" "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate"]
         method ["query" "queryForObject" "queryForList" "update" "execute" "batchUpdate"]]
     {:id "exit-jdbc" :role "exit" :type type :method method :as "kind:db"})
   (for [annotation ["org.apache.dubbo.config.annotation.DubboReference" "org.apache.dubbo.config.annotation.Reference"
                     "com.alibaba.dubbo.config.annotation.Reference" "com.alipay.sofa.runtime.api.annotation.SofaReference"]]
     {:id "exit-rpc" :role "exit" :annotation annotation :on "field" :as "kind:rpc"
      :attrs {"interfaceClass" "service" "interfaceType" "service" "version" "version" "uniqueId" "uniqueId"}})))
