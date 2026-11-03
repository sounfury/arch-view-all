(ns arch-view.web.documents-spec
  (:require [arch-view.web.documents :as documents]
            [speclj.core :refer :all]))

(describe "文件头说明"
  (it "读取文件开头的块注释"
    (should= "职责：入口。" (documents/header-description "/** 职责：入口。 */\nclass A")))

  (it "读取文件开头的 // 行注释"
    (should= "章会话：维护读章状态。\n工具调用在这里校验。"
             (documents/header-description
              "// 章会话：维护读章状态。\r\n// 工具调用在这里校验。\r\npackage a\r\n\r\nclass A\r\n")))

  (it "跳过 package 和 import 读取类说明"
    (should= "诊断接口：确认外部依赖可用。"
             (documents/header-description
              (str "package com.example.web\n\n"
                   "import org.springframework.web.bind.annotation.RestController\n"
                   "import java.util.List;\n\n"
                   "/** 诊断接口：确认外部依赖可用。 */\n"
                   "@RestController\nclass DiagnosticsController"))))

  (it "合并版权头与 import 之后的类说明，只去掉版权样板和块标签"
    (should= "xxxx\n\n体脂秤主接口请求级观测收口"
             (documents/header-description
              (str "/*\n * xxxx\n * Copyright (c) 2004-2026 All Rights Reserved.\n */\n"
                   "package com.a;\nimport java.util.Map;\n"
                   "/**\n * 体脂秤主接口请求级观测收口\n *\n * @version A.java, 2026-10-04\n */\npublic class A {}")))
    ;; 版权头里的公司署名也是样板；没有 HTML 分段时保留作者的换行。
    (should= "职责：入口。\n核心入口：run。"
             (documents/header-description
              (str "/*\n * Alipay.com Inc.\n * Copyright (c) 2004-2026 All Rights Reserved.\n */\n"
                   "package a;\n/**\n * 职责：入口。\n * 核心入口：run。\n *\n * @author wsz\n */\nclass A {}"))))

  (it "Javadoc 的 HTML 段落、列表、代码和链接标签转成 Markdown"
    (should= (str "①渲染：actionId＝`main_result`，每请求打印一条日志，biz_data 携带 result；\n"
                  "②模块：复用 取值方法 与 `ChannelService`，口径一致；display 只表示是否渲染。\n\n"
                  "- 返回 `List<String>`\n- 泛型 List\\<String> 原样\n\n"
                  "```\n@Autowired\nFoo foo;\n```")
             (documents/header-description
              (str "/**\n"
                   " * <p>①渲染：actionId＝{@code main_result}，\n"
                   " * 每请求打印一条日志，biz_data 携带\n"
                   " * result；\n"
                   " * ②模块：复用 {@link com.a.ChannelService#get() 取值方法} 与 {@link ChannelService}，\n"
                   " * 口径一致；\n"
                   " * display 只表示是否渲染。</p>\n"
                   " * <ul>\n"
                   " *   <li>返回 <code>List&lt;String&gt;</code></li>\n"
                   " *   <li>泛型 List<String> 原样</li>\n"
                   " * </ul>\n"
                   " * <pre>{@code\n"
                   " *   @Autowired\n"
                   " *   Foo foo;\n"
                   " * }</pre>\n"
                   " * @since 1.0\n"
                   " */\nclass A {}"))))

  (it "类说明不在声明之后时不误取方法注释"
    (should-be-nil (documents/header-description
                    "package a\n\nclass A {\n  /** 方法说明 */\n  fun f() {}\n}"))))
