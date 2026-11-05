;; 职责：验证 XML 声明的入口与按 key 分发：订阅对接 handler、来源位置、两处不一致的警告与 XML 安全读取。
;; 核心入口：Speclj XML 入口测试。

(ns arch-view.entries.declared-spec
  (:require [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.report :as report]
            [arch-view.entries.xml-source :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(def ^:private consumer-xml
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       "<!DOCTYPE beans [<!ENTITY secret SYSTEM \"file:///etc/hostname\">]>\n"
       "<beans xmlns=\"http://www.springframework.org/schema/beans\" xmlns:sofa=\"http://sofa\">\n"
       "  <sofa:consumer id=\"c2c\" group=\"S_c2c\">\n"
       "    <sofa:listener ref=\"commonListener\"/>\n"
       "    <sofa:channels>\n"
       "      <sofa:channel value=\"TP_C_USER\">\n"
       "        <sofa:event eventType=\"direct\" eventCode=\"EC_PRIZE\"/>\n"
       "      </sofa:channel>\n"
       "    </sofa:channels>\n"
       "  </sofa:consumer>\n"
       "  <sofa:consumer id=\"bill\" group=\"S_bill\">\n"
       "    <sofa:listener ref=\"commonListener\"/>\n"
       "    <sofa:channels>\n"
       "      <sofa:channel value=\"TP_C_BILL\">\n"
       "        <sofa:event eventType=\"direct\" eventCode=\"EC_benefit\"/>\n"
       "        <sofa:event eventType=\"direct\" eventCode=\"EC_lost\"/>\n"
       "      </sofa:channel>\n"
       "    </sofa:channels>\n"
       "  </sofa:consumer>\n"
       "  <note>&secret;</note>\n"
       "</beans>\n"))

(def ^:private handler-interface "com.co.msg.UniformEventMessageHandler")

(defn- handler [name key]
  (java "h" "import com.co.msg.UniformEventMessageHandler;\n"
        "public class " name " implements UniformEventMessageHandler {\n"
        "  public String getHandlerKey() {\n    return " key ";\n  }\n"
        "  @Override public void handle(Object event) {}\n}"))

(def ^:private files
  {"app/src/main/resources/spring/uniform-event.xml" consumer-xml
   "app/src/main/java/h/Keys.java" (java "h" "public interface Keys { String BILL = \"TP_C_BILL\"; }")
   "app/src/main/java/h/CommonListener.java"
   (java "h" "import com.co.msg.UniformEventMessageListener;\n"
         "public class CommonListener implements UniformEventMessageListener { @Override public void onUniformEvent(Object e) {} }")
   "app/src/main/java/h/SendPrizeMessageHandler.java" (handler "SendPrizeMessageHandler" "\"TP_C_USER_EC_PRIZE\"")
   "app/src/main/java/h/HealthGiftPackImpl.java" (handler "HealthGiftPackImpl" "Keys.BILL + \"_EC_benefit\"")
   "app/src/main/java/h/CopyHandler.java" (handler "CopyHandler" "\"TP_C_USER_EC_PRIZE\"")
   "app/src/main/java/h/StaleHandler.java" (handler "StaleHandler" "\"TP_OLD_EC_GONE\"")
   "app/src/main/java/h/DynamicHandler.java" (handler "DynamicHandler" "compute()")})

(def ^:private rules
  {:version 1
   :rules [{:id "msgbroker" :as "kind:mq" :channel "MsgBroker"
            :xml {:files ["**/spring/*.xml"]
                  :each "sofa:consumer/sofa:channels/sofa:channel/sofa:event"
                  :fields {:consumerGroup "../../../@group" :topic "../@value" :eventCode "@eventCode"
                           :bean "../../../sofa:listener/@ref"}}
            :label "{topic} / {eventCode}"
            :dispatch {:key "{topic}_{eventCode}"
                       :handlers {:implements handler-interface :keyMethod "getHandlerKey"
                                  :entryMethods {:interfaceMethods true}}}}]})

(describe "XML 声明与按 key 分发"
  (with scanned (fixtures/scan-project files rules))

  (it "每个订阅一个入口，挂在 key 一致的 handler 上，标签取 XML 字段"
    (let [entries (group-by :label (:entries @scanned))]
      (should= #{"TP_C_USER / EC_PRIZE" "TP_C_BILL / EC_benefit" "TP_C_BILL / EC_lost"} (set (keys entries)))
      (let [bill (first (entries "TP_C_BILL / EC_benefit"))]
        (should= "HealthGiftPackImpl#handle" (get-in bill [:anchor :symbol]))
        (should= {"consumerGroup" "S_bill" "topic" "TP_C_BILL" "eventCode" "EC_benefit"} (:attrs bill))
        (should= {:id "MsgBroker" :label "MsgBroker" :source "rule"} (:channel bill))
        (should= [{:kind "xml" :anchor {:path "app/src/main/resources/spring/uniform-event.xml" :startLine 12 :endLine 20}}
                  {:kind "dispatch-key" :key "TP_C_BILL_EC_benefit" :anchor {:path "app/src/main/java/h/HealthGiftPackImpl.java" :startLine 5 :endLine 5
                                                 :symbol "HealthGiftPackImpl#getHandlerKey"}}
                  {:kind "dispatcher" :type "h.CommonListener" :method "onUniformEvent"
                   :anchor {:path "app/src/main/java/h/CommonListener.java" :startLine 3 :endLine 3 :symbol "CommonListener"}}]
                 (:sources bill)))))

  (it "订阅找不到 handler 时挂在分发者上并报警，key 重复的 handler 各报警"
    (let [entries (group-by :label (:entries @scanned))
          lost (first (entries "TP_C_BILL / EC_lost"))]
      (should= "CommonListener#onUniformEvent" (get-in lost [:anchor :symbol]))
      (should= ["orphan-subscription"] (:warnings lost))
      (should= #{"SendPrizeMessageHandler#handle" "CopyHandler#handle"}
               (set (map #(get-in % [:anchor :symbol]) (entries "TP_C_USER / EC_PRIZE"))))
      (should (every? #(some #{"duplicate-key"} (:warnings %)) (entries "TP_C_USER / EC_PRIZE")))))

  (it "check 报告没有订阅的 handler、key 无法求值，分发者不算疑似遗漏"
    (let [warnings (into {} (map (juxt :code identity)) (:warnings (report/check @scanned)))]
      (should= ["TP_OLD_EC_GONE — h.StaleHandler (app/src/main/java/h/StaleHandler.java:5)"]
               (get-in warnings ["unsubscribed-handler" :items]))
      (should-contain "h.DynamicHandler" (first (get-in warnings ["key-unresolved" :items])))
      (should= 1 (get-in warnings ["orphan-subscription" :count]))
      (should= 2 (get-in warnings ["duplicate-key" :count]))
      (should-not (some #(= "h.CommonListener" (:type %)) (:suspects @scanned)))))

  (it "entryMethods names 复用方法选择，带 Override 的 key getter 不成为订阅入口"
    (let [files (update-vals files #(str/replace % "  public String getHandlerKey()"
                                               "  @Override public String getHandlerKey()"))
          rules (assoc-in rules [:rules 0 :dispatch :handlers :entryMethods] {:names ["handle"]})
          scanned (fixtures/scan-project files rules)
          entries (:entries scanned)]
      (should= "ok" (get-in scanned [:rules :status]))
      (should= 4 (count entries))
      (should= #{"SendPrizeMessageHandler#handle" "CopyHandler#handle" "HealthGiftPackImpl#handle"
                 "CommonListener#onUniformEvent"}
               (set (map #(get-in % [:anchor :symbol]) entries)))
      (should (every? #(some (fn [source] (= "dispatch-key" (:kind source))) (:sources %))
                      (remove #(some #{"orphan-subscription"} (:warnings %)) entries)))))

  (it "XML 无 dispatch 时也复用 methods names，只选事务回查方法"
    (let [scanned (fixtures/scan-project
                   {"src/main/resources/spring/publisher.xml"
                    "<beans><bean id='callback' class='h.Callback'/><publisher tx-callback='callback'/></beans>"
                    "src/main/java/h/Callback.java"
                    (java "h" "public class Callback {\n"
                          "  public void onUniformEventTxSynchronized(Object event) {}\n"
                          "  protected void onUniformEventTxSynchronized(String event) {}\n"
                          "  public String getHandlerKey() { return \"key\"; }\n}")}
                   {:version 1 :rules [{:id "tx-check" :as "kind:mq"
                                        :xml {:files ["spring/*.xml"] :each "publisher" :fields {:bean "@tx-callback"}}
                                        :methods {:names ["onUniformEventTxSynchronized"]}}]})]
      (should= "ok" (get-in scanned [:rules :status]))
      (should= ["Callback#onUniformEventTxSynchronized"]
               (map #(get-in % [:anchor :symbol]) (:entries scanned)))))

  (it "bean 找不到类时报告；没有 dispatch 时入口挂在 bean 类上"
    (let [scanned (fixtures/scan-project
                   (assoc files "app/src/main/resources/spring/service.xml"
                          (str "<beans xmlns:sofa=\"x\">\n"
                               "  <bean id=\"giftFacade\" class=\"h.CommonListener\"/>\n"
                               "  <sofa:service ref=\"giftFacade\" interface=\"x.Facade\"/>\n"
                               "  <sofa:service ref=\"missingBean\" interface=\"x.Other\"/>\n</beans>"))
                   {:version 1 :rules [{:id "xml-service" :as "kind:rpc" :channel "RPC-TR"
                                        :xml {:files ["spring/service.xml"] :each "sofa:service"
                                              :fields {:bean "@ref" :service "@interface"}}}]})
          warnings (into {} (map (juxt :code identity)) (:warnings (report/check scanned)))]
      (should= ["CommonListener#onUniformEvent"] (map #(get-in % [:anchor :symbol]) (:entries scanned)))
      (should= ["missingBean (app/src/main/resources/spring/service.xml:4)"] (get-in warnings ["bean-unresolved" :items])))))

(describe "XML 读取"
  (it "不展开外部实体，路径支持 .. 与 @属性"
    (fixtures/with-project {"a.xml" consumer-xml}
      (fn [root]
        (let [tree (xml/parse-file (io/file root "a.xml"))
              unit (first (xml/units tree "sofa:consumer/sofa:channels/sofa:channel/sofa:event"))]
          (should= "" (apply str (mapcat :children (filter #(= "note" (:tag %)) (:children tree)))))
          (should= "S_c2c" (xml/field-value (:chain unit) "../../../@group"))
          (should= "commonListener" (xml/field-value (:chain unit) "../../../sofa:listener/@ref"))
          (should= nil (xml/field-value (:chain unit) "../missing/@x"))
          (should= 4 (get-in unit [:declaration :start]))))))

  (it "资源目录取自源码目录，**/ 通配也匹配顶层文件"
    (fixtures/with-project {"m/src/main/resources/top.xml" "<a/>" "m/src/main/resources/spring/x.xml" "<a/>"}
      (fn [root]
        (let [roots (xml/resource-roots (io/file root) ["m/src/main/java"])
              files (xml/xml-files roots)]
          (should= 1 (count roots))
          (should= #{"top.xml" "spring/x.xml"} (set (map :relative (xml/matching-files files ["**/*.xml"]))))
          (should= ["spring/x.xml"] (map :relative (xml/matching-files files ["spring/*.xml"]))))))))
