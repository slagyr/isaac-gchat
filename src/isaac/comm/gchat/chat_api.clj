(ns isaac.comm.gchat.chat-api
  "Chat API client. Feature steps redef get-message! / -http! — production
   hits Chat as the Google user."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str])
  (:import
    (java.net URLEncoder)))

(def ^:private chat-base "https://chat.googleapis.com/v1")

(defn- parse-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn- encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn- with-query [url query]
  (if (seq query)
    (str url "?"
         (str/join "&" (map (fn [[k v]]
                              (str (name k) "=" (encode v)))
                            query)))
    url))

(defonce ^:private last-request* (atom nil))

(defn last-request []
  @last-request*)

(defn -http!
  "Internal HTTP seam. Returns {:status n :body parsed}."
  [{:keys [method url headers query body] :as req}]
  (reset! last-request* req)
  (let [full-url (with-query url query)
        payload  (when body (json/generate-string body))
        opts     (cond-> {:headers (or headers {}) :throw false}
                   payload (assoc :body payload))
        response (case (keyword method)
                   :get  (http/get full-url opts)
                   :post (http/post full-url opts)
                   (http/request (assoc opts :method (keyword method) :uri full-url)))
        status   (:status response 0)
        parsed   (parse-body (:body response))]
    {:status status :body parsed}))

(defn get-message!
  "GET spaces.messages.get. Throws on non-2xx so the handler can fail the record."
  [name]
  (let [token ((requiring-resolve 'isaac.google.token/token))
        resp  (-http! {:method  "GET"
                       :url     (str chat-base "/" name)
                       :headers {"Authorization" (str "Bearer " token)}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API get failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :name name})))))

(defn get-space!
  "GET spaces.get — what Chat calls a space and what kind it is. Throws on
   non-2xx so the caller can decide what an unanswerable space is worth."
  [token space]
  (let [resp (-http! {:method  "GET"
                      :url     (str chat-base "/" space)
                      :headers {"Authorization" (str "Bearer " token)}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API spaces.get failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :space space})))))

(defn create-message!
  "POST spaces.messages.create. When :thread is set, reply in that thread."
  [{:keys [space thread text token]}]
  (let [query (when (seq thread)
                {:messageReplyOption "REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD"})
        body  (cond-> {:text text}
                (seq thread) (assoc :thread {:name thread}))
        resp  (-http! {:method  "POST"
                       :url     (str chat-base "/" space "/messages")
                       :headers {"Authorization" (str "Bearer " token)
                                 "Content-Type"  "application/json"}
                       :query   query
                       :body    body})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API create failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :space space})))))

(defn create-reaction!
  "POST spaces.messages.reactions.create — react to the triggering message with
   an emoji. Chat lets a user add and remove reactions and neither notifies
   (isaac-1bq1). Returns the body, which carries the reaction's own :name for
   the later delete-reaction!."
  [{:keys [message emoji token]}]
  (let [resp (-http! {:method  "POST"
                      :url     (str chat-base "/" message "/reactions")
                      :headers {"Authorization" (str "Bearer " token)
                                "Content-Type"  "application/json"}
                      :body    {:emoji {:unicode emoji}}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API reactions.create failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :message message})))))

(defn delete-reaction!
  "DELETE spaces.messages.reactions.delete — remove a reaction by its own
   resource name (as returned by create-reaction!)."
  [{:keys [reaction token]}]
  (let [resp (-http! {:method  "DELETE"
                      :url     (str chat-base "/" reaction)
                      :headers {"Authorization" (str "Bearer " token)}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API reactions.delete failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :reaction reaction})))))

(defn find-direct-message!
  "GET spaces:findDirectMessage?name=users/<email>. Nil on 404."
  [email token]
  (let [resp (-http! {:method  "GET"
                      :url     (str chat-base "/spaces:findDirectMessage")
                      :headers {"Authorization" (str "Bearer " token)}
                      :query   {:name (str "users/" email)}})]
    (cond
      (<= 200 (:status resp) 299) (:body resp)
      (= 404 (:status resp))      nil
      :else
      (throw (ex-info (str "Chat API findDirectMessage failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :email email})))))

(defn setup-direct-message!
  "POST spaces:setup DIRECT_MESSAGE with that member."
  [email token]
  (let [resp (-http! {:method  "POST"
                      :url     (str chat-base "/spaces:setup")
                      :headers {"Authorization" (str "Bearer " token)
                                "Content-Type"  "application/json"}
                      :body    {:space        {:spaceType "DIRECT_MESSAGE"}
                                :memberships  [{:member {:name (str "users/" email)}}]}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API spaces:setup failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :email email})))))

(defn list-spaces!
  "GET spaces.list — the spaces the account is a member of."
  [token & [{:keys [page-size page-token filter*]}]]
  (let [resp (-http! {:method  "GET"
                      :url     (str chat-base "/spaces")
                      :headers {"Authorization" (str "Bearer " token)}
                      :query   (cond-> {:pageSize (or page-size 100)}
                                 page-token (assoc :pageToken page-token)
                                 filter*    (assoc :filter filter*))})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API spaces.list failed: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))))

(defn list-messages!
  "GET spaces.messages.list for a space, newest last. `filter*` takes Chat's
   own filter syntax (createTime > \"…\"); `thread` narrows to one thread."
  [token space & [{:keys [page-size page-token filter* thread]}]]
  (let [filters (cond-> []
                  (seq filter*) (conj filter*)
                  (seq thread)  (conj (str "thread.name = \"" thread "\"")))
        resp    (-http! {:method  "GET"
                         :url     (str chat-base "/" space "/messages")
                         :headers {"Authorization" (str "Bearer " token)}
                         :query   (cond-> {:pageSize (or page-size 50)}
                                    page-token    (assoc :pageToken page-token)
                                    (seq filters) (assoc :filter (str/join " AND " filters)))})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Chat API messages.list failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :space space})))))
