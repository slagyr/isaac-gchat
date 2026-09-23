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
    [isaac.comm.gchat.transcript :as transcript]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.nexus :as nexus])
  (:import
    (java.time Instant ZoneId)
    (java.time.format DateTimeFormatter)))

(defonce ^:private origin-by-session (atom {}))
(defonce ^:private delivery-failures* (atom {}))

;; Reactions on the triggering message show Yopp's progress — 👀 working, ✅
;; answered, ⚠️ failed, ⏳ parked — instead of a status post (isaac-1bq1).
;; Chat lets a user add/remove reactions and neither notifies. Keyed by
;; session-key: {:message <resource name> :reaction <resource name> :emoji
;; <glyph> :kind :working|:done|:failed|:parked}. Persists across the
;; origin-by-session dissoc at turn-end so a park's ⏳ survives until the
;; turn that actually answers.
(defonce ^:private reaction-state* (atom {}))

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

;; region ----- Progress reactions (isaac-1bq1) -----

(def default-reactions
  {:working "👀" :done "✅" :failed "⚠️" :parked "⏳"})

(defn- reactions-cfg
  "The configured reaction glyphs, defaults merged in - or nil when
   `gchat/reactions false` turns the whole lifecycle off."
  [cfg]
  (let [v (:gchat/reactions cfg)]
    (cond
      (false? v) nil
      (map? v)   (merge default-reactions v)
      :else      default-reactions)))

(defn- reaction-state [session-key]
  (get @reaction-state* session-key))

(defn- reaction-remove!
  "Delete the session's current reaction, if any. A failure is logged once
   at debug and never retried or surfaced as a turn error (isaac-1bq1)."
  [comm session-key]
  (when-let [{:keys [reaction message emoji]} (reaction-state session-key)]
    (try
      (chat-api/delete-reaction! {:reaction reaction :token (access-token (slice comm))})
      (catch Exception e
        (log/debug :gchat.reaction/failed :message message :emoji emoji :status (:status (ex-data e)))))
    (swap! reaction-state* dissoc session-key)))

(defn- reaction-add!
  "Add a reaction to `message`, remembering its resource name for the later
   remove. A failure is logged once at debug and never retried."
  [comm session-key message kind emoji]
  (try
    (let [resp (chat-api/create-reaction! {:message message :emoji emoji :token (access-token (slice comm))})]
      (swap! reaction-state* assoc session-key {:message message :reaction (:name resp) :emoji emoji :kind kind}))
    (catch Exception e
      (log/debug :gchat.reaction/failed :message message :emoji emoji :status (:status (ex-data e))))))

(defn- set-reaction!
  "Remove-then-add for every change - there is no reaction update (isaac-1bq1)."
  [comm session-key message kind emoji]
  (reaction-remove! comm session-key)
  (reaction-add! comm session-key message kind emoji))

(defn- reaction-working!
  "👀 on the triggering message, once per turn - the first cycle. A turn
   that resumes the same still-parked message (⏳) is left alone: the parked
   glyph is kept until the resumed reply lands, not flickered back to 👀."
  [comm session-key origin]
  (when-let [reactions (reactions-cfg (slice comm))]
    (when-let [message (:message origin)]
      (let [state (reaction-state session-key)]
        (when-not (and (= :parked (:kind state)) (= message (:message state)))
          (set-reaction! comm session-key message :working (:working reactions)))))))

(defn- reaction-done!
  "✅ once the turn's answer has posted (or diverted) - the reaction the
   parked ⏳ is kept for."
  [comm session-key origin]
  (when-let [reactions (reactions-cfg (slice comm))]
    (when-let [message (:message origin)]
      (set-reaction! comm session-key message :done (:done reactions)))))

(defn- reaction-failed!
  "⚠️ on a hard turn error."
  [comm session-key origin]
  (when-let [reactions (reactions-cfg (slice comm))]
    (when-let [message (:message origin)]
      (set-reaction! comm session-key message :failed (:failed reactions)))))

(defn- reaction-parked!
  "⏳ on provider weather - kept until the resumed reply lands, then ✅."
  [comm session-key origin]
  (when-let [reactions (reactions-cfg (slice comm))]
    (when-let [message (:message origin)]
      (set-reaction! comm session-key message :parked (:parked reactions)))))

;; endregion ^^^^^ Progress reactions ^^^^^

(defn- on-cycle-start* [comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (when (= :gchat (:kind origin))
      (swap! origin-by-session assoc session-key origin)
      (when (= 1 (:n cycle))
        (reaction-working! comm session-key origin)))))

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

(defn- note-own-reply!
  "Yopp's own reply gets the same thread marker every other line in the
   space's transcript carries (isaac-acou) — only once it actually posted;
   a diverted or failed reply never reached the thread."
  [comm origin text]
  (transcript/append! (:space origin)
                      (transcript/entry {:sender (:gchat/account (slice comm))
                                         :text   text
                                         :thread (:thread origin)
                                         :self?  true})))

(defn- on-reply* [comm session-key text]
  (when-let [origin (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (if (:invited? origin)
        (do (divert-reply! origin text)
            (reaction-done! comm session-key origin))
        (if-let [failure (reply! comm origin text)]
          (swap! delivery-failures* assoc session-key (assoc failure :class :delivery-failure))
          (do (note-own-reply! comm origin text)
              (reaction-done! comm session-key origin)))))))

;; What went wrong — isaac-h5v8. The drive never ends a turn silently: an
;; ordinary failure carries :ended-by :error, and provider weather (rate
;; limit, auth, a stalled stream) carries :ended-by :provider-unavailable
;; plus :reason and :retry-at (isaac.drive.weather/stamp-weather!). Both are
;; in-thread notices to the person who sent the message — never a stack
;; trace, a raw provider payload or a token. A reply that itself fails to
;; post (isaac-qry7, above) is its own case — the thread can't hear a notice
;; when posting to it is exactly what just failed, so that one stays an
;; operator-facing :gchat/turn-notice log instead of another post attempt.

;; Sessions with an unanswered park notice outstanding. One notice per park:
;; set when it posts, cleared only when the turn next ends with something
;; other than weather (a reply, cancel, cycle-limit, ...) — not by every
;; on-turn-end, so a re-wall mid-park stays quiet.
(defonce ^:private parked-sessions (atom #{}))

(defn- weather-result? [result]
  (boolean (or (:unavailable? result)
               (= :provider-unavailable (:ended-by result)))))

(defn- error-result? [result]
  (= :error (:ended-by result)))

(defn- retry-time-text
  "The retry-at ISO instant as a local clock reading, e.g. \"4:40pm\" — the
   viewer's own machine time, so the notice never leaks tick precision."
  [retry-at]
  (try
    (when (seq retry-at)
      (let [zoned (.atZone (Instant/parse retry-at) (ZoneId/systemDefault))
            fmt   (DateTimeFormatter/ofPattern "h:mma")]
        (str/lower-case (.format zoned fmt))))
    (catch Exception _ nil)))

(def ^:private weather-reason-words
  {:wall           "Out of tokens"
   :auth           "Waiting on a login"
   :stream-stalled "The connection stalled"})

(defn- weather-notice-text [{:keys [reason retry-at]}]
  (let [what  (get weather-reason-words reason "Hit a provider issue")
        when* (retry-time-text retry-at)]
    (if when*
      (str what " until " when* "; I will answer then.")
      (str what "; I will answer when it clears."))))

(defn- failure-class
  "Coarse, name-only classification of a hard turn error — never the
   exception message or provider payload, only its shape. Same vocabulary
   as the :class a reply-post failure logs (isaac-qry7): :provider-error,
   :tool-failure, :delivery-failure."
  [result]
  (let [ex-class (some-> (:ex-class result) str str/lower-case)]
    (cond
      (some->> ex-class (re-find #"tool")) :tool-failure
      (some->> ex-class (re-find #"deliver")) :delivery-failure
      :else :provider-error)))

(defn- error-notice-text [result]
  (str "Something went wrong (" (str/replace (name (failure-class result)) "-" " ") "). "
       "I couldn't finish that reply."))

(defn- post-notice!
  "Best-effort in-thread notice. One attempt; a delivery failure is logged
   once and never retried in a loop (isaac-h5v8)."
  [comm session-key text]
  (when-let [{:keys [space thread]} (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (try
        (let [cfg   (slice comm)
              token (access-token cfg)
              cap   (or (:gchat/message-cap cfg) fmt/default-message-cap)]
          (post-chunks! space thread text cap token (tenant/of-comm cfg)))
        (catch Exception e
          (log/error :gchat.notice/failed :session session-key :error (.getMessage e)))))))

(defn- on-turn-end* [comm session-key result]
  (if-let [{:keys [space thread status reason class]} (get @delivery-failures* session-key)]
    (do
      (log/warn :gchat/turn-notice :class class :session session-key
                :space space :thread thread :status status :reason reason)
      (swap! delivery-failures* dissoc session-key)
      (swap! parked-sessions disj session-key))
    (cond
      (weather-result? result)
      (when-not (contains? @parked-sessions session-key)
        (swap! parked-sessions conj session-key)
        (when-let [origin (get @origin-by-session session-key)]
          (reaction-parked! comm session-key origin))
        (post-notice! comm session-key (weather-notice-text result)))

      (error-result? result)
      (do (swap! parked-sessions disj session-key)
          (when-let [origin (get @origin-by-session session-key)]
            (reaction-failed! comm session-key origin))
          (post-notice! comm session-key (error-notice-text result)))

      :else
      (swap! parked-sessions disj session-key)))
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
