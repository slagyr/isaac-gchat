(ns isaac.comm.gchat
  "Google Chat comm factory. Inbound lives on the google handler; send!
   and on-reply post as the Google user."
  (:require
    [clojure.string :as str]
    [isaac.comm.factory :as factory]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.format :as fmt]
    [isaac.comm.gchat.target :as target]
    [isaac.comm.protocol :as comm]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defonce ^:private origin-by-session (atom {}))

(defn- slice [comm]
  (or @(.-cfg comm) {}))

(defn access-token []
  ((requiring-resolve 'isaac.google.token/token)))

(defn- post-chunks! [space thread text cap token]
  (let [chunks (fmt/split-content (fmt/->chat-text text) cap)]
    (doseq [chunk chunks]
      (chat-api/create-message! {:space  space
                                 :thread thread
                                 :text   chunk
                                 :token  token}))))

(defn- resolve-dm-space! [email token]
  (or (:name (chat-api/find-direct-message! email token))
      (:name (chat-api/setup-direct-message! email token))))

(defn- send!* [comm record]
  (try
    (let [cfg    (slice comm)
          token  (access-token)
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
        (do (post-chunks! space thread text cap token)
            {:ok true})))
    (catch Exception e
      (log/error :gchat.send/failed :error (.getMessage e))
      {:ok false :transient? true :error (.getMessage e)})))

(defn- on-cycle-start* [_comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (when (= :gchat (:kind origin))
      (swap! origin-by-session assoc session-key origin))))

(defn- on-reply* [comm session-key text]
  (when-let [{:keys [space thread]} (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (let [cfg   (slice comm)
            token (access-token)
            cap   (or (:gchat/message-cap cfg) fmt/default-message-cap)]
        (post-chunks! space thread text cap token)))))

(defn- on-turn-end* [_comm session-key _result]
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
