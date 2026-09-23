(ns isaac.comm.gchat.lookup
  "What Chat says a space is, asked once.

   One subscription on `spaces/-` carries every space the account belongs to,
   named or not, DMs included (isaac-ihuc). So the first time a space id turns
   up the comm knows nothing but the id, and one `spaces.get` answers what it
   is called. The answer is kept: display names change rarely, and asking per
   message would be a Chat call per message.

   A message carries its own space, and Chat fills in its display name when it
   has one. A name that differs from what is remembered is a rename — it
   replaces the memory, and the session follows it (isaac.comm.gchat.canon).
   A space Chat will not answer for is not remembered, so the next message
   asks again."
  (:require
    [clojure.string :as str]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.logger :as log]))

(defonce ^:private known* (atom {}))

(defn forget!
  "Drop every remembered space. The next question asks Chat again."
  []
  (reset! known* {}))

(defn -token
  "One organization's Google access token. Its own seam so a spec can answer
   without an auth store."
  [id]
  ((requiring-resolve 'isaac.google.token/token) id))

(defn- display-name [info]
  (not-empty (str (or (:displayName info) ""))))

(defn- listed
  "The account's own listing, when `spaces.get` will not answer: Chat refuses
   spaces.get on a direct message it will happily list (403, yopp 2026-09-23,
   isaac-f4ab). One paged `spaces.list`, the entry whose name matches, or nil."
  [token space]
  (loop [page-token nil]
    (let [body  (chat-api/list-spaces! token (when page-token {:page-token page-token}))
          found (first (filter #(= space (:name %)) (:spaces body)))
          next  (not-empty (str (or (:nextPageToken body) "")))]
      (cond
        found found
        next  (recur next)
        :else nil))))

(defn- forbidden?
  "True when the exception from spaces.get carries Chat's 403 status."
  [e]
  (= 403 (:status (ex-data e))))

(defn- dm-uri
  "The DM's own Chat URL, for the operator to open directly (isaac-qry7)."
  [space]
  (str "https://chat.google.com/dm/" (second (str/split (str space) #"/"))))

(def ^:private invited-action
  "open Chat as the account and accept the request; or turn on Workspace Admin → Google Chat → Chat invitations")

(defn- note-invited!
  "The account is invited to this DM but has never accepted it - Chat 403s
   spaces.get and messages.create alike (isaac-qry7). Logged once per space:
   ask! only runs once per space (space-info's own memoization), so this
   fires exactly once even across many messages in the same DM."
  [space]
  (log/warn :gchat.dm/invited :space space :uri (dm-uri space) :action invited-action))

(defn- ask!
  "Chat's word on a space, or nil when it will not give one. spaces.get first;
   when Chat refuses that, the account's listing, which names DMs too. A 403
   on spaces.get for a DM the listing still names is an invite Chat never
   auto-accepted (isaac-qry7) - not just any refusal, and not a room."
  [id space]
  (let [token (-token id)]
    (try
      (chat-api/get-space! token space)
      (catch Exception e
        (let [listing (try (listed token space)
                           (catch Exception e2
                             (log/warn :gchat.space/unknown :space space :error (.getMessage e2))
                             nil))]
          (cond
            (nil? listing)
            (do (log/warn :gchat.space/unknown :space space :error (.getMessage e))
                nil)

            (and (forbidden? e) (= "DIRECT_MESSAGE" (str (:spaceType listing))))
            (do (note-invited! space)
                (assoc listing :invited? true))

            :else
            listing))))))

(defn space-info
  "What Chat says this space is — its display name and its type — for one
   organization. Asked at most once per space; afterwards the memory answers,
   unless `event-space` (the space the event itself carried) names it
   something else, which replaces the memory."
  [id space event-space]
  (when (seq (str (or space "")))
    (let [remembered (get @known* [id space])
          hinted     (display-name event-space)
          answered   (when-not remembered (ask! id space))
          info       (or remembered answered event-space {})
          info       (if (and hinted (not= hinted (display-name info)))
                       (assoc info :displayName hinted)
                       info)]
      (when (or remembered answered)
        (swap! known* assoc [id space] info))
      info)))
