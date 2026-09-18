(ns isaac.comm.gchat.chat-api
  "Chat API client. Feature steps redef get-message! — production hits
   spaces.messages.get as the Google user."
  (:require
    [cheshire.core :as json])
  (:import
    (java.net URI)
    (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(defn- parse-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn get-message!
  "GET spaces.messages.get. Throws on non-2xx so the handler can fail the record."
  [name]
  (let [token   ((requiring-resolve 'isaac.google.token/token))
        url     (str "https://chat.googleapis.com/v1/" name)
        client  (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Authorization" (str "Bearer " token))
                    (.GET)
                    (.build))
        resp    (.send client request (HttpResponse$BodyHandlers/ofString))
        status  (.statusCode resp)
        body    (parse-body (.body resp))]
    (if (<= 200 status 299)
      body
      (throw (ex-info (str "Chat API get failed: " status)
                      {:status status :body body :name name})))))
