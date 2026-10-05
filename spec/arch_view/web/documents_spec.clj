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

  (it "类说明不在声明之后时不误取方法注释"
    (should-be-nil (documents/header-description
                    "package a\n\nclass A {\n  /** 方法说明 */\n  fun f() {}\n}"))))
