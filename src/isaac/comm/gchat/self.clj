(ns isaac.comm.gchat.self
  "Isaac's own Chat identity, per Google organization (tenant).

   `:gchat/account` in config is an email. Chat pushes Isaac's own replies back
   as events carrying only the sender's `users/<id>` and no email, so the gate
   cannot recognise them as self by email alone. It dropped them as :sender
   instead — the right outcome by luck, since the id was not in the allow-list.
   An operator who allows `domain:<id>` would let Isaac's own replies through
   and create an echo loop (isaac-mm7o).

   The id costs nothing to learn: every outbound send returns the created
   message, whose :sender is Isaac. A Chat comm speaks for exactly one Google
   organization (isaac-1zkz), so the cache is keyed by tenant — one process can
   run several organizations, and an id learned for one must never be mistaken
   for another's self. Cached in memory for the process; a restart relearns it
   on the first send, and `:gchat/account-id` in a comm's own config
   short-circuits the wait for that comm's organization.")

(defonce ^:private account-user* (atom {}))

(defn account-user
  "Isaac's own users/<id> for one tenant, if known."
  [tenant]
  (get @account-user* tenant))

(defn learn-from-send!
  "Remember the sender of a message Isaac just created, for one tenant.
   Returns the response unchanged so it can wrap a send call."
  [tenant response]
  (let [user (or (get-in response [:sender :name])
                 (get-in response ["sender" "name"]))]
    (when (seq (str (or user "")))
      (swap! account-user* assoc tenant (str user))))
  response)

(defn forget!
  "Drop every cached id — for tests and for a re-auth onto a different
   account."
  []
  (reset! account-user* {}))

(defn resolve-account-user
  "One tenant's account users/<id>: from that comm's config when set, else
   whatever a send under that tenant taught us. Never answers with an id
   learned for a different tenant."
  [tenant cfg]
  (let [configured (:gchat/account-id cfg)]
    (if (seq (str (or configured "")))
      (str configured)
      (account-user tenant))))
