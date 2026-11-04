;; 职责：验证内置规则与项目规则识别入口：HTTP 路由拼接、接口注解继承、类级 RPC、消息与定时、覆盖与警告。
;; 核心入口：Speclj 入口识别测试。

(ns arch-view.entries.core-spec
  (:require [arch-view.entries.fixtures :as fixtures :refer [java]]
            [speclj.core :refer :all]))

(def ^:private web "import org.springframework.web.bind.annotation.*;\n")

(describe "内置 Spring 规则"
  (it "类前缀、常量、多路径、method 枚举拼成 HTTP 入口，并定位到处理方法"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Paths.java" (java "p" "public interface Paths { String BASE = \"/api\"; }")
                    "src/main/java/p/OrderController.java"
                    (java "p" web
                          "@RestController\n@RequestMapping(Paths.BASE + \"/orders/\")\n"
                          "public class OrderController {\n"
                          "  @GetMapping(\"/{id}\") public String get(Long id) { return \"\"; }\n"
                          "  @PostMapping({\"/a\", \"b\"}) public void create() {}\n"
                          "  @RequestMapping(path = \"/x\", method = RequestMethod.PUT) public void put() {}\n"
                          "  @RequestMapping(\"/any\") public void any() {}\n"
                          "  public void helper() {}\n}\n")})
          entries (fixtures/by-label scanned)]
      (should= #{"GET /api/orders/{id}" "POST /api/orders/a" "POST /api/orders/b" "PUT /api/orders/x" "ANY /api/orders/any"}
               (set (keys entries)))
      (should= {:path "src/main/java/p/OrderController.java" :startLine 6 :endLine 6 :symbol "OrderController#get"}
               (:anchor (entries "GET /api/orders/{id}")))
      (should= "p.OrderController" (:module (entries "GET /api/orders/{id}")))
      (should= {:id "HTTP" :label "HTTP" :source "builtin"} (:channel (entries "GET /api/orders/{id}")))
      (should= "builtin:spring-mvc" (:rule (entries "GET /api/orders/{id}")))))

  (it "不是控制器的接口（如 Feign 客户端）不算入口；控制器继承接口上的映射"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Client.java" (java "p" web "public interface Client { @GetMapping(\"/remote\") String call(); }")
                    "src/main/java/p/Api.java" (java "p" web "public interface Api { @GetMapping(\"/api/ping\") String ping(); }")
                    "src/main/java/p/ApiController.java"
                    (java "p" web "@RestController public class ApiController implements Api { public String ping() { return \"\"; } }")})]
      (should= ["GET /api/ping"] (map :label (:entries scanned)))
      (should= "ApiController#ping" (get-in (first (:entries scanned)) [:anchor :symbol]))))

  (it "同一文件多个顶层类型时，入口挂到处理类自己的模块"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Web.java"
                    (java "p" web "@RestController public class Web { @GetMapping(\"/w\") public void w() {} }\n"
                          "interface Zeta {}\n")})]
      (should= "p.Web" (:module (first (:entries scanned))))))

  (it "识别 JAX-RS、@Scheduled、Kafka、main 与 CommandLineRunner"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Res.java"
                    (java "p" "import javax.ws.rs.*;\n@Path(\"/res\") public class Res { @GET @Path(\"/{id}\") public String get() { return \"\"; } }")
                    "src/main/java/p/Jobs.java"
                    (java "p" "import org.springframework.scheduling.annotation.Scheduled;\n"
                          "import org.springframework.kafka.annotation.KafkaListener;\n"
                          "public class Jobs {\n"
                          "  @Scheduled(cron = \"0 0 2 * * ?\") public void nightly() {}\n"
                          "  @Scheduled(fixedRate = 5000) public void tick() {}\n"
                          "  @KafkaListener(topics = {\"t1\", \"t2\"}, groupId = \"g\") public void on(String m) {}\n"
                          "  public static void main(String[] args) {}\n}")
                    "src/main/java/p/Boot.java"
                    (java "p" "import org.springframework.boot.CommandLineRunner;\n"
                          "public class Boot implements CommandLineRunner { @Override public void run(String... args) {} }")})
          entries (fixtures/by-label scanned)]
      (should= #{"GET /res/{id}" "cron 0 0 2 * * ?" "fixedRate 5000" "t1" "t2" "Jobs#main" "Boot#run"}
               (set (keys entries)))
      (should= "Kafka" (get-in entries ["t1" :channel :label]))
      (should= "g" (get-in entries ["t2" :attrs "consumerGroup"]))
      (should= "startup" (:kind (entries "Boot#run"))))))

(def ^:private company-rules
  {:version 1
   :rules [{:id "sofa-tr" :annotation "com.alipay.sofa.runtime.api.annotation.SofaService" :on "class"
            :as "kind:rpc" :channel "RPC-TR" :when {"bindings.bindingType" "tr"}
            :attrs {:interfaceType "service"} :methods {:interfaceFromAttr "interfaceType"}}
           {:id "mobilegw" :annotation "com.co.gw.OperationType" :as "kind:rpc" :channel "RPC-网关"
            :attrs {:value "operation"}}
           {:id "sofamq" :annotation "com.co.mq.MessageConsumer" :as "kind:mq" :channel "MQ"
            :attrs {:topic "topic" :group "consumerGroup" :tag "tag" :orderly "flag:顺序消费" :filter "extra:filter"}}
           {:id "simple-job" :implements "com.co.job.ISimpleJobHandler" :as "kind:schedule"}
           {:id "xx-api" :annotation "com.co.web.XxApi" :as "spring:RestController" :attrs {:value "prefix"}}
           {:id "xx-get" :annotation "com.co.web.XxGet" :as "spring:GetMapping" :attrs {:value "path"}}
           {:id "never" :annotation "com.co.Nope" :as "kind:rpc"}]})

(def ^:private company-files
  {"src/main/java/f/Facade.java" (java "f" "public interface Facade { String save(String e); String query(String id); }")
   "src/main/java/f/FacadeImpl.java"
   (java "f" "import com.alipay.sofa.runtime.api.annotation.*;\n"
         "@SofaService(interfaceType = Facade.class, bindings = {@SofaServiceBinding(bindingType = \"tr\")})\n"
         "public class FacadeImpl implements Facade { public String save(String e) { return e; }\n"
         "  public String query(String id) { return id; } public void helper() {} }")
   "src/main/java/f/LocalImpl.java"
   (java "f" "import com.alipay.sofa.runtime.api.annotation.SofaService;\n"
         "@SofaService(interfaceType = Facade.class) public class LocalImpl implements Facade {\n"
         "  public String save(String e) { return e; } public String query(String id) { return id; } }")
   "src/main/java/g/TaskRpc.java"
   (java "g" "import com.co.gw.OperationType;\n"
         "public interface TaskRpc { String P = \"app.task\"; @OperationType(P + \".list\") String list(); }")
   "src/main/java/g/TaskRpcImpl.java" (java "g" "public class TaskRpcImpl implements TaskRpc { public String list() { return \"\"; } }")
   "src/main/java/g/TaskRpcImpl2.java" (java "g" "public class TaskRpcImpl2 implements TaskRpc { public String list() { return \"\"; } }")
   "src/main/java/g/Orphan.java" (java "g" "import com.co.gw.OperationType;\npublic interface Orphan { @OperationType(\"x.none\") String none(); }")
   "src/main/java/m/Consumers.java"
   (java "m" "import com.co.mq.MessageConsumer;\npublic class Consumers {\n"
         "  @MessageConsumer(topic = \"TP_A\", group = \"G1\", orderly = true) public void a(String m) {}\n"
         "  @MessageConsumer(topic = {\"TP_B\", \"TP_C\"}, group = \"G2\", filter = \"new\") public void b(String m) {}\n}")
   "src/main/java/m/Other.java"
   (java "m" "import com.co.mq.MessageConsumer;\npublic class Other {\n"
         "  @MessageConsumer(topic = \"TP_A\", group = \"G3\", tag = Tags.X) public void c(String m) {}\n}")
   "src/main/java/j/Base.java" (java "j" "import com.co.job.ISimpleJobHandler;\npublic abstract class Base implements ISimpleJobHandler {}")
   "src/main/java/j/RepairJob.java" (java "j" "public class RepairJob extends Base { @Override public void handle(Object c) {} }")
   "src/main/java/w/Ctl.java"
   (java "w" "import com.co.web.*;\n@XxApi(\"/app\") public class Ctl { @XxGet(\"/main\") public String main() { return \"\"; } }")
   "src/main/java/w/LegacyHandler.java" (java "w" "public class LegacyHandler { public void handle() {} }")})

(describe "项目规则"
  (with scanned (fixtures/scan-project company-files company-rules))

  (it "SOFA 只收 tr 绑定，方法取 interfaceType 接口；只有 jvm 绑定的服务不是入口"
    (let [entries (filter #(= "RPC-TR" (get-in % [:channel :label])) (:entries @scanned))]
      (should= #{"Facade#save" "Facade#query"} (set (map :label entries)))
      (should= #{"FacadeImpl#save" "FacadeImpl#query"} (set (map #(get-in % [:anchor :symbol]) entries)))
      (should= {:id "RPC-TR" :label "RPC-TR" :source "rule"} (:channel (first entries)))))

  (it "接口方法上的注解落到实现类，多实现标 multi-impl，无实现标 no-impl"
    (let [entries (group-by :label (:entries @scanned))]
      (should= #{"TaskRpcImpl#list" "TaskRpcImpl2#list"}
               (set (map #(get-in % [:anchor :symbol]) (entries "app.task.list"))))
      (should (every? #(some #{"multi-impl"} (:warnings %)) (entries "app.task.list")))
      (should= ["no-impl"] (:warnings (first (entries "x.none"))))
      (should= "Orphan#none" (get-in (first (entries "x.none")) [:anchor :symbol]))))

  (it "消息入口按 topic 拆分，标签来自 flag/extra，未解析常量带警告"
    (let [entries (group-by :label (:entries @scanned))]
      (should= ["顺序消费"] (:tags (first (filter #(= "G1" (get-in % [:attrs "consumerGroup"])) (entries "TP_A")))))
      (should= 2 (count (entries "TP_A")))
      (should= ["filter=new"] (:tags (first (entries "TP_B"))))
      (should= 1 (count (entries "TP_C")))
      (should= ["unresolved-attr"] (:warnings (first (filter #(= "G3" (get-in % [:attrs "consumerGroup"])) (entries "TP_A")))))))

  (it "implements 规则经由抽象基类命中具体子类，抽象类本身不算入口"
    (should= ["RepairJob#handle"]
             (map :label (filter #(= "schedule" (:kind %)) (:entries @scanned)))))

  (it "公司 HTTP 注解映射到 spring 语义后与 Spring 写法一致"
    (should (some #(= "GET /app/main" (:label %)) (:entries @scanned))))

  (it "同一方法项目规则覆盖内置规则，并记录覆盖"
    (should= #{{:handler "f.FacadeImpl#save" :project ["sofa-tr"] :builtin ["sofa-service"]}
               {:handler "f.FacadeImpl#query" :project ["sofa-tr"] :builtin ["sofa-service"]}}
             (set (:overrides @scanned))))

  (it "疑似遗漏列出名字像入口却没有入口的具体类"
    (should= ["w.LegacyHandler"] (map :type (:suspects @scanned)))))

(describe "其它情况"
  (it "项目源码里定义的注解按元注解展开"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/MyApi.java" (java "p" web "@RestController @RequestMapping public @interface MyApi { String value(); }")
                    "src/main/java/p/Ctl.java" (java "p" web "@MyApi(\"/meta\") public class Ctl { @GetMapping(\"/x\") public void x() {} }")})]
      (should= ["GET /meta/x"] (map :label (:entries scanned)))))

  (it "多个通配导入都可能提供规则注解时不计入口并报告歧义"
    (let [scanned (fixtures/scan-project
                   {"src/main/java/p/Jobs.java"
                    (java "p" "import org.springframework.scheduling.annotation.*;\nimport other.*;\n"
                          "public class Jobs { @Scheduled(cron = \"x\") public void a() {} }")})]
      (should= [] (:entries scanned))
      (should= "src/main/java/p/Jobs.java:4" (:location (first (:ambiguous scanned))))))

  (it "非 Java 项目返回空入口并标明不支持"
    (let [scanned (fixtures/scan-project {"src/app/core.clj" "(ns app.core)"})]
      (should= [] (:entries scanned))
      (should (:unsupported scanned)))))
