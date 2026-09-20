(ns isaac.gchat-steps
  "Google Chat inbound + outbound feature steps."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gchat :as gchat]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.handler :as handler]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.people :as people]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.auth.store :as auth-store]
    [isaac.logger :as log]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]))

(helper! isaac.gchat-steps)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (g/dissoc! :gchat-comm)
    (g/dissoc! :gchat-http-stub)
    ;; the people memo and its warn-once marks outlive a scenario otherwise
    (people/reset-memo!)))

(defn- kv-cells->map [cells]
  (when (and (seq cells) (even? (count cells)))
    (into {} (map (fn [[k v]] [k v]) (partition 2 cells)))))

(defn- table-map [{:keys [headers rows]}]
  (or (let [header-map (kv-cells->map headers)
            row-map    (apply merge {} (keep kv-cells->map rows))]
        (when (or header-map (seq row-map))
          (merge header-map row-map)))
      (when (and (seq headers) (= 1 (count rows)))
        (zipmap headers (first rows)))
      {}))

(defn- nest-dotted [m]
  (reduce (fn [acc [k v]]
            (assoc-in acc (mapv keyword (str/split (str k) #"\.")) v))
          {}
          m))

(defn- parse-cell [v]
  (cond
    (nil? v) v
    (and (string? v) (re-matches #"-?\d+" v)) (parse-long v)
    :else v))

(defn- path-table->record [table]
  (let [pairs (if (and (= ["path" "value"] (:headers table))
                       (seq (:rows table)))
                (:rows table)
                (or (seq (map (fn [row]
                                (if (= 2 (count row))
                                  row
                                  [(first row) (second row)]))
                              (:rows table)))
                    (when (and (seq (:headers table)) (seq (:rows table)))
                      (map vector (:headers table) (first (:rows table))))))]
    (reduce (fn [acc [path value]]
              (let [s  (str path)
                    ks (if (str/starts-with? s "gchat/")
                         [(keyword s)]
                         (mapv keyword (str/split s #"/")))]
                (assoc-in acc ks (parse-cell value))))
            {}
            pairs)))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

(defn- gchat-module-coord []
  {:isaac.comm.gchat {:local/root (System/getProperty "user.dir")}})

(defn- gchat-module-index []
  (let [local (io/file (System/getProperty "user.dir") "resources" "isaac-manifest.edn")
        manifest (cond
                   (.exists local) (edn/read-string (slurp local))
                   :else (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string))]
    (when manifest
      {:isaac.comm.gchat {:coord    {:local/root (System/getProperty "user.dir")}
                          :manifest manifest
                          :path     nil}})))

(defn inject-gchat-module! []
  (alter-var-root #'discovery/*foundation-index-override*
                  (fn [prev]
                    (merge (or prev (discovery/builtin-index))
                           (gchat-module-index))))
  (when-let [root (root-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (gchat-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn- ensure-session-store! []
  (when-not (session-store/registered-store)
    (when-let [root (root-dir)]
      (session-store/register-store! (memory-store/create-store root)))))

(defn- ensure-gchat-factory! []
  (when-not (get-method comm-factory/create :gchat)
    (require 'isaac.comm.gchat)))

(defn- load-gchat-cfg []
  (let [fs*  (feature-fs)
        root (root-dir)
        cfg  (:config (loader/load-config-result {:root root :fs fs*}))]
    (or (get-in cfg [:comms :gchat])
        (get-in cfg [:comms "gchat"])
        {})))

(defn- record-http! [req]
  (let [flat (cond-> {:method  (:method req)
                      :url     (:url req)
                      :headers (:headers req)
                      :body    (:body req)
                      :query   (:query req)}
               (:query req) (assoc :query (:query req)))]
    (g/update! :outbound-http-requests (fn [prior] (vec (conj (or prior []) flat))))
    (g/assoc! :outbound-http-request flat)
    req))

(defn- stub-http! [req]
  (let [req' (record-http! req)
        stub (g/get :gchat-http-stub)
        url  (:url req')]
    (cond
      (and stub (= url "https://chat.googleapis.com/v1/spaces:findDirectMessage")
           (:no-dm stub))
      {:status 404 :body {}}

      (and stub (= url "https://chat.googleapis.com/v1/spaces:setup")
           (:setup-space stub))
      {:status 200 :body {:name (:setup-space stub)}}

      (str/includes? (str url) "/messages")
      {:status 200 :body {:name (str (:url req') "/posted")}}

      :else
      {:status 200 :body {}})))

(defn chat-api-returns [name table]
  (let [msg (assoc (nest-dotted (table-map table)) :name name)]
    (g/update! :gchat-api-messages (fnil assoc {}) name msg)))

(defn- stub-get-message! [name]
  (or (get (g/get :gchat-api-messages) name)
      (throw (ex-info (str "no Chat API stub for " name) {:name name}))))

(defn- gchat-access-token []
  (or (g/get :gchat-access-token)
      (when-let [tokens (with-feature-fs
                          (fn []
                            (auth-store/load-tokens (or (root-dir) "target/test-state")
                                                    "google"
                                                    (feature-fs))))]
        (or (:access tokens) (:access_token tokens)))
      "at-1"))

(defn gchat-outbound-comm-registered []
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)
        comm (gchat/make {:name :gchat :root root})
        cfg  (nexus/-with-nested-nexus {:fs fs* :root root}
               (load-gchat-cfg))]
    (reset! (.-cfg comm) cfg)
    (comm-registry/register-instance! "gchat" comm)
    (g/assoc! :gchat-comm comm)))

(defn chat-api-has-no-dm [email]
  (g/update! :gchat-http-stub (fnil assoc {}) :no-dm true :no-dm-email email))

(defn chat-api-creates-space-on-setup [name]
  (g/update! :gchat-http-stub (fnil assoc {}) :setup-space name))

(defn log-entry-count [n event]
  (let [n     (if (string? n) (parse-long n) n)
        event (keyword (str/replace (str event) #"^:" ""))
        hits  (filter #(= event (:event %)) (log/get-entries))]
    (g/should= n (count hits))))

(defn- with-chat-stubs [f]
  (let [token (gchat-access-token)]
    (g/assoc! :gchat-access-token token)
    (with-redefs [chat-api/get-message! stub-get-message!
                  chat-api/-http!       stub-http!
                  gchat/access-token    (constantly token)]
      (f))))

(defn google-chat-delivers [name]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (ensure-session-store!)
  (when-not (g/get :gchat-comm)
    (gchat-outbound-comm-registered))
  (let [fs*  (feature-fs)
        root (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gchat feature")
        (grover/clear-provider-requests!)
        (when-let [comm (g/get :gchat-comm)]
          (reset! (.-cfg comm) (or (get-in cfg [:comms :gchat])
                                   (get-in cfg [:comms "gchat"])
                                   {})))
        (with-chat-stubs
          (fn []
            (handler/handle-event {:type "google.workspace.chat.message.v1.created"
                                   :data {:message {:name name}}}))))))
  (session-steps/await-turn!))

(defn gchat-comm-send! [table]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (when-not (g/get :gchat-comm)
    (gchat-outbound-comm-registered))
  (let [record (path-table->record table)
        comm   (g/get :gchat-comm)
        fs*    (feature-fs)
        root   (root-dir)
        cfg    (nexus/-with-nested-nexus {:fs fs* :root root}
                 (load-gchat-cfg))]
    (reset! (.-cfg comm) cfg)
    (with-chat-stubs
      (fn []
        (comm/send! comm record)))))

(defgiven #"the Chat API returns message \"([^\"]+)\":"
  isaac.gchat-steps/chat-api-returns)

(defgiven "gchat outbound comm is registered"
  isaac.gchat-steps/gchat-outbound-comm-registered)

(defgiven #"the Chat API has no direct message space with \"([^\"]+)\""
  isaac.gchat-steps/chat-api-has-no-dm)

(defgiven #"the Chat API creates space \"([^\"]+)\" on setup"
  isaac.gchat-steps/chat-api-creates-space-on-setup)

(defwhen #"Google Chat delivers a message event for \"([^\"]+)\""
  isaac.gchat-steps/google-chat-delivers)

(defwhen "gchat comm send! is invoked with:"
  isaac.gchat-steps/gchat-comm-send!)

(defthen #"exactly (\d+) log entr(?:y|ies) has event \"([^\"]+)\""
  isaac.gchat-steps/log-entry-count)
