(ns isaac.comm.gchat
  "Google Chat comm factory. Inbound lives on the google handler; send!
   is child isaac-2wr9."
  (:require
    [isaac.comm.factory :as factory]
    [isaac.comm.protocol :as comm]
    [isaac.config.root :as root]
    [isaac.nexus :as nexus]))

(defn- send!* [_comm _record]
  {:ok true})

(deftype GchatComm [host cfg])

(extend GchatComm
  comm/Comm
  (merge comm/defaults
         {:send! send!*}))

(defn make [host]
  (->GchatComm host (atom nil)))

(defmethod factory/create :gchat [node-path slice]
  (let [comm (make {:name (last node-path)
                    :root (or (nexus/get :root) (root/current-root))})]
    (reset! (.-cfg comm) slice)
    comm))
