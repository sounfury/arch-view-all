;; 回归测试：检查软件包分组、逐层展开和依赖汇总是否符合已有行为。

(ns arch-view.domain.architecture-projection-spec
  (:require [arch-view.domain.architecture-projection :as sut]
            [speclj.core :refer :all]))

(defn- edge-present?
  [edges from to type count]
  (boolean (some #(= {:from from :to to :type type :count count}
                     (select-keys % [:from :to :type :count]))
                 edges)))

(describe "architecture projection"
  (it "projects mixed leaf nodes, source labels, and scoped display edges"
    (let [architecture {:graph {:nodes #{"empire.config.core"
                                         "empire.config.domain"
                                         "empire.config.domain.inner.a"
                                         "empire.config.domain.inner.b"
                                         "empire.config.units"
                                         "empire.other.core"}
                               :module->source-file {"empire.config.domain" "/tmp/domain_impl.cljc"
                                                     "empire.config.core" "/tmp/core.cljc"
                                                     "empire.config.units" "/tmp/units.cljc"}
                               :abstract-modules #{"empire.config.units"}}
                        :classified-edges #{{:from "empire.config.units" :to "empire.config.domain" :type :abstract}
                                            {:from "empire.config.domain" :to "empire.config.units" :type :direct}
                                            {:from "empire.config.domain.inner.a" :to "empire.config.domain.inner.b" :type :direct}
                                            {:from "empire.config.domain.inner.b" :to "empire.config.domain.inner.a" :type :direct}
                                            {:from "empire.other.core" :to "empire.config.core" :type :direct}
                                            {:from "empire.config.core" :to "empire.other.core" :type :direct}}}
          root-view (sut/view-architecture architecture [])
          config-view (sut/view-architecture architecture ["config"]) 
          config-nodes (:nodes (:graph config-view))
          mixed-leaf (sut/mixed-leaf-node-id "domain")]
      (should= true (contains? (:nodes (:graph root-view)) "config"))
      (should= true (get (:module->cycle? root-view) "config"))

      (should= true (contains? config-nodes mixed-leaf))
      (should= true (contains? config-nodes "domain"))
      (should= true (get (:module->leaf? config-view) mixed-leaf))
      (should= false (get (:module->leaf? config-view) "domain"))

      (should= :abstract (get (:module->kind config-view) "units"))
      (should= "domain_impl" (get (:module->display-label config-view) mixed-leaf))
      (should= "config.domain_impl.cljc" (get (:module->full-name config-view) mixed-leaf))
      (should= true (get (:module->cycle? config-view) mixed-leaf))
      (should= true (boolean (some #{"config.domain.inner.a->config.domain.inner.b->config.domain.inner.a"
                                     "config.domain.inner.b->config.domain.inner.a->config.domain.inner.b"}
                                   (:cycle-lines config-view))))

      (should= true (edge-present? (:display-edges config-view)
                                   "empire.other.core" "core" :direct 1))
      (should= true (edge-present? (:display-edges config-view)
                                   "core" "empire.other.core" :direct 1))))

  (it "marks namespace cycle only when a subtree has a cycle"
    (let [architecture {:graph {:nodes #{"empire.alpha.api"
                                         "empire.alpha.impl"
                                         "empire.beta.core"}}
                        :classified-edges #{{:from "empire.alpha.api" :to "empire.alpha.impl" :type :direct}
                                            {:from "empire.beta.core" :to "empire.alpha.api" :type :direct}}}
          root-view (sut/view-architecture architecture [])
          alpha-view (sut/view-architecture architecture ["alpha"])]
      (should= false (get (:module->cycle? root-view) "alpha"))
      (should= false (get (:module->cycle? root-view) "beta"))
      (should= false (get (:module->cycle? alpha-view) "api"))
      (should= false (get (:module->cycle? alpha-view) "impl"))))

  (it "reports deep cycles from the root and rebuilds the index for a new architecture"
    (let [nodes #{"app.a.x.One" "app.a.x.Two" "app.a.y.Three" "app.b.Four"}
          cyclic {:guidance {:namespace-root-depth 0}
                  :graph {:nodes nodes}
                  :classified-edges #{{:from "app.a.x.One" :to "app.a.x.Two" :type :direct}
                                      {:from "app.a.x.Two" :to "app.a.x.One" :type :direct}
                                      {:from "app.b.Four" :to "app.a.y.Three" :type :direct}
                                      {:from "app.b.Four" :to "missing.Module" :type :direct}}}
          acyclic (update cyclic :classified-edges disj {:from "app.a.x.Two" :to "app.a.x.One" :type :direct})
          root-view (sut/view-architecture cyclic [])]
      (should= true (get (:module->cycle? root-view) "app"))
      (should= 1 (count (:cycle-lines root-view)))
      (should (re-find #"^app\.a\.x\.(One|Two)->" (first (:cycle-lines root-view))))
      (should= false (get (:module->cycle? (sut/view-architecture cyclic ["app" "a"])) "y"))
      (should= #{{:from "b" :to "a" :type :direct :count 1}}
               (:classified-edges (sut/view-architecture cyclic ["app"])))
      ;; 索引按架构对象缓存，重新分析得到的新架构不能沿用旧的循环结论。
      (should= false (get (:module->cycle? (sut/view-architecture acyclic [])) "app"))
      (should= [] (:cycle-lines (sut/view-architecture acyclic [])))
      (should= true (get (:module->cycle? (sut/view-architecture cyclic [])) "app"))))

  (it "handles helper functions for mixed leaf identifiers"
    (let [node (sut/mixed-leaf-node-id "acceptance")]
      (should= "acceptance|file" node)
      (should= true (sut/mixed-leaf-node? node))
      (should= false (sut/mixed-leaf-node? "acceptance"))
      (should= "acceptance" (sut/node-child-name node))
      (should= "acceptance" (sut/node-child-name "acceptance"))))

  (it "splits namespace segments after the project root"
    (should= ["ui" "quil" "core"] (sut/namespace-segments "empire.ui.quil.core"))
    (should= [] (sut/namespace-segments "empire")))

  (it "aggregates edge metadata and upgrades to abstract"
    (should= {:type :direct :count 1}
             (#'sut/aggregate-edge nil :direct))
    (should= {:type :direct :count 3}
             (#'sut/aggregate-edge {:type :direct :count 2} :direct))
    (should= {:type :abstract :count 4}
             (#'sut/aggregate-edge {:type :abstract :count 3} :direct))
    (should= {:type :abstract :count 4}
             (#'sut/aggregate-edge {:type :direct :count 3} :abstract))))
