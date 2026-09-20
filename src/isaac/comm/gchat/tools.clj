(ns isaac.comm.gchat.tools
  "Chat tools for the agent, on Isaac's own token (isaac-jqk2).

   The inbound path stays deterministic — the gate decides who may start a
   turn, and a mention arrives with the conversation since Isaac last spoke
   (isaac-iv5c). These tools are for everything beyond that: which spaces am I
   in, what was said further back, and say this there."
  (:require
    [clojure.string :as str]
    [isaac.comm.gchat :as gchat]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.people-render :as render]))

(defn- error [message] {:isError true :error message})

(defn- args-of [arguments]
  (reduce-kv (fn [m k v] (assoc m (str/lower-case (name k)) v)) {} (or arguments {})))

(defn- token [] (gchat/access-token))

(defn- space->summary [space]
  {:space        (:name space)
   :display-name (:displayName space)
   :type         (or (:spaceType space) (:type space))})

(defn spaces
  "Which spaces is the account in?"
  [_arguments]
  (try
    (let [response (chat-api/list-spaces! (token))]
      {:result {:spaces (mapv space->summary (:spaces response))}})
    (catch Exception e
      (error (str "Chat spaces.list failed: " (.getMessage e))))))

(defn- message->line [message]
  {:sender (render/sender-label message)
   :thread (get-in message [:thread :name])
   :at     (:createTime message)
   :text   (:text message)})

(defn history
  "What was said in a space (or one thread of it), oldest first."
  [arguments]
  (let [args   (args-of arguments)
        space  (some-> (get args "space") str str/trim)
        thread (some-> (get args "thread") str str/trim not-empty)
        since  (some-> (get args "since") str str/trim not-empty)
        limit  (or (some-> (get args "limit") str parse-long) 50)]
    (cond
      (str/blank? space)
      (error "space is required: a spaces/<id> resource name")

      :else
      (try
        (let [response (chat-api/list-messages! (token) space
                                                {:page-size limit
                                                 :thread    thread
                                                 :filter*   (when since
                                                              (str "createTime > \"" since "\""))})
              lines    (mapv message->line (:messages response))]
          {:result {:space    space
                    :messages (vec (sort-by :at lines))
                    :more?    (boolean (seq (str (or (:nextPageToken response) ""))))}})
        (catch Exception e
          (error (str "Chat messages.list failed: " (.getMessage e))))))))

(defn send-message
  "Say something in a space, optionally in a thread."
  [arguments]
  (let [args   (args-of arguments)
        space  (some-> (get args "space") str str/trim)
        thread (some-> (get args "thread") str str/trim not-empty)
        text   (some-> (get args "text") str)]
    (cond
      (str/blank? space) (error "space is required: a spaces/<id> resource name")
      (str/blank? text)  (error "text is required")
      :else
      (try
        (let [message (chat-api/create-message! {:space space :thread thread
                                                 :text text :token (token)})]
          {:result {:message (:name message)
                    :thread  (get-in message [:thread :name])}})
        (catch Exception e
          (error (str "Chat message send failed: " (.getMessage e))))))))

(defn spaces-tool-factory [_]
  {:description "List the Google Chat spaces and DMs this Isaac account is a member of."
   :parameters  {:type "object" :properties {}}
   :handler     #'spaces})

(defn history-tool-factory [_]
  {:description (str "Read what was said in a Chat space, oldest first. Narrow to one thread, "
                     "or to messages after an RFC-3339 time. Use this to reach further back "
                     "than the context a mention already carries.")
   :parameters  {:type       "object"
                 :properties {"space"  {:type "string" :description "spaces/<id> resource name"}
                              "thread" {:type "string" :description "Optional spaces/<id>/threads/<id>"}
                              "since"  {:type "string" :description "Optional RFC-3339 time, exclusive"}
                              "limit"  {:type "integer" :description "Messages to return (default 50)"}}
                 :required   ["space"]}
   :handler     #'history})

(defn send-tool-factory [_]
  {:description (str "Post a message to a Chat space or thread as this Isaac account. "
                     "Side-effecting: people will see it.")
   :parameters  {:type       "object"
                 :properties {"space"  {:type "string" :description "spaces/<id> resource name"}
                              "thread" {:type "string" :description "Optional thread to reply in"}
                              "text"   {:type "string" :description "What to say"}}
                 :required   ["space" "text"]}
   :handler     #'send-message})
