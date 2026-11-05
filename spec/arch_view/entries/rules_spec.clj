;; 职责：验证入口规则文件的读取、错误提示、关闭内置规则与规则规范化。
;; 核心入口：Speclj 规则测试。

(ns arch-view.entries.rules-spec
  (:require [arch-view.entries.fixtures :as fixtures]
            [arch-view.entries.methods :as methods]
            [arch-view.entries.rules :as sut]
            [arch-view.workspace :as workspace]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(defn- load-with [rules]
  (fixtures/with-project {} rules
    #(sut/load-rules (workspace/create {} % {}))))

(describe "入口规则文件"
  (it "没有规则文件时只用内置规则"
    (let [loaded (load-with nil)]
      (should= "missing" (:status loaded))
      (should= ".arch-view/entry-rules.json" (:file loaded))
      (should (some #(= "spring-mvc" (:id %)) (:rules loaded)))
      (should= [] (:project loaded))))

  (it "项目规则排在内置规则前，disableBuiltin 整组关闭"
    (let [loaded (load-with {:version 1 :disableBuiltin ["spring-scheduled"]
                             :rules [{:id "xx-get" :annotation "com.xx.XxGet" :as "spring:GetMapping"
                                      :attrs {:value "path"}}]})]
      (should= "ok" (:status loaded))
      (should= "xx-get" (:id (first (:rules loaded))))
      (should-not (some #(= "spring-scheduled" (:id %)) (:rules loaded)))
      (should= {:id "xx-get" :origin :project :role :http-mapping :kind :http :on :method
                :http-method "GET" :attrs {"value" "path"} :match {:annotation "com.xx.XxGet"}}
               (select-keys (first (:project loaded)) [:id :origin :role :kind :on :http-method :attrs :match]))))

  (it "类型规则默认作用于类，kind 规则默认机制名取入口类型"
    (let [rule (first (:project (load-with {:version 1 :rules [{:id "job" :implements "x.Job" :as "kind:schedule"}]})))]
      (should= :class (:on rule))
      (should= "定时" (:channel rule))))

  (it "逐条报告未知字段、取值错误、重复 id，并保留内置规则"
    (let [loaded (load-with {:version 2 :extra 1 :disableBuiltin ["nope"]
                             :rules [{:id "a" :annotation "x.A" :implements "x.B" :as "kind:rpc"}
                                     {:id "a" :annotation "x.A" :as "spring:Nope" :color "red"}
                                     {:id "Bad_Id" :annotation "x.C" :as "kind:mq" :on "field"
                                      :attrs {:topic "topics"} :methods {:interfaceMethods "yes"}
                                      :when {:k {:like "x"}}}]})
          text (str/join "\n" (:errors loaded))]
      (should= "error" (:status loaded))
      (doseq [part ["未知顶层字段 extra" "version 必须为 1" "disableBuiltin 中没有内置规则 nope"
                    "规则 id 重复：a" "annotation、implements、extends、xml 必须且只能写一个"
                    "as 取值无效：spring:Nope" "未知字段 color" "id 缺失或格式无效" "on 只能是 class 或 method"
                    "attrs.topic 的语义键无效：topics" "methods 写法无效" "when.k 条件无效"]]
        (should-contain part text))
      (should (some #(= "spring-mvc" (:id %)) (:rules loaded)))))

  (it "xml 与 dispatch 规则逐项校验，写对时规范为字符串键"
    (let [bad (load-with {:version 1
                          :rules [{:id "x1" :as "spring:GetMapping" :on "method" :label 3
                                   :xml {:files [] :each "a//b" :fields {:topic "../$bad"}}
                                   :dispatch {:key "" :handlers {:implements "x.H"}}}
                                  {:id "x2" :annotation "x.A" :as "kind:mq" :dispatch {:key "{k}" :handlers {}}}]})
          text (str/join "\n" (:errors bad))
          good (load-with {:version 1
                           :rules [{:id "ok" :as "kind:mq" :label "{topic}"
                                    :xml {:files ["spring/*.xml"] :each "sofa:consumer" :fields {:topic "@topic" :bean "@ref"}}
                                    :dispatch {:key "{topic}" :handlers {:implements "x.H" :keyMethod "key"
                                                                          :entryMethods {:interfaceMethods true}}}}]})]
      (doseq [part ["xml.files 必须是非空字符串数组" "xml.each 必须是元素名路径" "xml.fields.topic 路径无效"
                    "xml 规则的 as 只能是 kind:*" "xml 规则不支持 on 和 when" "label 必须是字符串"
                    "dispatch.key 必须是模板字符串" "dispatch.handlers 需要 implements 和 keyMethod"
                    "规则 x2：dispatch 目前只能与 xml 一起使用"]]
        (should-contain part text))
      (should= "ok" (:status good))
      (should= {:xml {:files ["spring/*.xml"] :each "sofa:consumer" :fields {"topic" "@topic" "bean" "@ref"}}}
               (:match (first (:project good))))
      (should= {:key "{topic}" :handlers {:implements "x.H" :keyMethod "key" :entryMethods {"interfaceMethods" true}}}
               (:dispatch (first (:project good))))))

  (it "兼容原有 methods 写法，并规范化 names 与 XML entryMethods"
    (doseq [selector [nil "public" {:interfaceMethods true} {:interfaceFromAttr "interfaceType"}
                      {:implementsOf "x.Handler"} {:annotation "x.Entry"} {:names ["handle" "onEvent"]}]]
      (let [loaded (load-with {:version 1
                              :rules [{:id "job" :implements "x.Job" :as "kind:schedule" :methods selector}
                                      {:id "mq" :as "kind:mq"
                                       :xml {:files ["spring/*.xml"] :each "consumer" :fields {:bean "@ref"}}
                                       :dispatch {:key "{bean}" :handlers {:implements "x.Handler" :keyMethod "key"
                                                                          :entryMethods selector}}}]})
            normalized (if (map? selector) (update-keys selector name) selector)]
        (should= "ok" (:status loaded))
        (should= normalized (:methods (first (:project loaded))))
        (should= normalized (get-in loaded [:project 1 :dispatch :handlers :entryMethods])))))

  (it "names 必须是非空的合法 Java 方法名数组，且不能混用其它 selector"
    (doseq [selector (concat (map #(hash-map :names %) [nil "handle" 3 {} [] [nil] [3] [""] [" \t"]
                                                       ["handle" nil] ["handle" ""] ["handle*"]
                                                       ["handle(Object)"] [" handle"] ["class"] ["1handle"]])
                            [{:names ["handle"] :interfaceMethods true}])]
      (let [loaded (load-with {:version 1
                              :rules [{:id "job" :implements "x.Job" :as "kind:schedule" :methods selector}
                                      {:id "mq" :as "kind:mq"
                                       :xml {:files ["spring/*.xml"] :each "consumer" :fields {:bean "@ref"}}
                                       :dispatch {:key "{bean}" :handlers {:implements "x.Handler" :keyMethod "key"
                                                                          :entryMethods selector}}}]})
            text (str/join "\n" (:errors loaded))]
        (should= "error" (:status loaded))
        (should-contain "规则 job：methods 写法无效" text)
        (should-contain "规则 mq：dispatch.handlers.entryMethods 写法无效" text)
        (should= [] (:project loaded))
        (should (some #(= "spring-mvc" (:id %)) (:rules loaded))))))

  (it "分组配置逐项校验，写对时原样放在 :config"
    (let [bad (load-with {:version 1 :rules [] :entryLayers "rpc" :domainSubsystems ["aq" 3]
                          :domains [{:id "Order" :color "red" :paths "/api"} {:id "a" :label ""} {:id "a" :topics ["t"]} 5]
                          :aside [{:label "测试"} {:label "x" :classes ["A"] :when 1}]})
          text (str/join "\n" (:errors bad))
          config {:entryLayers ["rpc"] :domainSubsystems ["aq"]
                  :domains [{:id "order" :label "订单" :paths ["/api/orders"] :packages ["com.x.order"] :topics ["order-paid"]}]
                  :aside [{:label "测试/示例" :classes ["RpcDemo" "*Demo"]}]}
          good (load-with (merge {:version 1 :rules []} config))]
      (doseq [part ["entryLayers 必须是子系统 id 字符串数组" "domainSubsystems 必须是子系统 id 字符串数组"
                    "domains 第 1 项（Order）：未知字段 color" "domains 第 1 项（Order）：id 缺失或格式无效"
                    "domains 第 1 项（Order）：paths 必须是字符串数组" "domains 第 2 项（a）：label 必须是非空字符串"
                    "domains 第 2 项（a）：paths、packages、topics 至少写一项" "domains 第 4 项：必须是对象"
                    "domains 中 id 重复：a" "aside 第 1 项：classes 必须是非空字符串数组" "aside 第 2 项：未知字段 when"]]
        (should-contain part text))
      (should= "ok" (:status good))
      (should= config (:config good))))

  (it "JSON 无法解析时报告读取错误"
    (let [loaded (load-with "{not json")]
      (should= "error" (:status loaded))
      (should-contain "规则文件无法读取" (first (:errors loaded))))))

(describe "按名称选择入口方法"
  (with-stubs)
  (with ctx {:index {:types {}} :supers (stub :supers {:return #{}})})
  (with handler-type {:fqn "x.Handler"
              :methods [{:name "handle" :params [] :public? true :override? true}
                        {:name "handle" :params [{:name "event"}] :public? true}
                        {:name "handle" :params [{:name "event"} {:name "context"}] :public? false}
                        {:name "handle" :params [] :public? true :static? true}
                        {:name "handle" :params [] :public? true :abstract? true}
                        {:name "onEvent" :params [] :public? true}
                        {:name "Handle" :params [] :public? true}
                        {:name "getName" :params [] :public? true :override? true}
                        {:name "getThreadPool" :params [] :public? true :override? true}
                        {:name "getHandlerKey" :params [] :public? true :override? true}]})

  (it "精确选择当前类的所有公有非静态非抽象重载，不选择 getter 或同名非 public 方法"
    (should= [["handle" 0] ["handle" 1] ["onEvent" 0]]
             (map methods/method-key (methods/select-methods @ctx @handler-type {"names" ["handle" "onEvent"]} {})))
    (should-not-have-invoked :supers))

  (it "名称不匹配时返回空，不回退到接口或 public 方法，也不支持通配与签名"
    (doseq [names [["missing"] ["handle*"] ["handle(Object)"]]]
      (should= [] (methods/select-methods @ctx @handler-type {"names" names} {})))
    (should-not-have-invoked :supers))

  (it "外部任务接口的七个实现仅生成七个 handle 入口"
    (let [files (into {} (for [n (range 7)]
                          [(str "src/main/java/j/Job" n ".java")
                           (fixtures/java "j" "import external.ISimpleJobHandler;\n"
                                          "public class Job" n " implements ISimpleJobHandler {\n"
                                          "  @Override public void handle(Object event) {}\n"
                                          "  private void handle(String event) {}\n"
                                          "  protected void handle(Integer event) {}\n"
                                          "  static void handle(Long event) {}\n"
                                          "  @Override public String getName() { return \"job\"; }\n"
                                          "  @Override public Object getThreadPool() { return null; }\n}")]))
          scanned (fixtures/scan-project files {:version 1
                                                :rules [{:id "simple-job" :implements "external.ISimpleJobHandler"
                                                         :as "kind:schedule" :methods {:names ["handle"]}}]})]
      (should= "ok" (get-in scanned [:rules :status]))
      (should= 7 (count (:entries scanned)))
      (should= #{"handle"} (set (map #(get-in % [:handler :method]) (:entries scanned))))))

  (it "保留默认、interfaceMethods 和 public selector 的原有选择行为"
    (doseq [selector [nil {"interfaceMethods" true}]]
      (should= ["handle" "getName" "getThreadPool" "getHandlerKey"]
               (map :name (methods/select-methods @ctx @handler-type selector {}))))
    (should= ["handle" "handle" "onEvent" "Handle" "getName" "getThreadPool" "getHandlerKey"]
             (map :name (methods/select-methods @ctx @handler-type "public" {})))))
