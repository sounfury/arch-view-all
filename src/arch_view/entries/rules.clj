;; 职责：定义内置入口规则，读取并校验项目规则文件 .arch-view/entry-rules.json，把两者统一成内部规则。
;; 核心入口：加载规则（load-rules）；规范化单条规则（normalize-rule）；内置规则表（builtin-rules）。

(ns arch-view.entries.rules
  (:require [arch-view.cli-support :as cli-support]
            [arch-view.entries.exit-specs :as exits]
            [arch-view.entries.xml-source :as xml]
            [arch-view.workspace :as workspace]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(declare ^:private rule-errors ^:private top-level-errors ^:private role-of ^:private normalize-methods
         ^:private normalize-xml ^:private normalize-dispatch)

(def rules-file-name "entry-rules.json")

(def kind-labels
  {:http "HTTP" :rpc "RPC" :mq "消息" :schedule "定时" :startup "启动" :event "事件"})

(def semantic-keys
  #{"prefix" "path" "httpMethod" "cron" "fixedRate" "fixedDelay" "name" "topic" "consumerGroup" "tag"
    "service" "version" "uniqueId" "operation" "group" "summary"})

;; spring:* 的含义：角色、入口类型、默认机制名、默认属性映射、固定 HTTP 方法。
(def ^:private spring-targets
  (let [mapping {"value" "path" "path" "path" "method" "httpMethod"}
        fixed (fn [method] {:role :http-mapping :kind :http :channel "HTTP" :on :method
                            :attrs {"value" "path" "path" "path"} :http-method method})]
    {"spring:RestController" {:role :http-controller :kind :http :channel "HTTP" :on :class :attrs {}}
     "spring:Controller" {:role :http-controller :kind :http :channel "HTTP" :on :class :attrs {}}
     "spring:RequestMapping" {:role :http-mapping :kind :http :channel "HTTP" :on :any :attrs mapping}
     "spring:GetMapping" (fixed "GET")
     "spring:PostMapping" (fixed "POST")
     "spring:PutMapping" (fixed "PUT")
     "spring:DeleteMapping" (fixed "DELETE")
     "spring:PatchMapping" (fixed "PATCH")
     "spring:Scheduled" {:role :entry :kind :schedule :channel "定时" :on :method
                         :attrs {"cron" "cron" "fixedRate" "fixedRate" "fixedDelay" "fixedDelay"}}
     "spring:KafkaListener" {:role :entry :kind :mq :channel "Kafka" :on :method
                             :attrs {"topics" "topic" "groupId" "consumerGroup"}}
     "spring:RabbitListener" {:role :entry :kind :mq :channel "RabbitMQ" :on :method :attrs {"queues" "topic"}}
     "spring:EventListener" {:role :entry :kind :event :channel "Spring 事件" :on :method :attrs {}}}))

(def ^:private web "org.springframework.web.bind.annotation.")

(defn- sofa [binding]
  {:id "sofa-service" :annotation "com.alipay.sofa.runtime.api.annotation.SofaService" :as "kind:rpc" :on "class"
   :when {"bindings.bindingType" binding} :channel (str "SOFA-" binding)
   :attrs {"interfaceType" "service" "uniqueId" "uniqueId"} :methods {"interfaceFromAttr" "interfaceType"}})

(defn- jaxrs [package]
  (concat [{:id "jaxrs" :annotation (str package ".Path") :on "class" :as "spring:RestController"
            :attrs {"value" "prefix"}}
           {:id "jaxrs" :annotation (str package ".Path") :on "method" :as "spring:RequestMapping"
            :attrs {"value" "path"}}]
          (for [method ["GET" "POST" "PUT" "DELETE" "PATCH"]]
            {:id "jaxrs" :annotation (str package "." method) :as (str "spring:" (str/capitalize method) "Mapping")
             :attrs {}})))

(def builtin-specs
  "内置规则与项目规则同一写法；同一 id 下的多条共享命中计数，可整体用 disableBuiltin 关闭。"
  (concat
   [{:id "spring-mvc" :annotation (str web "RestController") :as "spring:RestController"}
    {:id "spring-mvc" :annotation "org.springframework.stereotype.Controller" :as "spring:Controller"}
    {:id "spring-mvc" :annotation (str web "RequestMapping") :as "spring:RequestMapping"
     :attrs {"value" "prefix" "path" "prefix" "method" "httpMethod"} :on "class"}
    {:id "spring-mvc" :annotation (str web "RequestMapping") :as "spring:RequestMapping" :on "method"}]
   (for [method ["Get" "Post" "Put" "Delete" "Patch"]]
     {:id "spring-mvc" :annotation (str web method "Mapping") :as (str "spring:" method "Mapping")})
   (jaxrs "javax.ws.rs")
   (jaxrs "jakarta.ws.rs")
   [{:id "spring-scheduled" :annotation "org.springframework.scheduling.annotation.Scheduled" :as "spring:Scheduled"}
    {:id "spring-kafka" :annotation "org.springframework.kafka.annotation.KafkaListener" :as "spring:KafkaListener"}
    {:id "spring-rabbit" :annotation "org.springframework.amqp.rabbit.annotation.RabbitListener"
     :as "spring:RabbitListener"}
    {:id "rocketmq" :annotation "org.apache.rocketmq.spring.annotation.RocketMQMessageListener" :as "kind:mq"
     :on "class" :channel "RocketMQ"
     :attrs {"topic" "topic" "consumerGroup" "consumerGroup" "selectorExpression" "tag"}
     :methods {"implementsOf" "org.apache.rocketmq.spring.core.RocketMQListener"}}
    {:id "spring-event" :annotation "org.springframework.context.event.EventListener" :as "spring:EventListener"}
    {:id "spring-event" :annotation "org.springframework.transaction.event.TransactionalEventListener"
     :as "spring:EventListener"}]
   (for [annotation ["org.apache.dubbo.config.annotation.DubboService"
                     "org.apache.dubbo.config.annotation.Service"
                     "com.alibaba.dubbo.config.annotation.Service"]]
     {:id "dubbo" :annotation annotation :as "kind:rpc" :on "class" :channel "Dubbo"
      :attrs {"interfaceClass" "service" "version" "version"} :methods {"interfaceMethods" true}})
   (map sofa ["bolt" "rest" "dubbo" "h2c" "tr"])
   [{:id "grpc" :extendsSuffix "ImplBase" :as "kind:rpc" :channel "gRPC" :methods {"interfaceMethods" true}}
    {:id "xxl-job" :annotation "com.xxl.job.core.handler.annotation.XxlJob" :as "kind:schedule"
     :channel "XXL-JOB" :attrs {"value" "name"}}
    {:id "quartz" :implements "org.quartz.Job" :as "kind:schedule" :channel "Quartz"
     :methods {"interfaceMethods" true}}]
   (for [job ["org.apache.shardingsphere.elasticjob.simple.job.SimpleJob"
              "org.apache.shardingsphere.elasticjob.dataflow.job.DataflowJob"
              "com.dangdang.ddframe.job.api.simple.SimpleJob"]]
     {:id "elastic-job" :implements job :as "kind:schedule" :channel "ElasticJob" :methods {"interfaceMethods" true}})
   [{:id "startup" :main true :as "kind:startup" :channel "启动"}
    {:id "startup" :implements "org.springframework.boot.CommandLineRunner" :as "kind:startup" :channel "启动"
     :methods {"interfaceMethods" true}}
    {:id "startup" :implements "org.springframework.boot.ApplicationRunner" :as "kind:startup" :channel "启动"
     :methods {"interfaceMethods" true}}
    {:id "openapi" :annotation "io.swagger.v3.oas.annotations.tags.Tag" :as "meta" :attrs {"name" "group"}}
    {:id "openapi" :annotation "io.swagger.annotations.Api" :as "meta" :attrs {"tags" "group"}}
    {:id "openapi" :annotation "io.swagger.v3.oas.annotations.Operation" :as "meta"
     :attrs {"summary" "summary" "tags" "group"}}
    {:id "openapi" :annotation "io.swagger.annotations.ApiOperation" :as "meta" :attrs {"value" "summary"}}]))

(defn normalize-rule
  "把规则写法转成内部规则：{:id :origin :order :match :on :role :kind :channel :attrs :defaults :when :methods}。"
  [origin order spec]
  (let [spec (update-keys spec name)
        {:keys [role kind channel on attrs http-method]} (role-of (get spec "as"))
        match (some (fn [k] (when-let [v (get spec k)] {(keyword k) (if (= "xml" k) (normalize-xml v) v)}))
                    ["annotation" "implements" "extends" "extendsSuffix" "main" "type" "xml"])]
    {:id (get spec "id") :origin origin :order order :match match
     :on (keyword (or (get spec "on") (when (= "exit" (get spec "role")) "class")
                      (when-not (:annotation match) "class") (name on)))
     :role (if (= "exit" (get spec "role")) :exit role) :kind kind :channel (or (get spec "channel") channel)
     :attrs (update-keys (or (get spec "attrs") attrs) name)
     :defaults (update-keys (or (get spec "defaults") {}) name)
     :when (update-keys (or (get spec "when") {}) name)
     :methods (normalize-methods (get spec "methods"))
     :method (get spec "method") :args (update-keys (or (get spec "args") {}) name)
     :label (get spec "label")
     :dispatch (normalize-dispatch (get spec "dispatch"))
     :http-method http-method}))

(defn builtin-rules
  "内置规则，按 disabled 集合关闭整组。"
  [disabled]
  (->> (concat builtin-specs exits/specs)
       (map-indexed #(normalize-rule :builtin %1 %2))
       (remove #(contains? disabled (:id %)))
       vec))

(defn builtin-ids []
  (set (map :id (concat builtin-specs exits/specs))))

(defn load-rules
  "读取项目规则并与内置规则合并。规则文件缺失或有错时仍返回内置规则，:status 标明 ok、missing 或 error。"
  [ws]
  (let [file (io/file (workspace/data-dir ws) rules-file-name)
        path (workspace/relative ws file)]
    (if-not (.isFile file)
      {:file path :status "missing" :rules (builtin-rules #{}) :project []}
      (try
        (let [data (cli-support/read-json-file file)
              errors (concat (top-level-errors data) (rule-errors (:rules data)))]
          (if (seq errors)
            {:file path :status "error" :errors (vec errors) :rules (builtin-rules #{}) :project []}
            (let [project (vec (map-indexed #(normalize-rule :project (+ 10000 %1) %2) (:rules data)))]
              {:file path :status "ok" :config (dissoc data :rules :version :disableBuiltin)
               :rules (into project (builtin-rules (set (:disableBuiltin data)))) :project project})))
        (catch clojure.lang.ExceptionInfo ex
          {:file path :status "error" :errors [(str "规则文件无法读取：" (.getMessage ex))]
           :rules (builtin-rules #{}) :project []})))))

;; ===== 私有方法 =====

(defn- normalize-methods [methods]
  (if (map? methods) (update-keys methods name) methods))

(defn- normalize-xml [xml]
  (let [xml (update-keys xml name)]
    {:files (vec (get xml "files")) :each (get xml "each") :fields (update-keys (get xml "fields") name)}))

(defn- normalize-dispatch [dispatch]
  (when dispatch
    (let [dispatch (update-keys dispatch name)
          handlers (update-keys (get dispatch "handlers") name)]
      {:key (get dispatch "key")
       :handlers {:implements (get handlers "implements") :keyMethod (get handlers "keyMethod")
                  :entryMethods (normalize-methods (get handlers "entryMethods"))}})))

(defn- role-of [as]
  (cond
    (contains? spring-targets as) (spring-targets as)
    (= "meta" as) {:role :meta :on :any :attrs {}}
    (= "kind:db" as) {:role :exit :kind :db :channel "DB" :on :class :attrs {}}
    (str/starts-with? (str as) "kind:")
    (let [kind (keyword (subs as 5))] {:role :entry :kind kind :channel (kind-labels kind) :on :method :attrs {}})
    :else {}))

(def ^:private rule-fields
  #{:id :role :annotation :implements :extends :type :method :args :xml :on :as :channel :attrs :defaults :when :methods :label :dispatch})

(def ^:private top-fields
  #{:version :disableBuiltin :rules :entryLayers :domainSubsystems :domains :aside})

(defn- valid-as? [as]
  (or (= "kind:db" as) (contains? spring-targets as) (= "meta" as)
      (and (string? as) (str/starts-with? as "kind:") (contains? kind-labels (keyword (subs as 5))))))

(defn- valid-semantic? [key]
  (let [key (str key)]
    (or (contains? semantic-keys key)
        (and (re-matches #"(flag|extra):.+" key) true))))

(defn- valid-methods? [methods]
  (or (nil? methods) (= "public" methods)
      (and (map? methods) (= 1 (count methods))
           (let [[k v] (first methods)]
             (case (name k)
               "interfaceMethods" (true? v)
               ("interfaceFromAttr" "implementsOf" "annotation") (and (string? v) (not (str/blank? v)))
               false)))))

(defn- valid-condition? [condition]
  (or (string? condition) (number? condition) (boolean? condition)
      (and (map? condition) (= 1 (count condition))
           (let [[k v] (first condition)]
             (case (name k)
               "in" (and (sequential? v) (every? (some-fn string? number? boolean?) v))
               "not" (valid-condition? v)
               "present" (boolean? v)
               false)))))

(defn- exit-kind-errors [prefix rule]
  (concat
   (when (and (contains? rule :role) (not (#{"entry" "exit"} (:role rule))))
     [(str prefix "role 只能是 entry 或 exit")])
   (when (and (= "kind:db" (:as rule)) (not= "exit" (:role rule)))
     [(str prefix "kind:db 必须声明 role: exit")])
   (when (and (= "exit" (:role rule)) (not (#{"kind:http" "kind:rpc" "kind:mq" "kind:db"} (:as rule))))
     [(str prefix "出口 as 只能是 kind:http、kind:rpc、kind:mq 或 kind:db")])
   (when (and (= "exit" (:role rule)) (:dispatch rule)) [(str prefix "dispatch 只能用于入口规则")])
   (when (and (= "exit" (:role rule)) (seq (:when rule)) (not (:annotation rule)))
     [(str prefix "出口 when 目前只能用于注解规则")])))

(defn- valid-args? [args]
  (and (map? args) (every? #(re-matches #"\d+" (name %)) (keys args))
       (every? valid-semantic? (vals args))))

(defn- call-selector-errors [prefix rule]
  (concat
   (when (and (some #(contains? rule %) [:type :method :args]) (not= "exit" (:role rule)))
     [(str prefix "type、method、args 只能用于出口规则")])
   (when (and (:type rule) (not (and (string? (:type rule)) (not (str/blank? (:type rule))))))
     [(str prefix "type 必须是非空类型全名")])
   (when (and (:method rule) (not (and (string? (:method rule)) (not (str/blank? (:method rule))))))
     [(str prefix "method 必须是非空方法名")])
   (when (and (contains? rule :args) (not (valid-args? (:args rule))))
     [(str prefix "args 必须是出口参数序号到语义键的对象，如 {\"0\": \"topic\"}")])))

(defn- shape-errors [prefix rule]
  (concat
   (for [field (keys rule) :when (not (contains? rule-fields field))] (str prefix "未知字段 " (name field)))
   (when-not (= 1 (count (filter rule [:annotation :implements :extends :type :xml])))
     [(str prefix "annotation、implements、extends、xml 必须且只能写一个（出口也可选 type）")])
   (exit-kind-errors prefix rule)
   (call-selector-errors prefix rule)
   (when-not (valid-as? (:as rule)) [(str prefix "as 取值无效：" (:as rule))])
   (when (and (:on rule) (not (contains? (if (= "exit" (:role rule)) #{"class" "method" "field"} #{"class" "method"}) (:on rule))))
     [(str prefix "on 只能是 class 或 method（出口可写 field）")])
   (when (and (:channel rule) (not (string? (:channel rule)))) [(str prefix "channel 必须是字符串")])
   (when-not (valid-methods? (:methods rule)) [(str prefix "methods 写法无效")])))

(defn- text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- xml-errors [prefix {:keys [xml] :as rule}]
  (let [fields (:fields xml)]
    (concat
     (when-not (and (map? xml) (sequential? (:files xml)) (seq (:files xml)) (every? text? (:files xml)))
       [(str prefix "xml.files 必须是非空字符串数组")])
     (when-not (and (text? (:each xml)) (xml/valid-path? (:each xml)))
       [(str prefix "xml.each 必须是元素名路径，如 sofa:consumer/sofa:channel")])
     (when-not (and (map? fields) (seq fields)) [(str prefix "xml.fields 必须是非空对象")])
     (for [[field path] fields
           :when (not (and (text? path) (xml/valid-path? path)))]
       (str prefix "xml.fields." (name field) " 路径无效：" path))
     (when-not (str/starts-with? (str (:as rule)) "kind:") [(str prefix "xml 规则的 as 只能是 kind:*")])
     (when (or (:on rule) (:when rule)) [(str prefix "xml 规则不支持 on 和 when")]))))

(defn- dispatch-errors [prefix {:keys [dispatch xml label]}]
  (let [handlers (:handlers dispatch)]
    (concat
     (when (and label (not (text? label))) [(str prefix "label 必须是字符串")])
     (when (and dispatch (not xml)) [(str prefix "dispatch 目前只能与 xml 一起使用")])
     (when dispatch
       (concat
        (when-not (text? (:key dispatch)) [(str prefix "dispatch.key 必须是模板字符串，如 {topic}_{eventCode}")])
        (when-not (and (text? (:implements handlers)) (text? (:keyMethod handlers)))
          [(str prefix "dispatch.handlers 需要 implements 和 keyMethod")])
        (when-not (valid-methods? (normalize-methods (:entryMethods handlers)))
          [(str prefix "dispatch.handlers.entryMethods 写法无效")]))))))

(defn- field-errors [prefix rule]
  (concat
   (shape-errors prefix rule)
   (when (:xml rule) (xml-errors prefix rule))
   (dispatch-errors prefix rule)
   (for [[attr key] (:attrs rule) :when (not (valid-semantic? key))]
     (str prefix "attrs." (name attr) " 的语义键无效：" key))
   (for [[key _] (:defaults rule) :when (not (valid-semantic? (name key)))]
     (str prefix "defaults 的语义键无效：" (name key)))
   (for [[path condition] (:when rule) :when (not (valid-condition? condition))]
     (str prefix "when." (name path) " 条件无效"))))

(defn- rule-errors [rules]
  (if-not (sequential? rules)
    ["rules 必须是数组"]
    (let [ids (keep :id rules)
          duplicated (for [[id n] (frequencies ids) :when (> n 1)] (str "规则 id 重复：" id))]
      (concat duplicated
              (mapcat (fn [index rule]
                        (if-not (map? rule)
                          [(str "第 " (inc index) " 条规则必须是对象")]
                          (let [id (:id rule)
                                prefix (str "规则 " (or id (str "#" (inc index))) "：")]
                            (concat (when-not (cli-support/valid-id? id) [(str prefix "id 缺失或格式无效")])
                                    (field-errors prefix rule)))))
                      (range) rules)))))

(defn- text-list? [value]
  (and (sequential? value) (every? text? value)))

(defn- domain-errors [index domain]
  (let [prefix (str "domains 第 " (inc index) " 项" (when (text? (:id domain)) (str "（" (:id domain) "）")) "：")]
    (if-not (map? domain)
      [(str prefix "必须是对象")]
      (concat
       (for [field (keys domain) :when (not (#{:id :label :paths :packages :topics} field))]
         (str prefix "未知字段 " (name field)))
       (when-not (cli-support/valid-id? (:id domain)) [(str prefix "id 缺失或格式无效")])
       (when (and (contains? domain :label) (not (text? (:label domain)))) [(str prefix "label 必须是非空字符串")])
       (for [field [:paths :packages :topics] :when (and (contains? domain field) (not (text-list? (get domain field))))]
         (str prefix (name field) " 必须是字符串数组"))
       (when-not (some #(seq (get domain %)) [:paths :packages :topics])
         [(str prefix "paths、packages、topics 至少写一项")])))))

(defn- aside-errors [index aside]
  (let [prefix (str "aside 第 " (inc index) " 项：")]
    (if-not (map? aside)
      [(str prefix "必须是对象")]
      (concat
       (for [field (keys aside) :when (not (#{:label :classes} field))] (str prefix "未知字段 " (name field)))
       (when-not (text? (:label aside)) [(str prefix "label 必须是非空字符串")])
       (when-not (and (text-list? (:classes aside)) (seq (:classes aside))) [(str prefix "classes 必须是非空字符串数组")])))))

(defn- grouping-errors
  "顶层分组配置：entryLayers、domainSubsystems 为子系统 id 数组，domains、aside 为对象数组。"
  [data]
  (concat
   (for [field [:entryLayers :domainSubsystems] :when (and (contains? data field) (not (text-list? (get data field))))]
     (str (name field) " 必须是子系统 id 字符串数组"))
   (for [field [:domains :aside] :when (and (contains? data field) (not (sequential? (get data field))))]
     (str (name field) " 必须是数组"))
   (when (sequential? (:domains data))
     (concat (apply concat (map-indexed domain-errors (:domains data)))
             (for [[id n] (frequencies (keep :id (filter map? (:domains data)))) :when (> n 1)]
               (str "domains 中 id 重复：" id))))
   (when (sequential? (:aside data)) (apply concat (map-indexed aside-errors (:aside data))))))

(defn- top-level-errors [data]
  (concat
   (when-not (map? data) ["规则文件必须是 JSON 对象"])
   (when (map? data)
     (concat
      (for [field (keys data) :when (not (contains? top-fields field))] (str "未知顶层字段 " (name field)))
      (when-not (= 1 (:version data)) ["version 必须为 1"])
      (for [id (:disableBuiltin data) :when (not (contains? (builtin-ids) id))] (str "disableBuiltin 中没有内置规则 " id))
      (grouping-errors data)))))
