(ns isaac.comm.gchat.gate
  "Deterministic inbound gate: event + config → :route | :drop reason.
   Chat names a human sender users/<id> and never an email, so the caller may
   pass :resolve-person (isaac.google.people/resolve) to look the email up at
   decision time. The lookup is the only impure edge and it fails soft: without
   it — or when it fails — an allow-list still matches users/<id> or
   domain:<domainId>.

   The gate is also where a space with no config gets its session: with
   `gchat/discover` on, belonging to the space is the grant, and the space
   routes to its canonical session (isaac.comm.gchat.canon). The caller may
   pass what Chat said the space is as `:space-info` and the organization as
   `:tenant`; the gate itself asks Google nothing about spaces."
  (:require
    [clojure.string :as str]
    [isaac.comm.gchat.canon :as canon]
    [isaac.google.people :as people]))

(defn- sender-email [message]
  (or (get-in message [:sender :email])
      (get-in message [:senderEmail])
      (get message :sender.email)))

(defn space-of
  "Which space a fetched message belongs to."
  [message]
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

(defn- dm?
  "A direct message, by what the event said or by what Chat's listing says
   the space is."
  [message space-info]
  (or (= "DIRECT_MESSAGE" (str (space-type message)))
      (= "DIRECT_MESSAGE" (str (:spaceType space-info)))))

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

(defn- respond-policy
  "When this space starts a turn: what its entry says, else every message in a
   DM and only mentions in a space."
  [entry dm?]
  (or (:respond entry) (if dm? :all :mentions)))

(defn- policy-kw [policy]
  (keyword (or policy :mentions)))

(defn- sender-user [message]
  (get-in message [:sender :name]))

(defn- sender-domain [message]
  (get-in message [:sender :domainId]))

(defn- sender-display-name [message]
  (get-in message [:sender :displayName]))

(defn- resolved-person
  "Ask Google who users/<id> is — only when Chat withheld the email and the
   caller supplied a resolver. Never throws, may answer nil."
  [message resolve-person]
  (let [user (sender-user message)]
    (when (and resolve-person
               (nil? (sender-email message))
               (seq (str (or user ""))))
      (try
        (resolve-person user {:display-name (sender-display-name message)})
        (catch Exception _ nil)))))

(defn sender-identity
  "What the gate knows about who sent this: email when Google supplies it or
   the People API resolves it, the users/<id> resource, the Workspace domainId,
   and the display name. spaces.messages.get under user auth returns
   users/<id> + domainId and NO email for human senders, so an allow-list must
   be able to name those too."
  ([message] (sender-identity message {}))
  ([message {:keys [resolve-person]}]
   (let [person (resolved-person message resolve-person)
         name   (or (sender-display-name message) (:display-name person))]
     (cond-> {:email  (or (sender-email message) (:email person))
              :user   (sender-user message)
              :domain (sender-domain message)}
       (seq (str (or name ""))) (assoc :display-name name)))))

(defn- email-domain [email]
  (let [email (str/lower-case (str (or email "")))
        at    (str/last-index-of email "@")]
    (when (and at (< (inc at) (count email)))
      (subs email (inc at)))))

(defn email-matches?
  "An allow-from entry against an email. \"*@tonotop.com\" admits every address
   in that domain; anything else is an exact, case-insensitive match. Chat's
   sender email comes from Google, so a domain pattern here is as trustworthy
   as the address itself — unlike Gmail, where From: is forgeable."
  [entry email]
  (let [entry (str/lower-case (str (or entry "")))
        email (str/lower-case (str (or email "")))]
    (boolean
      (and (seq entry) (seq email)
           (if (str/starts-with? entry "*@")
             (= (subs entry 2) (email-domain email))
             (= entry email))))))

(defn- allowed-sender?
  "An allow-from entry matches by email (exactly or as *@domain), by
   users/<id>, or by domain:<domainId> (the Workspace customer id, as Chat
   reports it in sender.domainId)."
  [cfg identity]
  (let [allow (allow-from cfg)
        {:keys [email user domain]} identity]
    (boolean
      (and (seq allow)
           (some (fn [entry]
                   (let [entry (str entry)]
                     (or (email-matches? entry email)
                         (and (seq user) (= entry user))
                         (and (seq domain) (= entry (str "domain:" domain))))))
                 allow)))))

(defn decide
  "cfg + fetched Chat message → {:action :route ...} | {:action :drop :reason kw}.
   Pure but for the optional :resolve-person lookup in `opts`."
  ([cfg message] (decide cfg message {}))
  ([cfg message opts]
    (let [identity     (sender-identity message opts)
          email        (:email identity)
          space        (space-of message)
          thread       (thread-name message)
          account      (:gchat/account cfg)
          ;; Chat pushes Isaac's own replies back with users/<id> and no email,
          ;; so email alone cannot see self. Without this an operator who allows
          ;; domain:<id> gets an echo loop (isaac-mm7o).
          account-user (or (:account-user opts) (:gchat/account-id cfg))
          space-info   (:space-info opts)
          direct?      (dm? message space-info)
          entry        (space-cfg cfg space)]
      (cond
        (or (and (seq account) (= email account))
            (and (seq (str (or account-user "")))
                 (= (str account-user) (:user identity))))
        {:action :drop :reason :self}

        (not (allowed-sender? cfg identity))
        {:action :drop :reason :sender :sender identity}

        ;; Belonging to the space is the grant when discovery is on: the
        ;; account was invited, and that is what lets the space be heard
        ;; (isaac-xy2i). Without it an unlisted space still fails closed.
        (and (not direct?) (nil? entry) (not (:gchat/discover cfg)))
        {:action :drop :reason :space}

        :else
        (let [policy (policy-kw (respond-policy entry direct?))]
          (cond
            (= :never policy)
            {:action :drop :reason :policy}

            ;; Not spoken to, but in a space Isaac belongs to: heard, not
            ;; answered. The handler keeps the line so the next mention has
            ;; context (isaac-iv5c).
            (and (= :mentions policy) (not (mentioned? message)))
            {:action   :log
             :reason   :logged
             :space    space
             :thread   thread
             :text     (or (:text message) "")
             :sender   (people/render identity)
             :identity identity}

            :else
            {:action      :route
             :space       space
             :thread      thread
             :session-key (or (:session entry)
                              (canon/canonical-name {:space        space
                                                     :tenant       (:tenant opts)
                                                     :display-name (:displayName space-info)
                                                     :dm?          direct?
                                                     :member       (:display-name identity)}))
             ;; The space id, verbatim, on the session a rename must not
             ;; orphan. An entry that pinned a session named it on purpose;
             ;; that session is not this space's to claim.
             :tags        (when-not (:session entry)
                            (some-> (canon/space-tag space) hash-set))
             :crew        (or (:crew entry)
                              (:crew cfg)
                              "main")
             :space-cfg   entry
             :dm?         direct?
             :text        (or (:text message) "")
             :sender      (people/render identity)
             ;; Who spoke, structured: the rendered name is for the turn to
             ;; read, this is for the session's origin to keep.
             :identity    identity}))))))
