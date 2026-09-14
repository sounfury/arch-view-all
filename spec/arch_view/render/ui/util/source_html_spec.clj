(ns arch-view.render.ui.util.source-html-spec
  (:require [clojure.string :as str]
            [arch-view.render.ui.util.source-html :as sut]
            [speclj.core :refer :all]))

(describe "source html"
  (it "Given Java source, when displayed, then preserves semicolons and escapes generics"
    (let [html (sut/source->html "App.java" "List<String> x; int y = 1; // comment")]
      (should= true (str/includes? html "List&lt;String&gt; x; int y = 1; // comment"))
      (should= false (str/includes? html "<span class='cmt'>"))))

  (it "renders Python safely without Clojure comment or keyword styling"
    (let [html (sut/source->html "module.py" "if x < 2: print(x); print('ok') # note")]
      (should= true (str/includes? html "x &lt; 2:"))
      (should= true (str/includes? html "; print('ok') # note"))
      (should= false (str/includes? html "<span class='cmt'>"))
      (should= false (str/includes? html "<span class='kw'>"))))

  (it "escapes html-sensitive characters"
    (should= "&lt;a&amp;b&gt;" (sut/html-escape "<a&b>")))

  (it "colorizes strings, keywords, and comments"
    (let [html (sut/colorize-clojure-html "(println \"x\" :k) ; c")]
      (should= true (str/includes? html "class='str'"))
      (should= true (str/includes? html "class='kw'"))
      (should= true (str/includes? html "class='cmt'"))))

  (it "does not treat html escape entity semicolons as comments"
    (let [html (sut/colorize-clojure-html "(visibility-port/set-combat-visibility-port! (->MovementCombatVisibilityPort))")]
      (should= true (str/includes? html "-&gt;MovementCombatVisibilityPort"))
      (should= false (str/includes? html "-&gt;<span class='cmt'>;MovementCombatVisibilityPort"))))

  (it "does not treat semicolons inside strings as comments"
    (let [html (sut/colorize-clojure-html "(println \"x;still-string\") ; outside")]
      (should= true (str/includes? html "class='str'"))
      (should= true (str/includes? html "class='cmt'>; outside</span>"))))

  (it "handles escaped quote sequences while scanning comments"
    (let [html (sut/colorize-clojure-html "(println \"a\\\\\\\"b\") ; c")]
      (should= true (str/includes? html "class='str'"))
      (should= true (str/includes? html "class='cmt'>; c</span>"))))

  (it "renders tab-expanded source lines and wrapper html"
    (let [lines (sut/source-lines->html "\t:ok\n")
          doc (sut/source->html "demo" "\t:ok\n")]
      (should= true (str/includes? lines "class='ln'>1</td>"))
      (should= true (str/includes? lines "<pre>  <span class='kw'>:ok</span></pre>"))
      (should= true (str/includes? doc "<div class='hdr'>demo</div>"))
      (should= true (str/includes? doc "charset='UTF-8'"))
      (should= true (str/includes? doc "PingFang SC"))))

  (it "handles nil inputs for line rendering and escaping paths"
    (let [escaped (sut/colorize-clojure-html nil)
          lines (sut/source-lines->html nil)]
      (should= "" escaped)
      (should= true (str/includes? lines "&nbsp;")))))
