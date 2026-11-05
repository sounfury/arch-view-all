;; 职责：用独立 Java 源码复现嵌套回调 TreePath 丢失及 ExecuteTemplate 匿名实现业务链丢失。
(ns arch-view.entries.callback-mock-spec
  (:require [arch-view.entries.chain :as chain]
            [arch-view.entries.core :as entries]
            [arch-view.entries.fixtures :as fixtures :refer [java]]
            [arch-view.entries.java-calls :as calls]
            [arch-view.entries.rules :as rules]
            [arch-view.entries.web :as web]
            [clojure.string :as str]
            [speclj.core :refer :all]))

(defn- analyze-project [files f]
  (fixtures/with-project files
    (fn [root]
      (let [ws (fixtures/workspace-of root)
            ctx (entries/analysis-context ws (rules/load-rules ws))
            analyzed (calls/analyze ws ctx)]
        (f ctx analyzed (chain/build ctx analyzed))))))

(defn- entry [type method]
  {:id (str type "#" method) :handler {:type type :method method :params []}})

(defn- methods-in [slice]
  (set (map (juxt :type :method) (:nodes slice))))

(def ^:private template-files
  {"src/main/java/p/ExecuteCallback.java"
   (java "p" "public interface ExecuteCallback<T> { void checkParams(T request); String execute(T request); }")
   "src/main/java/p/AbstractExecuteCallback.java"
   (java "p" "public abstract class AbstractExecuteCallback<T> implements ExecuteCallback<T> { public void checkParams(T request) {} }")
   "src/main/java/p/ExecuteTemplate.java"
   (java "p" "public class ExecuteTemplate { public static <T> String execute(T request, ExecuteCallback<T> callback) { callback.checkParams(request); return callback.execute(request); } }")
   "src/main/java/p/HttpExecuteCallback.java"
   (java "p" "public abstract class HttpExecuteCallback<T> { public void checkParams(T request) {} public abstract String execute(T request); }")
   "src/main/java/p/HttpExecuteTemplate.java"
   (java "p" "public class HttpExecuteTemplate { public static <T> String execute(T request, HttpExecuteCallback<T> callback) { callback.checkParams(request); return callback.execute(request); } }")
   "src/main/java/p/RecommendationService.java"
   (java "p" "public class RecommendationService { public String recommend(String r) { return r; } public void validate(String r) {} public void unused() {} }")
   "src/main/java/p/ScaleService.java"
   (java "p" "public class ScaleService { public String issue(String r) { return r; } }")
   "src/main/java/p/AppRecRpcImpl.java"
   (java "p" "public class AppRecRpcImpl { RecommendationService service;\n"
         "  public String appRec() { return ExecuteTemplate.execute(\"request\", new AbstractExecuteCallback<>() {\n"
         "    @Override public final String execute(String request) { return service.recommend(request); }\n"
         "    @Override public void checkParams(String request) { service.validate(request); }\n"
         "    private void helper() { service.unused(); }\n"
         "  }); }\n}")
   "src/main/java/p/BodyScaleBridgeController.java"
   (java "p" "public class BodyScaleBridgeController { ScaleService service;\n"
         "  public String issueBridgeMain() { return HttpExecuteTemplate.execute(\"request\", new HttpExecuteCallback<String>() {\n"
         "    @Override public String execute(String request) { return service.issue(request); }\n"
         "  }); }\n}")})

(describe "Java 回调 mock 回归"
  (it "嵌套 lambda、方法引用与匿名回调混用后，后续调用仍保留正确 TreePath 和所属方法"
    (analyze-project
     {"src/main/java/p/Mixed.java"
      (java "p" "import java.util.List; public class Mixed {\n"
            "  void run() { List.of(\"x\").forEach(x -> { invoke(() -> { ping(); invoke(this::pong); }); });\n"
            "    invoke(new Runnable() { @Override public void run() { pong(); } }); after(); }\n"
            "  void invoke(Runnable callback) { callback.run(); } void ping() {} void pong() {} void after() {}\n}")}
     (fn [_ analyzed graph]
       (let [root "p.Mixed#run()"
             from-root (filter #(= root (:from %)) (:calls analyzed))
             visible (chain/slice graph (entry "p.Mixed" "run") {:depth 10 :limit 100})]
         (should (every? #(and (:from %) (get-in % [:anchor :path]) (get-in % [:anchor :startLine])) (:calls analyzed)))
         (should= 1 (count (filter #(= "after" (:method %)) from-root)))
         (should (some #(and (= "ping" (:method %)) (:reason %)) from-root))
         (should (some #(and (= "pong" (:method %)) (re-find #"方法引用，执行时机未确定" (str (:reason %)))) from-root))
         (should-contain ["p.Mixed" "after"] (methods-in visible))
         (should-contain ["p.Mixed" "ping"] (methods-in visible))
         (should (some #(and (= "p.Mixed#after()" (:to %)) (= "solid" (:style %))) (:edges visible)))))))

  (it "RPC 与 HTTP 模板中的匿名 execute/checkParams 可达，回调连接为虚线且不会把未调用 helper 当入口"
    (analyze-project template-files
      (fn [ctx analyzed graph]
        (let [rpc (chain/slice graph (entry "p.AppRecRpcImpl" "appRec") {:depth 10 :limit 100})
              http (chain/slice graph (entry "p.BodyScaleBridgeController" "issueBridgeMain") {:depth 10 :limit 100})
              anonymous (filter :anonymous? (vals (get-in ctx [:index :types])))
              callback-edges (filter #(and (= "p.AppRecRpcImpl#appRec()" (:from %))
                                           (= "execute" (get-in graph [:nodes (:to %) :method]))) (:edges rpc))]
          (should= 2 (count anonymous))
          (should= 2 (count (set (map :fqn anonymous))))
          (should (every? #(re-find #"\$anonymous@\d+" (:fqn %)) anonymous))
          (should-contain ["p.RecommendationService" "recommend"] (methods-in rpc))
          (should-contain ["p.RecommendationService" "validate"] (methods-in rpc))
          (should-not-contain ["p.RecommendationService" "unused"] (methods-in rpc))
          (should-contain ["p.ScaleService" "issue"] (methods-in http))
          (should-not-contain ["p.RecommendationService" "recommend"] (methods-in http))
          (should-not-contain ["p.ScaleService" "issue"] (methods-in rpc))
          (should (some #(and (= "dashed" (:style %)) (re-find #"回调参数" (:reason %))) callback-edges))
          (should (some #(and (get-in ctx [:index :types (:type %) :anonymous?]) (= "execute" (:method %))) (vals (:nodes analyzed))))))))

  (it "模板与回调父类来自缺失依赖时，仍保留显式 Override 回调的业务调用"
    (analyze-project (dissoc template-files "src/main/java/p/ExecuteTemplate.java" "src/main/java/p/ExecuteCallback.java"
                                           "src/main/java/p/AbstractExecuteCallback.java")
      (fn [_ _ graph]
        (let [visible (chain/slice graph (entry "p.AppRecRpcImpl" "appRec") {:depth 8 :limit 100})]
          (should-contain ["p.RecommendationService" "recommend"] (methods-in visible))
          (should-contain ["p.RecommendationService" "validate"] (methods-in visible))
          (should-not-contain ["p.RecommendationService" "unused"] (methods-in visible))))))

  (it "网页入口链路返回各自业务节点，而不只返回相同的模板骨架"
    (let [files (-> template-files
                    (update "src/main/java/p/AppRecRpcImpl.java"
                            #(str/replace % "public String appRec()" "@external.Operation(\"mock.rec\") public String appRec()"))
                    (update "src/main/java/p/BodyScaleBridgeController.java"
                            #(str/replace % "public String issueBridgeMain()" "@external.Operation(\"mock.scale\") public String issueBridgeMain()")))
          rules {:version 1 :rules [{:id "operation" :annotation "external.Operation" :as "kind:rpc" :attrs {:value "operation"}}]}]
      (fixtures/with-project files rules
        (fn [root]
          (let [state (fixtures/workspace-of root)
                data ((get web/routes ["GET" "/api/entries"]) state {})
                by-label (into {} (map (juxt :label identity)) (:entries data))
                request (fn [label] ((get web/routes ["GET" "/api/entry/chain"]) state {"id" (:id (by-label label))}))
                rpc (request "mock.rec") http (request "mock.scale")]
            (should-contain ["p.RecommendationService" "recommend"] (methods-in rpc))
            (should-contain ["p.RecommendationService" "validate"] (methods-in rpc))
            (should-contain ["p.ScaleService" "issue"] (methods-in http))
            (should-not-contain ["p.ScaleService" "issue"] (methods-in rpc))
            (should-not-contain ["p.RecommendationService" "recommend"] (methods-in http)))))))

  (it "同名局部类及同一行的多个匿名类使用不同标识，重复解析稳定，局部回调保留业务调用"
    (let [files {"src/main/java/p/Locals.java"
                 (java "p" "public class Locals { void accept(Runnable r) {} void business() {}\n"
                       "  public void first() { class Callback implements Runnable { public void run() { business(); } } accept(new Callback()); }\n"
                       "  public void second() { class Callback implements Runnable { public void run() { business(); } } accept(new Callback()); }\n"
                       "  public void third() { accept(new Runnable() { public void run() { business(); } }); accept(new Runnable() { public void run() { business(); } }); }\n}")}
          first-ids (atom nil)]
      (dotimes [_ 2]
        (analyze-project files
          (fn [ctx analyzed graph]
            (let [locals (filter :local? (vals (get-in ctx [:index :types])))
                  ids (set (map :id (vals (:nodes analyzed))))
                  visible (chain/slice graph (entry "p.Locals" "first") {:depth 8 :limit 100})]
              (should= 4 (count locals))
              (should= 4 (count (set (map :fqn locals))))
              (should-contain ["p.Locals" "business"] (methods-in visible))
              (if @first-ids (should= @first-ids ids) (reset! first-ids ids)))))))))
