(ns isaac.comm.gchat.spaces
  "The spaces the account belongs to.

   Listing every space in config was the Discord channel-map chore again. The
   account's own membership already is the list: `spaces.list` under its token
   names every space and DM it belongs to, and that is what the registration
   timer subscribes and what the gate routes. Inviting the account to a space
   grants it ingest; removing it takes that back on the next listing
   (isaac-xy2i). Configured entries are overrides, never the gate.

   The registration timer ticks every 30 seconds and membership does not, so a
   listing stands for `gchat/discover-every-ms` (5 minutes by default) and every
   question in between is answered from it — including the handler's, when it
   needs a space's display name to name a session."
  (:require
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]))

(def SPACE-TYPES
  "What discovery asks Chat for: named spaces and direct messages."
  "spaceType = \"SPACE\" OR spaceType = \"DIRECT_MESSAGE\"")

(def DEFAULT-EVERY-MS
  "How long a listing stands before discovery asks Chat again. The registration
   tick fires every 30 seconds; who is in which space is not a 30-second
   question."
  300000)

(def EVERY-MS-KEY :gchat/discover-every-ms)

(defn every-ms
  "How long one comm's listing stands."
  [slice]
  (or (get slice EVERY-MS-KEY) DEFAULT-EVERY-MS))

(defonce ^:private listings* (atom {}))

(defn forget-known!
  "Drop every organization's listing. The next question asks Chat again."
  []
  (reset! listings* {}))

(defn- now-ms []
  (.toEpochMilli (memory/now)))

(defn- fresh? [id every-ms*]
  (when-let [at (get-in @listings* [id :at])]
    (< (- (now-ms) at) (long (or every-ms* DEFAULT-EVERY-MS)))))

(defn- remember! [id spaces]
  (swap! listings* assoc id {:at     (now-ms)
                             :spaces (into {} (map (juxt :name identity)) spaces)})
  spaces)

(defn- remembered [id]
  (vec (vals (get-in @listings* [id :spaces] {}))))

(defn list-all!
  "Every space the account is a member of, following Chat's paging."
  [token]
  (loop [page nil
         acc  []]
    (let [response (chat-api/list-spaces! token (cond-> {:filter* SPACE-TYPES}
                                                  page (assoc :page-token page)))
          acc      (into acc (:spaces response))
          next*    (not-empty (str (or (:nextPageToken response) "")))]
      (if next* (recur next* acc) acc))))

(defn -token
  "One organization's Google access token. Its own seam so a spec can answer
   without an auth store."
  [id]
  ((requiring-resolve 'isaac.google.token/token) id))

(defn discovered
  "The spaces one organization's account belongs to. Chat is asked at most once
   per `every-ms`; in between, the last listing stands. Never throws — a
   listing Chat refuses leaves the configured entries standing alone, says so,
   and is tried again on the next tick."
  ([id] (discovered id DEFAULT-EVERY-MS))
  ([id every-ms*]
   (if (fresh? id every-ms*)
     (remembered id)
     (try
       (remember! id (list-all! (-token id)))
       (catch Exception e
         (log/warn :gchat.discovery/failed :organization id :error (.getMessage e))
         (remembered id))))))

(defn known
  "What Chat says a space is — its display name and its type — from that
   organization's current listing. A space the listing does not hold costs a
   fresh listing at most once per `every-ms`."
  [id every-ms* space]
  (or (get-in @listings* [id :spaces space])
      (do (discovered id every-ms*)
          (get-in @listings* [id :spaces space]))))
