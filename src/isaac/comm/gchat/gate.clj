(ns isaac.comm.gchat.gate
  "Deterministic inbound gate: event + config → :route | :drop reason."
  (:require
    [clojure.string :as str]))

(defn session-name
  "Default session id for a Chat space: gchat-<space with / → ->."
  [space]
  (str "gchat-" (str/replace (str space) "/" "-")))

(defn- sender-email [message]
  (or (get-in message [:sender :email])
      (get-in message [:senderEmail])
      (get message :sender.email)))

(defn- space-name [message]
  (or (get-in message [:space :name])
      (when-let [n (:name message)]
        (second (re-find #"(spaces/[^/]+)" (str n))))))

(defn- space-type [message]
  (or (get-in message [:space :type])
      "SPACE"))

(defn- thread-name [message]
  (or (get-in message [:thread :name])
      (:thread message)))

(defn- mentioned?
  "True when annotations name a user resource (the account)."
  [message]
  (let [ann (:annotations message)]
    (boolean
      (cond
        (string? (get ann :mention)) (seq (get ann :mention))
        (sequential? ann)            (some (fn [a]
                                             (or (:mention a)
                                                 (get-in a [:userMention :user :name])
                                                 (get-in a [:user :name])))
                                           ann)
        (map? ann)                   (or (seq (:mention ann))
                                         (get-in ann [:userMention :user :name]))
        :else                        false))))

(defn- dm? [message]
  (= "DIRECT_MESSAGE" (str (space-type message))))

(defn- allow-from [cfg]
  (let [v (:gchat/allow-from cfg)]
    (cond
      (nil? v) nil
      (string? v) [v]
      :else (vec v))))

(defn- spaces [cfg]
  (or (:gchat/spaces cfg) {}))

(defn- space-key [space]
  (when space
    (keyword space)))

(defn- space-cfg [cfg space]
  (let [spaces* (spaces cfg)]
    (or (get spaces* (space-key space))
        (get spaces* space)
        (get spaces* (keyword (str "spaces/" (name (or space ""))))))))

(defn- respond-policy [cfg message]
  (if (dm? message)
    (or (some-> (space-cfg cfg (space-name message)) :respond)
        :all)
    (or (some-> (space-cfg cfg (space-name message)) :respond)
        :mentions)))

(defn- policy-kw [policy]
  (keyword (or policy :mentions)))

(defn- allowed-sender? [cfg email]
  (let [allow (allow-from cfg)]
    (boolean (and (seq allow) (some #(= email %) allow)))))

(defn decide
  "Pure: cfg + fetched Chat message → {:action :route ...} | {:action :drop :reason kw}."
  [cfg message]
  (let [email  (sender-email message)
        space  (space-name message)
        thread (thread-name message)
        account (:gchat/account cfg)]
    (cond
      (and (seq account) (= email account))
      {:action :drop :reason :self}

      (not (allowed-sender? cfg email))
      {:action :drop :reason :sender}

      (and (not (dm? message)) (nil? (space-cfg cfg space)))
      {:action :drop :reason :space}

      :else
      (let [policy (policy-kw (respond-policy cfg message))]
        (cond
          (= :never policy)
          {:action :drop :reason :policy}

          (and (= :mentions policy) (not (mentioned? message)))
          {:action :drop :reason :no-mention}

          :else
          {:action      :route
           :space       space
           :thread      thread
           :session-key (or (get (space-cfg cfg space) :session)
                            (session-name space))
           :crew        (or (get (space-cfg cfg space) :crew)
                            (:crew cfg)
                            "main")
           :space-cfg   (space-cfg cfg space)
           :dm?         (dm? message)
           :text        (or (:text message) "")
           :sender      email})))))
