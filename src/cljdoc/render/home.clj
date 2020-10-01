(ns cljdoc.render.home
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.render.search :as search]
            [cljdoc.util :as util]))

(def tagline
  "is a website building & hosting documentation for Clojure/Script libraries")

(defn footer []
  [:div.b--light-gray.pa4.tc.f4.fw3
   [:p "cljdoc is created by its " [:a.link.blue {:href (util/github-url :contributors)} "contributors"]
    ". Say hi in " [:a.link.blue {:href "https://clojurians.slack.com/messages/C8V0BQ0M6/"} "#cljdoc"] " on "
    [:a.link.blue {:href "http://clojurians.net/"} "Slack"] ". Report issues on " [:a.link.blue {:href (util/github-url :home)} "GitHub"] "."]
   [:p "Support cljdoc on " [:a.link.blue {:href "https://opencollective.com/cljdoc"} "OpenCollective"] "."]])

(defn feature-block [{:keys [title text link link-text]}]
  [:div.dtc-l.br.b--light-gray.pa4
   [:h2.ma0.fw4 title]
   [:span.w3.bb.b--blue.bw3.dib " "]
   [:p text]
   [:p [:a.link.blue {:href link} link-text]]])

(defn home []
  (->> [:div.pt4
        [:div.mt5-ns.mw7.center.pa4.pa0-l
         [:h1.ma0
          [:span.dn "cljdoc beta"]
          [:img {:src "/cljdoc-logo.svg" :alt "cljdoc logo" :width "150px"}]]
         [:p.f2-ns.f3.mv3.w-90-l.lh-copy tagline]
         (search/search-form)
         [:p.lh-copy "Read "
          [:a.link.blue {:href (util/github-url :rationale)} "the rationale"] "."
          " Check out some examples here: "
          [:a.link.blue.nowrap {:href "/d/rum/rum/CURRENT"} "rum"] ", "
          [:a.link.blue.nowrap {:href "/d/lambdaisland/kaocha/CURRENT"} "kaocha"] ", "
          [:a.link.blue.nowrap {:href "/d/metosin/reitit/CURRENT"} "reitit"] "."
          "or"
          ]
         [:div#recent-docs-visited]
         [:script {:src "/js/recents.js"}]
         ]

        [:div.mt5-ns.bg-white
         (into [:div.dt-l.dt--fixed.bb.bt.b--light-gray.lh-copy]
               (map feature-block
                    [{:title "Automated Docs"
                      :text "cljdoc builds documentation for new releases that are pushed to Clojars within minutes. Ever forgot to update your docs after a release? No more."
                      :link-text "→ Basic Setup"
                      :link (util/github-url :userguide/basic-setup)}
                     {:title "Articles & More"
                      :text "Seamless integration of articles and tutorials from Markdown and Asciidoc source files."
                      :link-text "→ Articles"
                      :link (util/github-url :userguide/articles)}
                     {:title "Offline Docs"
                      :text "Download documentation for any project in a zip file for easy offline use while travelling or swinging in your hammock."
                      :link-text "→ Offline Docs"
                      :link (util/github-url :userguide/offline-docs)}
                     {:title "Specs, Examples, ..."
                      :text "In the future cljdoc may incorporate more than just API docs and articles. Specs and examples are high on the list."
                      :link-text "→ Roadmap"
                      :link (util/github-url :roadmap)}]))]

        (let [container :div.dtc-l.pa5-ns.pa4.white.b--light-blue.hover-bg-black-10.bg-animate
              button    :a.dib.bg-white.blue.ph3.pv2.br1.no-underline.f5.fw5.grow]
          [:div.bg-blue.dt-l.dt--fixed.lh-copy
           [container
            [:h2.f5.ma0.fw5.ttu.tracked.o-70 "Library Authors"]
            [:p.f4.mb4.fw3 "Learn how to publish your docs to cljdoc, integrate tutorials and other material and add a badge to your project's Readme."]
            [button {:href (util/github-url :userguide/authors)} "Documentation for Library Authors →"]]
           [container
            [:h2.f5.ma0.fw5.ttu.tracked.o-70 "Library Users"]
            [:p.f4.mb4.fw3 "Learn where to find documentation, how to download it for offline use and more."]
            [button {:href (util/github-url :userguide/users)} "Documentation for Library Users →"]]])

        [:div.tc.ttu.tracked.bb.b--light-gray.pa4
         [:span.o-50.dark-blue.f6 "↓ More Features ↓"]]

        [:div.dt-l.dt--fixed.bb.b--light-gray
         [:div.dtc-l.v-mid.ph5-ns.ph4
          [:p.f2-ns.f3.fw3.lh-copy.near-black "Docs for every Clojure library available at a predictable, consistent location."]]
         [:div.dtc-l.v-mid.ph5-ns.ph4.pv5-l
          [:pre.lh-copy
           [:code
            "(str \"https://cljdoc.org/d/\""
            "\n     (:group-id your-project) \"/\""
            "\n     (:artifact-id your-project) \"/\""
            "\n     (:version your-project))"]]]]

        [:div.dt-l.dt--fixed.bb.b--light-gray
         [:div.dtc-l.v-mid.ph5-ns.ph4
          [:p.f2-ns.f3.fw3.lh-copy.near-black "Platform-aware documentation, clearly indicating when things differ between Clojure & Clojurescript."]]
         [:div.dtc-l.v-mid.bt.bn-l.b--light-gray.pl5-l
          [:img.db {:src "/platform-differences-example.png" :alt "Example of platform aware documentation with rum.core"}]]]

        [:div.dt-l.dt--fixed.bb.b--light-gray
         [:div.dtc-l.v-mid.ph5-ns.ph4
          [:p.f2-ns.f3.fw3.lh-copy.near-black "Documentation links are always tied to a specific version and old versions are kept available."]]
         (into [:div.dtc-l.v-mid.ph5-ns.ph4.pv5-l.lh-copy.mb4]
               (->> ["2.1.0" "2.1.1" "2.1.2" "2.1.3"]
                    (map (fn [v] [:code.db [:span.gray.o-70 "https://cljdoc.org/d/bidi/bidi/"] v]))))]

        [:div.dt-l.dt--fixed.bb.b--light-gray
         [:div.dtc-l.v-mid.ph5-ns.ph4
          [:p.f2-ns.f3.fw3.lh-copy.near.black "Open Source, so the community can work together to improve or even fork cljdoc."]]
         [:div.dtc-l.v-mid.ph5-ns.ph4.pv5-l.lh-copy.mb4
          [:a.dt.link.black.center.o-80.glow {:href (util/github-url :home)}
           [:img.dtc.v-mid.mr3 {:src "https://microicon-clone.vercel.app/github/38"}]
           [:span.dtc.v-mid.f3 "cljdoc/cljdoc"]]]]

        (footer)]
       (layout/page {:title "cljdoc — documentation for Clojure/Script libraries"
                     :responsive? true})
       (str)))
