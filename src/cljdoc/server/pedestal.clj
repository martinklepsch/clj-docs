(ns cljdoc.server.pedestal
  "Weaves together the various HTTP components of cljdoc.

  Routing and HTTP concerns are handled via Pedestal and
  endpoints are implemented as [intereceptors](http://pedestal.io/reference/interceptors).
  For more details on routing see [[cljdoc.server.routes]].

  The main aspects handlded via HTTP (and thus through this namespace) are:

  - Rendering documentation pages (see [[view]])
  - Rendering build logs (see [[show-build]] & [[all-builds]])
  - Rendering a sitemap (see [[sitemap-interceptor]])
  - Handling build requests (see [[request-build]], [[full-build]] & [[circle-ci-webhook]])
  - Redirecting to newer releases (see [[resolve-version-interceptor]] & [[jump-interceptor]])"
  (:require [cljdoc.render.build-req :as render-build-req]
            [cljdoc.render.build-log :as render-build-log]
            [cljdoc.render.index-pages :as index-pages]
            [cljdoc.render.home :as render-home]
            [cljdoc.render.search :as render-search]
            [cljdoc.render.meta :as render-meta]
            [cljdoc.render.error :as error]
            [cljdoc.render.offline :as offline]
            [cljdoc.render :as html]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.pedestal-util :as pu]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.api :as api]
            [cljdoc.server.search.api :as search-api]
            [cljdoc.server.sitemap :as sitemap]
            [cljdoc.storage.api :as storage]
            [cljdoc.util :as util]
            [cljdoc.util.pom :as pom]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sentry :as sentry]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [co.deps.ring-etag-middleware :as etag]
            [integrant.core :as ig]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.error :as pedestal-error]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]))

(def render-interceptor
  "This interceptor will render the documentation page for the current route
  based on the cache-bundle that has been injected into the context previously
  by the [[artifact-data-loader]] interceptor.

  If the request is for the root page (e.g. /d/group/artifact/0.1.0) this interceptor
  will also lookup the first article that's part of the cache-bundle and return a 302
  redirecting to that page."
  (interceptor/interceptor
   {:name  ::render
    :enter (fn render-doc [{:keys [cache-bundle] :as ctx}]
             (let [path-params (-> ctx :request :path-params)
                   page-type   (-> ctx :route :route-name)]
               (if-let [first-article-slug (and (= page-type :artifact/version)
                                                (-> cache-bundle :version :doc first :attrs :slug))]
                 ;; instead of rendering a mostly white page we
                 ;; redirect to the README/first listed article
                 (let [location (routes/url-for :artifact/doc :params (assoc path-params :article-slug first-article-slug))]
                   (assoc ctx :response {:status 302, :headers {"Location" location}}))

                 (if cache-bundle
                   (pu/ok-html ctx (html/render page-type path-params {:cache-bundle cache-bundle
                                                                       :pom (::pom-info ctx)
                                                                       :last-build (::last-build ctx)}))
                   (let [resp {:status 404
                               :headers {"Content-Type" "text/html"}
                               :body (str (render-build-req/request-build-page path-params))}]
                     (assoc ctx :response resp))))))}))

(def doc-slug-parser
  "Further process the `article-slug` URL segment by splitting on `/` characters.

  This is necessary because we want to allow arbitrary nesting in user
  provided doctrees and Pedestal's router will return a single string
  for everything that comes after a wildcard path segment."
  (interceptor/interceptor
   {:name ::doc-slug-parser
    :enter (fn [ctx]
             (->> (string/split (get-in ctx [:request :path-params :article-slug])  #"/")
                  ;; I feel like pedestal should take care of this url-decoding
                  ;; https://github.com/cljdoc/cljdoc/issues/113
                  (map #(java.net.URLDecoder/decode % "UTF-8"))
                  (assoc-in ctx [:request :path-params :doc-slug-path])))}))

(defn available-docs-denormalized
  "Return all available documents with one item per version
  (i.e. in the same format as expected by index-pages/versions-tree)"
  [searcher {:keys [group-id artifact-id] :as artifact-ent}]
  (let [match-keys (cond
                     artifact-id [:artifact-id :group-id]
                     group-id [:group-id]
                     :else nil)
        matches #(= (select-keys % match-keys) (select-keys artifact-ent match-keys))]
    (->> (search-api/all-docs searcher)
         (filter matches)
         (mapcat (fn [artifact]
                   (map
                     #(-> artifact (dissoc :versions) (assoc :version %))
                     (:versions artifact)))))))

(defn versions-data
  "Return matching documents with version info in a tree"
  [searcher store route-name {{:keys [path-params params]} :request}]
  (let [{:keys [group-id] :as artifact-ent} path-params]
    (->> (if (:all params)
           (available-docs-denormalized searcher artifact-ent)
           (case route-name
             (:artifact/index :group/index)
             ;; NOTE: We do not filter by artifact-id b/c in the UI we want
             ;; to show "Other artifacts under the <XY> group"
             (storage/list-versions store group-id)

             :cljdoc/index
             (storage/all-distinct-docs store)))
         (index-pages/versions-tree))))


(defn index-pages
  "Return a list of interceptors suitable to render an index page appropriate for the provided `route-name`.
  `route-name` can be either `:artifact/index`,  `:group/index` or `:cljdoc/index`."
  [searcher store route-name]
  [(pu/coerce-body-conf
     (fn html-render-fn [ctx]
       (let [artifact-ent (-> ctx :request :path-params)
             versions-data (-> ctx :response :body)]
         (case route-name
           :artifact/index (index-pages/artifact-index artifact-ent versions-data)
           :group/index (index-pages/group-index artifact-ent versions-data)
           :cljdoc/index (index-pages/full-index versions-data)))))
   (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
   (interceptor/interceptor
    {:name ::releases-loader
     :enter (fn releases-loader-inner [ctx]
              (pu/ok ctx (versions-data searcher store route-name ctx)))})])

(defn artifact-data-loader
  "Return an interceptor that loads all data from `store` that is
  relevant for the artifact identified via the entity map in `:path-params`."
  [store]
  (interceptor/interceptor
   {:name ::artifact-data-loader
    :enter (fn artifact-data-loader-inner [ctx]
             (let [params (-> ctx :request :path-params)
                   pom-data (::pom-info ctx)
                   bundle-params (assoc params :dependency-version-entities (:dependencies pom-data))]
               (log/info "Loading artifact cache bundle for" params (:cache-bundle ctx))
               (if (storage/exists? store params)
                 (assoc ctx :cache-bundle (storage/bundle-docs store bundle-params))
                 ctx)))}))

(defn last-build-loader
  "Load a projects last build into the context"
  [build-tracker]
  (interceptor/interceptor
   {:name ::last-build-loader
    :enter (fn last-build-loader-inner [ctx]
             (let [{:keys [group-id artifact-id version]} (-> ctx :request :path-params)]
               (assoc ctx ::last-build (build-log/last-build build-tracker group-id artifact-id version))))}))

(defn pom-loader
  "Load a projects POM file, parse it and inject some information from it into the context."
  [cache]
  (interceptor/interceptor
   {:name ::pom-loader
    :enter (fn pom-loader-inner [ctx]
             (let [pom-xml-memo (:cljdoc.util.repositories/get-pom-xml cache)
                   params (-> ctx :request :path-params)
                   pom-parsed (pom/parse (pom-xml-memo
                                          (util/clojars-id params)
                                          (:version params)))]
               (assoc ctx ::pom-info {:description (-> pom-parsed pom/artifact-info :description)
                                      :dependencies (-> pom-parsed pom/dependencies-with-versions)})))}))

(def resolve-version-interceptor
  "An interceptor that will look at `:path-params` and try to turn it into an artifact
  entity or redirect of the version is specified in a meta-fasion, i.e. `CURRENT`.

  - If the provided version is `nil` set it to the last known release.
  - If the provided version is `CURRENT` redirect to either a version from the `referer` header
    or the last known version."
  (interceptor/interceptor
   {:name ::resolve-version-interceptor
    :enter (fn resolve-version-interceptor [{:keys [route request] :as ctx}]
             (let [{:keys [project group-id artifact-id version]} (:path-params request)
                   artifact-id     (or artifact-id (util/artifact-id project))
                   group-id        (or group-id (util/group-id project))
                   current?        (= "CURRENT" version)
                   referer-version (some-> request
                                           (get-in [:headers "referer"])
                                           util/uri-path routes/match-route :path-params :version)
                   artifact-entity {:artifact-id artifact-id
                                    :group-id group-id
                                    :version (cond
                                               (nil? version)
                                               (repos/latest-release-version (str group-id "/" artifact-id))

                                               current?
                                               (or referer-version
                                                   (repos/latest-release-version (str group-id "/" artifact-id)))

                                               :else version)}]
               (if current?
                 (assoc ctx :response
                        {:status 307
                         :headers {"Location" (routes/url-for (:route-name route) :path-params (merge (:path-params request) artifact-entity))}})
                 (update-in ctx [:request :path-params] merge artifact-entity))))}))

(defn view
  "Combine various interceptors into an interceptor chain for
  rendering views for `route-name`."
  [storage cache build-tracker route-name]
  (->> [resolve-version-interceptor
        (last-build-loader build-tracker)
        (when (= :artifact/doc route-name) doc-slug-parser)
        (pom-loader cache)
        (artifact-data-loader storage)
        render-interceptor]
       (keep identity)
       (vec)))

(defn redirect-to-build-page
  [ctx build-id]
  {:pre [(some? build-id)]}
  (assoc ctx :response {:status 303 :headers {"Location" (str "/builds/" build-id)}}))

(defn request-build
  "Create an interceptor that will initiate documentation builds based
  on provided form params using `analysis-service` for analysis and tracking
  build progress/state via `build-tracker`."
  [{:keys [analysis-service build-tracker] :as deps}]
  (interceptor/interceptor
   {:name ::request-build
    :enter (fn request-build-handler [ctx]
             (if-let [running (build-log/running-build build-tracker
                                                       (-> ctx :request :form-params :project util/group-id)
                                                       (-> ctx :request :form-params :project util/artifact-id)
                                                       (-> ctx :request :form-params :version))]
               (redirect-to-build-page ctx (:id running))
               (let [build (api/kick-off-build!
                            deps
                            {:project (-> ctx :request :form-params :project)
                             :version (-> ctx :request :form-params :version)})]
                 (redirect-to-build-page ctx (:build-id build)))))}))

(def request-build-validate
  ;; TODO quick and dirty for now
  (interceptor/interceptor
   {:name ::request-build-validate
    :enter (fn request-build-validate [ctx]
             (if (and (some-> ctx :request :form-params :project string?)
                      (some-> ctx :request :form-params :version string?))
               ctx
               (assoc ctx :response {:status 400 :headers {}})))}))

(defn search-interceptor [searcher]
  (interceptor/interceptor
    {:name  ::search
     :enter (fn search-handler [ctx]
              (if-let [q (-> ctx :request :params :q)]
                (pu/ok ctx (search-api/search searcher q))
                (assoc ctx :response {:status 400 :headers {} :body "ERROR: Missing q query param"})))}))

(defn search-suggest-interceptor [searcher]
  (interceptor/interceptor
   {:name  ::search-suggest
    :enter (fn search-suggest-handler [ctx]
             (if-let [q (-> ctx :request :params :q)]
               (assoc ctx :response {:status  200
                                     :headers {"Content-Type" "application/x-suggestions+json"}
                                     :body    (search-api/suggest searcher q)})
               (assoc ctx :response {:status 400 :headers {} :body "ERROR: Missing q query param"})))}))

(defn show-build
  [build-tracker]
  (interceptor/interceptor
   {:name ::build-show
    :enter (fn build-show-render [ctx]
             (if-let [build-info (->> ctx :request :path-params :id
                                      (build-log/get-build build-tracker))]
               (pu/ok ctx build-info)
               ;; Not setting :response implies 404 response
               ctx))}))

(defn all-builds
  [build-tracker]
  (interceptor/interceptor
   {:name ::build-index
    :enter (fn build-index-render [ctx]
             (->> (build-log/recent-builds build-tracker 30)
                  (cljdoc.render.build-log/builds-page)
                  (pu/ok-html ctx)))}))

(defn return-badge [ctx status color]
  (let [url (format "https://badgen.net/badge/cljdoc/%s/%s"
                    (string/replace status  #"/" "%2F")
                    (name color))]
    (->> {:status 200
          :headers {"Content-Type" "image/svg+xml;charset=utf-8"
                    "Cache-Control" (format "public; max-age=%s" (* 30 60))}
          :body (:body (clj-http.lite.client/get url {:headers {"User-Agent" "clj-http-lite"}}))}
         (assoc ctx :response))))

(defn badge-interceptor []
  (interceptor/interceptor
   {:name ::badge
    :leave (fn badge [ctx]
             (log/info "Badge req headers" (-> ctx :request :headers))
             (let [{:keys [version]} (-> ctx :request :path-params)
                   last-build (::last-build ctx)
                   [status color] (cond
                                    (and last-build (not (build-log/api-import-successful? last-build)))
                                    ["API import failed" :red]

                                    (and last-build (not (build-log/git-import-successful? last-build)))
                                    ["Git import failed" :red]

                                    :else
                                    [version :blue])]
               (return-badge ctx status color)))
    :error (fn [ctx _err]
             (let [{:keys [project]} (-> ctx :request :path-params)]
               (return-badge ctx (str "no%20release%20found%20for%20" project) :red)))}))

(defn jump-interceptor []
  (interceptor/interceptor
   {:name ::jump
    :enter (fn jump [ctx]
             (let [{:keys [project artifact-id group-id] :as params} (-> ctx :request :path-params)
                   project (cond project project
                                 artifact-id (util/clojars-id params)
                                 group-id group-id)
                   release (try (repos/latest-release-version project)
                                (catch Exception e
                                  (log/warnf e "Could not find release for %s" project)))]
               (->> (if release
                      {:status 302
                       :headers {"Location" (routes/url-for :artifact/version
                                                            :params
                                                            {:group-id (util/group-id project)
                                                             :artifact-id (util/artifact-id project)
                                                             :version release})}}
                      {:status 404
                       :headers {}
                       :body (format "Could not find release for %s" project)})
                    (assoc ctx :response))))}))

(def etag-interceptor
  (interceptor/interceptor
   {:name ::etag
    :leave (ring-middlewares/response-fn-adapter
            (fn [request _opts]
              (etag/add-file-etag request false)))}))

(def redirect-trailing-slash-interceptor
  ;; Needed because https://github.com/containous/traefik/issues/4247
  (interceptor/interceptor
   {:name ::redirect-trailing-slash
    :leave (fn [ctx]
             (let [uri (-> ctx :request :uri)]
               (cond-> ctx
                 (and (.endsWith uri "/")
                      (not= uri "/"))
                 (assoc :response {:status 301
                                   :headers {"Location" (subs uri 0 (dec (.length uri)))}}))))}))

(def not-found-interceptor
  (interceptor/interceptor
   {:name ::not-found-interceptor
    :leave (fn [context]
             (if-not (http/response? (:response context))
               (assoc context :response {:status 404
                                         :headers {"Content-Type" "text/html"}
                                         :body (error/not-found-404)})
               context))}))

(defn build-sitemap
  "Build a new sitemap if previous one was built longer than 60 minutes ago."
  [{:keys [last-generated sitemap] :as state} storage]
  (let [now (java.util.Date.)]
    (if (or (not last-generated)
            (> (- (.getTime now) (.getTime last-generated)) (* 60 60 1000)))
      ;; Return updated state
      {:last-generated now :sitemap (sitemap/build storage)}
      ;; Return identical state
      state)))

(defn sitemap-interceptor
  [storage]
  (let [state (atom {})]
    (interceptor/interceptor
     {:name  ::sitemap
      :enter #(pu/ok-xml % (:sitemap (swap! state build-sitemap storage)))})))

(def offline-bundle
  "Creates an HTTP response with a zip file containing offline docs
  for the project that has been injected into the context by [[artifact-data-loader]]."
  (interceptor/interceptor
   {:name ::offline-bundle
    :enter (fn offline-bundle [{:keys [cache-bundle] :as ctx}]
             (log/info "Bundling" (str (-> cache-bundle :version-entity :artifact-id) "-"
                                       (-> cache-bundle :version-entity :version) ".zip"))
             (->> (if cache-bundle
                    {:status 200
                     :headers {"Content-Type" "application/zip, application/octet-stream"
                               "Content-Disposition" (format "attachment; filename=\"%s\""
                                                             (str (-> cache-bundle :version-entity :artifact-id) "-"
                                                                  (-> cache-bundle :version-entity :version) ".zip"))}
                     :body (offline/zip-stream cache-bundle)}
                    {:status 404
                     :headers {}
                     :body "Could not find data, please request a build first"})
                  (assoc ctx :response)))}))

(def catch-all-errors
  (pedestal-error/error-dispatch
   [context ex]

  ;; Catch specific errors here. For example,
  ;  [{:exception-type :java.io.FileNotFoundException}]
  ;  (assoc context :response {:status 404
  ;                            :body   {:error (.getMessage ex)}})

   ;; Catch all errors that did not match.
   :else
   (do
     (log/warnf "Caught an exception => %s" (.getMessage ex))
     (assoc context :response {:status 500
                               :body   {:error (.getMessage ex)}}))))

(defn route-resolver
  "Given a route name return a list of interceptors to handle requests
  to that route.

  This has been put into place to better separate route-definitions
  from handler implementation in case route-definitions become
  interesting for ClojureScript where Pededestal can't go.

  For more details see `cljdoc.server.routes`."
  [{:keys [build-tracker storage cache searcher] :as deps}
   {:keys [route-name] :as route}]
  (->> (case route-name
         :home       [(interceptor/interceptor {:name ::home :enter #(pu/ok-html % (render-home/home))})]
         :search     [(interceptor/interceptor {:name ::search :enter #(pu/ok-html % (render-search/search-page %))})]
         :shortcuts  [(interceptor/interceptor {:name ::shortcuts :enter #(pu/ok-html % (render-meta/shortcuts))})]
         :sitemap    [(sitemap-interceptor storage)]
         :show-build [(pu/coerce-body-conf
                        (fn html-render [ctx]
                          (cljdoc.render.build-log/build-page (-> ctx :response :body))))
                      (pu/negotiate-content #{"text/html" "application/edn" "application/json"})
                      (show-build build-tracker)]
         :all-builds [(all-builds build-tracker)]

         :api/search [pu/coerce-body (pu/negotiate-content #{"application/json"}) (search-interceptor searcher)]
         :api/search-suggest [pu/coerce-body (pu/negotiate-content #{"application/x-suggestions+json"}) (search-suggest-interceptor searcher)]

         :ping          [(interceptor/interceptor {:name ::pong :enter #(pu/ok-html % "pong")})]
         :request-build [(body/body-params) request-build-validate (request-build deps)]

         :cljdoc/index    (index-pages searcher storage route-name)
         :group/index     (index-pages searcher storage route-name)
         :artifact/index  (index-pages searcher storage route-name)

         :artifact/version   (view storage cache build-tracker route-name)
         :artifact/namespace (view storage cache build-tracker route-name)
         :artifact/doc       (view storage cache build-tracker route-name)
         :artifact/offline-bundle [(pom-loader cache)
                                   (artifact-data-loader storage)
                                   offline-bundle]

         :artifact/current-via-short-id [(jump-interceptor)]
         :artifact/current [(jump-interceptor)]
         :jump-to-project    [resolve-version-interceptor
                              (jump-interceptor)]
         :badge-for-project  [(badge-interceptor)
                              resolve-version-interceptor
                              (last-build-loader build-tracker)])
       (assoc route :interceptors)))

(defmethod ig/init-key :cljdoc/pedestal [_ opts]
  (log/info "Starting pedestal on port" (:port opts))
  (-> {::http/routes (routes/routes (partial route-resolver opts) {})
       ::http/type   :jetty
       ::http/join?  false
       ::http/port   (:port opts)
       ;; TODO look into this some more:
       ;; - https://groups.google.com/forum/#!topic/pedestal-users/caRnQyUOHWA
       ::http/secure-headers {:content-security-policy-settings {:object-src "'none'"}}
       ::http/resource-path "public"
       ::http/not-found-interceptor not-found-interceptor}
      http/default-interceptors
      (update ::http/interceptors #(into [sentry/interceptor
                                          redirect-trailing-slash-interceptor
                                          (ring-middlewares/not-modified)
                                          etag-interceptor]
                                         %))
      (http/create-server)
      (http/start)))

(defmethod ig/halt-key! :cljdoc/pedestal [_ server]
  (http/stop server))
