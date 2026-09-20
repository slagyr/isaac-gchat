(ns isaac.comm.gchat.handler
  "Pub/Sub Chat pointer → fetch → gate → dispatch."
  (:require
    [isaac.api :as api]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.gate :as gate]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.people :as people]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defn- feature-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- gchat-slice [cfg]
  (or (get-in cfg [:comms :gchat])
      (get-in cfg [:comms "gchat"])))

(defn -load-cfg []
  (let [root (or (nexus/get :root) (root/current-root))
        snap (loader/snapshot "gchat")
        cfg  (if (:gchat/account (gchat-slice snap))
               snap
               (or (:config (loader/load-config-result {:root root :fs (feature-fs)}))
                   snap
                   {}))]
    (or (gchat-slice cfg) {})))

(defn- message-name [event]
  (or (get-in event [:data :message :name])
      (get-in event [:data :messageName])
      (get-in event [:data "message" "name"])))

(defn- origin [decision]
  {:kind   :gchat
   :space  (:space decision)
   :thread (:thread decision)})

(defn- ensure-session! [decision]
  (or (api/get-session (:session-key decision))
      (api/create-session! (:session-key decision)
                           {:channel  "gchat"
                            :chatType (if (:dm? decision) "direct" "space")
                            :crew     (:crew decision)
                            :origin   (origin decision)})))

(defn- full-config []
  (try
    (let [root (or (nexus/get :root) (root/current-root))
          snap (loader/snapshot "gchat")
          cfg  (if (seq (:models snap))
                 snap
                 (or (when root
                       (:config (loader/load-config-result {:root root :fs (feature-fs)})))
                     snap
                     {}))]
      cfg)
    (catch Exception _
      {})))

(defn- live-comm [cfg]
  (or (comm-registry/comm-for "gchat")
      (try
        (comm-factory/create [:comms :gchat] cfg)
        (catch Exception _ nil))))

(defn- dispatch! [decision]
  (ensure-session! decision)
  (let [ch (live-comm (-load-cfg))]
    (api/dispatch! (cond-> {:session-key (:session-key decision)
                            :input       (str (:sender decision) ": " (:text decision))
                            :origin      (origin decision)
                            :crew        (:crew decision)
                            :config      (full-config)}
                     ch (assoc :comm ch)))))

(defn handle-event
  "Contributed :isaac.google/handler for google.workspace.chat.message.v1.*."
  [event]
  (let [name (message-name event)]
    (if-not name
      (log/error :gchat/fetch-failed :error "missing message name")
      (try
        (let [message  (chat-api/get-message! name)
              decision (gate/decide (-load-cfg) message
                                    {:resolve-person people/resolve})]
          (if (= :drop (:action decision))
            (if (= :sender (:reason decision))
              ;; the one drop an operator must see: it names the identity to allow
              (log/info :gchat/message-dropped :reason :sender :sender (:sender decision) :message name)
              (log/debug :gchat/message-dropped :reason (:reason decision)))
            (do
              (dispatch! decision)
              (log/info :gchat/message-routed
                        :space (:space decision)
                        :thread (:thread decision)
                        :session (:session-key decision)))))
        (catch Exception e
          (log/error :gchat/fetch-failed :message name :error (.getMessage e)))))))
