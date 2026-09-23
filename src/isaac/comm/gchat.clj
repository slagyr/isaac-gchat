(ns isaac.comm.gchat
  "Google Chat comm factory. Inbound lives on the google handler; send!
   and on-reply post as the Google user."
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as delivery-queue]
    [isaac.comm.factory :as factory]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.self :as self]
    [isaac.comm.gchat.format :as fmt]
    [isaac.comm.gchat.target :as target]
    [isaac.comm.gchat.tenant :as tenant]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defonce ^:private origin-by-session (atom {}))
(defonce ^:private delivery-failures* (atom {}))

(defn- slice [comm]
  (or @(.-cfg comm) {}))

(defn access-token
  "The Google access token to post as. With a comm's config slice, it is that
   comm's organization's token; with none, the organization this thread is
   already acting as (isaac-1zkz)."
  ([] ((requiring-resolve 'isaac.google.token/token)))
  ([slice] ((requiring-resolve 'isaac.google.token/token) (tenant/of-comm slice))))

(defn- post-chunks! [space thread text cap token tenant]
  (let [chunks (fmt/split-content (fmt/->chat-text text) cap)]
    (doseq [chunk chunks]
      ;; The response's :sender is Isaac — the one free source of the account's
      ;; users/<id>, which the gate needs to see its own replies. Keyed by
      ;; tenant: a comm speaks for one organization (isaac-1zkz), and an id
      ;; learned for one must never be mistaken for another's self (isaac-mm7o).
      (self/learn-from-send!
        tenant
        (chat-api/create-message! {:space  space
                                   :thread thread
                                   :text   chunk
                                   :token  token})))))

(defn- resolve-dm-space! [email token]
  (or (:name (chat-api/find-direct-message! email token))
      (:name (chat-api/setup-direct-message! email token))))

(defn- send!* [comm record]
  (try
    (let [cfg    (slice comm)
          token  (access-token cfg)
          cap    (or (:gchat/message-cap cfg) fmt/default-message-cap)
          text   (:content record)
          to     (:gchat/to record)
          space  (or (when (seq to)
                       (resolve-dm-space! to token))
                     (target/resolve-space cfg (:gchat/space record)))
          thread (:gchat/thread record)]
      (cond
        (str/blank? space)
        (do (log/warn :gchat.send/missing-target :record record)
            {:ok false :transient? false})

        :else
        (do (post-chunks! space thread text cap token (tenant/of-comm cfg))
            {:ok true})))
    (catch Exception e
      (log/error :gchat.send/failed :error (.getMessage e))
      {:ok false :transient? true :error (.getMessage e)})))

(defn- on-cycle-start* [_comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (when (= :gchat (:kind origin))
      (swap! origin-by-session assoc session-key origin))))

(defn -full-cfg
  "The whole process config, not just this comm's slice - needed to read
   :attention :notify, the same {:comm :target} coords isaac.attention reads
   internally (isaac-qry7). Its own seam so a spec can answer without
   installing config."
  []
  (loader/snapshot "gchat reply diversion"))

(defn- attention-coords [full-cfg]
  (get-in full-cfg [:attention :notify]))

(defn- who [origin]
  (or (:display-name origin) (:email origin) (:user origin) "someone"))

(defn- pending-prefix
  "One line naming the DM and the sender, and that the request is pending -
   read before the turn's own reply text (isaac-qry7)."
  [origin]
  (str "Pending Chat invite: the DM with " (who origin) " (" (:space origin)
      ") has not been accepted yet, so this reply could not be posted there."))

(defn- divert-reply!
  "The account is only invited to this DM - Chat 403s posting there, so the
   reply goes to the attention comm instead, prefixed with the DM, the
   sender, and that the request is pending (isaac-qry7). No attention comm
   configured: log once and drop the reply rather than lose it silently."
  [origin text]
  (if-let [{:keys [comm target]} (attention-coords (-full-cfg))]
    (do
      (delivery-queue/enqueue! {:comm    (if (string? comm) (keyword comm) comm)
                                :target  target
                                :content (str (pending-prefix origin) "\n\n" text)})
      (log/warn :gchat.dm/reply-diverted :space (:space origin) :thread (:thread origin)))
    (log/warn :gchat.dm/reply-diverted :space (:space origin) :thread (:thread origin) :dropped true)))

(defn- delivery-failure-fields
  "The fields a reply's create-message! failure surfaces as - never the bare
   ex-info message a raw 'Chat API create failed: 403' turn error would show
   (isaac-qry7)."
  [origin e]
  (let [data   (ex-data e)
        body   (:body data)
        reason (or (get-in body [:error :message])
                  (when (string? body) body)
                  (.getMessage e))]
    {:space (:space origin) :thread (:thread origin) :status (:status data) :reason reason}))

(defn- reply!
  "Post the turn's reply. Returns nil on success, or the failure fields when
   create-message! throws - caught here so it never surfaces as a raw turn
   error (isaac-qry7)."
  [comm origin text]
  (let [cfg   (slice comm)
        token (access-token cfg)
        cap   (or (:gchat/message-cap cfg) fmt/default-message-cap)]
    (try
      (post-chunks! (:space origin) (:thread origin) text cap token (tenant/of-comm cfg))
      nil
      (catch Exception e
        (let [{:keys [space thread status reason] :as failure} (delivery-failure-fields origin e)]
          (log/error :gchat/delivery-failed :space space :thread thread :status status :reason reason)
          failure)))))

(defn- on-reply* [comm session-key text]
  (when-let [origin (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (if (:invited? origin)
        (divert-reply! origin text)
        (when-let [failure (reply! comm origin text)]
          (swap! delivery-failures* assoc session-key (assoc failure :class :delivery-failure)))))))

(defn- on-turn-end* [_comm session-key _result]
  (when-let [{:keys [space thread status reason class]} (get @delivery-failures* session-key)]
    (log/warn :gchat/turn-notice :class class :session session-key
              :space space :thread thread :status status :reason reason)
    (swap! delivery-failures* dissoc session-key))
  (swap! origin-by-session dissoc session-key))

(deftype GchatComm [host cfg])

(extend GchatComm
  comm/Comm
  (merge comm/defaults
         {:send!          send!*
          :on-cycle-start on-cycle-start*
          :on-reply       on-reply*
          :on-turn-end    on-turn-end*}))

(defn make [host]
  (->GchatComm host (atom nil)))

(defmethod factory/create :gchat [node-path slice]
  (let [comm (make {:name (last node-path)
                    :root (or (nexus/get :root) (root/current-root))})]
    (reset! (.-cfg comm) slice)
    comm))
