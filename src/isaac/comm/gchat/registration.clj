(ns isaac.comm.gchat.registration
  "Chat contribution to :isaac.google/registration — one pointer subscription per space.

   A reconcile pass runs once per Google organization with `tenants/*tenant*`
   bound, so both halves of this contribution answer for that organization
   alone: the keys are its spaces, and they subscribe to its topic
   (isaac-1zkz). With `gchat/discover` the keys are the spaces the account is
   a member of, not the ones somebody listed (isaac-xy2i)."
  (:require
    [isaac.comm.gchat.spaces :as spaces]
    [isaac.comm.gchat.tenant :as tenant]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.tenants :as tenants]
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
        cfg  (if (seq (tenants/tenants snap))
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
  "The Chat space resource names (spaces/ENG) this reconcile pass subscribes
   for its organization: every space the account belongs to when it discovers,
   plus whatever config names outright. A space the account has left drops out
   of the listing and out of these keys, and the pass unsubscribes it."
  []
  (let [cfg        (full-cfg)
        id         (tenants/resolve-id cfg nil)
        configured (map space-name (tenant/spaces-for cfg id))
        found      (when (tenant/discovering? cfg id)
                     (keep :name (spaces/discovered id (tenant/discover-every-ms cfg id))))]
    (vec (sort (distinct (concat configured found))))))

(defn expiry [sub]
  (or (:expireTime sub) (:expires-at sub)))

(defn create! [key]
  (let [cfg   (full-cfg)
        topic (get-in cfg (conj (tenants/config-path cfg (tenants/resolve-id cfg nil)) :topic))]
    ((requiring-resolve 'isaac.google.events/create-subscription!)
      {:targetResource         (str "//chat.googleapis.com/" key)
       :eventTypes             EVENT-TYPES
       :notificationEndpoint   {:pubsubTopic topic}
       :payloadOptions         {:includeResource false}})))

(defn renew! [name]
  ((requiring-resolve 'isaac.google.events/renew-subscription!) name))
