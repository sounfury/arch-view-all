;; 职责：验证架构小节的前端兼容匹配规则，防止错误包、标题或代码围栏命中。
;; 核心入口：架构文档匹配 Speclj 示例。

(ns arch-view.context.architecture-spec
  (:require [arch-view.context.architecture :as architecture]
            [arch-view.context.fixtures :as fixture]
            [arch-view.workspace :as workspace]
            [speclj.core :refer :all]))

(describe "上下文中的架构职责匹配"
  (with ws (fixture/project))
  (after (fixture/cleanup! @ws))

  (it "探索链接优先于标题标识，URL 解码并统一点与斜线分段"
    (fixture/put! (:root @ws) "ARCHITECTURE.md"
                  (str "# 项目\n\n## 核心子系统与职责\n\n"
                       "### service · 模型服务（`src/demo/web/`）\n\n- **职责**：生成数据。\n\n"
                       "[探索模型](#module=web%2Emodel)\n\n"
                       "### webby · 无关相似前缀\n\n[探索](#module=webby)\n"))
    (let [entries (architecture/matching-sections @ws (workspace/resolve-target @ws "demo.web.model"))]
      (should= "service · 模型服务（src/demo/web/）" (:title (first entries)))
      (should= "web/model" (:module (first entries)))
      (should-not (some #(= "webby" (:module %)) entries))))

  (it "没有探索链接时沿用前端标题正向与反向标识"
    (fixture/put! (:root @ws) "ARCHITECTURE.md"
                  (str "# 项目\n\n## 职责\n\n### 1. web · 网页（`src/demo/web/`）\n\n职责正文。\n\n"
                       "### 模型（web.model）\n\n模型正文。\n"))
    (let [entries (architecture/matching-sections @ws (workspace/resolve-target @ws "demo.web.model"))]
      (should= ["web" "web/model" "web/model"] (mapv :module entries))))

  (it "存在核心子系统一节时忽略其他章节中的模块链接及代码围栏中的伪标题"
    (fixture/put! (:root @ws) "ARCHITECTURE.md"
                  (str "# 项目\n\n## 项目说明\n\n### web · 旁注\n\n[探索](#module=web)\n\n"
                       "## 核心子系统与职责\n\n### web · 正式职责\n\n"
                       "- **实现状态**：未完成。\n\n```md\n### util · 伪标题\n```\n\n"
                       "[探索](#module=web)\n\n## 数据流\n\n### web · 其他\n\n[探索](#module=web)\n"))
    (let [entries (architecture/matching-sections @ws (workspace/resolve-target @ws "demo.web.model"))]
      (should= ["web · 正式职责" "service · 模型服务"] (mapv :title entries))
      (should= "未完成" (:status (first entries)))))

  (it "包文档不会套用到其他目录的同名模块"
    (should= ["util · 工具"]
             (mapv :title (architecture/matching-sections @ws (workspace/resolve-target @ws "demo.util")))))

  (it "namespace-root-depth 为零时项目链接保留完整命名空间"
    (fixture/put! (:root @ws) "ARCHITECTURE.md"
                  "# 项目\n\n## 核心子系统与职责\n\n### 网页（demo.web）\n\n完整命名空间。\n")
    (let [ws (assoc-in @ws [:architecture :guidance :namespace-root-depth] 0)]
      (should= ["demo/web" "demo/web/model"]
               (mapv :module (architecture/matching-sections ws (workspace/resolve-target ws "demo.web.model")))))))
