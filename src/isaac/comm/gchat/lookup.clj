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

(defn- ask!
  "Chat's word on a space, or nil when it will not give one."
  [id space]
  (try
    (chat-api/get-space! (-token id) space)
    (catch Exception e
      (log/warn :gchat.space/unknown :space space :error (.getMessage e))
      nil)))

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
