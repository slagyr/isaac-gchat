(ns isaac.comm.gchat.registration
  "Chat contribution to :isaac.google/registration — one pointer subscription per space."
  (:require
    [isaac.comm.gchat.handler :as handler]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def EVENT-TYPES
  ["google.workspace.chat.message.v1.created"
   "google.workspace.chat.message.v1.updated"
   "google.workspace.chat.message.v1.deleted"])

(defn- feature-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- full-cfg []
  (let [root (or (nexus/get :root) (root/current-root))
        snap (loader/snapshot "gchat registration")
        cfg  (if (get-in snap [:google :topic])
               snap
               (or (when root
                     (:config (loader/load-config-result {:root root :fs (feature-fs)})))
                   snap
                   {}))]
    cfg))

(defn- space-name [k]
  (cond
    (keyword? k) (if (namespace k)
                   (str (namespace k) "/" (name k))
                   (name k))
    :else (str k)))

(defn space-keys
  "Configured Chat space resource names (spaces/ENG)."
  []
  (let [spaces (or (:gchat/spaces (handler/-load-cfg)) {})]
    (vec (sort (map space-name (keys spaces))))))

(defn expiry [sub]
  (or (:expireTime sub) (:expires-at sub)))

(defn create! [key]
  (let [topic (get-in (full-cfg) [:google :topic])]
    ((requiring-resolve 'isaac.google.events/create-subscription!)
      {:targetResource         (str "//chat.googleapis.com/" key)
       :eventTypes             EVENT-TYPES
       :notificationEndpoint   {:pubsubTopic topic}
       :payloadOptions         {:includeResource false}})))

(defn renew! [name]
  ((requiring-resolve 'isaac.google.events/renew-subscription!) name))
