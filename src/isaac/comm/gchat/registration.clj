(ns isaac.comm.gchat.registration
  "Chat contribution to :isaac.google/registration — one pointer subscription
   per organization, on every space at once.

   Workspace Events takes `//chat.googleapis.com/spaces/-`, \"all spaces for a
   user\": under the account's own token it delivers every space the account
   belongs to, named spaces, unnamed group chats and DMs alike, and keeps
   doing so as it is invited to more. So a reconcile pass has exactly one key
   to answer with, and `gchat/spaces` entries subscribe nothing — belonging to
   a space is the grant, and an entry only says what its session is called and
   when it answers (isaac-ihuc).

   A pass runs once per Google organization with `tenants/*tenant*` bound, so
   the subscription it creates uses that organization's token and its topic
   (isaac-1zkz)."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.tenants :as tenants]
    [isaac.nexus :as nexus]))

(def KEY
  "The one Chat subscription an organization needs."
  "spaces/-")

(def EVENT-TYPES
  "What the one subscription carries: what was said, and who came and went.
   Membership events are what tell the comm a space it has never heard from
   exists at all — a DM's first message arrives with them."
  ["google.workspace.chat.message.v1.created"
   "google.workspace.chat.message.v1.updated"
   "google.workspace.chat.message.v1.deleted"
   "google.workspace.chat.membership.v1.created"
   "google.workspace.chat.membership.v1.updated"
   "google.workspace.chat.membership.v1.deleted"])

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

(defn subscription-keys
  "What this reconcile pass subscribes for its organization: `spaces/-`, and
   nothing else. Configured `gchat/spaces` entries are overrides on an
   existing space's session, not subscriptions, and a space nobody listed is
   still heard because the account belongs to it."
  []
  [KEY])

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
