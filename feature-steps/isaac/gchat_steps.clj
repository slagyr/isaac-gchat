(ns isaac.gchat-steps
  "Google Chat inbound feature steps."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen helper!]]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.handler :as handler]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]))

(helper! isaac.gchat-steps)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))))

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
  (when-let [manifest (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string)]
    {:isaac.comm.gchat {:coord    {:local/root (System/getProperty "user.dir")}
                        :manifest manifest
                        :path     nil}}))

(defn- inject-gchat-module! []
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

(defn chat-api-returns [name table]
  (let [msg (assoc (nest-dotted (table-map table)) :name name)]
    (g/update! :gchat-api-messages (fnil assoc {}) name msg)))

(defn- stub-get-message! [name]
  (or (get (g/get :gchat-api-messages) name)
      (throw (ex-info (str "no Chat API stub for " name) {:name name}))))

(defn google-chat-delivers [name]
  (ensure-gchat-factory!)
  (inject-gchat-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gchat feature")
        (grover/clear-provider-requests!)
        (with-redefs [chat-api/get-message! stub-get-message!]
          (handler/handle-event {:type "google.workspace.chat.message.v1.created"
                                 :data {:message {:name name}}})))))
  (session-steps/await-turn!))

(defgiven #"the Chat API returns message \"([^\"]+)\":"
  isaac.gchat-steps/chat-api-returns)

(defwhen #"Google Chat delivers a message event for \"([^\"]+)\""
  isaac.gchat-steps/google-chat-delivers)
