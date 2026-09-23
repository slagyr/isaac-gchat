(ns isaac.comm.gchat.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.module.protocol]
    [isaac.comm.gchat.module :as sut]
    [speclj.core :refer [describe it should should=]]))

(def manifest
  (edn/read-string (slurp "resources/isaac-manifest.edn")))

(describe "isaac.comm.gchat.module"

  (it "returns a module"
    (should (satisfies? isaac.module.protocol/Module (sut/create-module))))

  (it "declares its module id"
    (should= :isaac.comm.gchat (:id manifest)))

  (it "contributes the gchat comm impl"
    (should= 'isaac.comm.gchat (get-in manifest [:isaac.agent/comm :gchat :namespace])))

  (it "contributes Chat message handlers"
    (should= 'isaac.comm.gchat.handler/handle-event
             (get-in manifest [:isaac.google/handler "google.workspace.chat.message.v1.created"])))

  (it "contributes a Chat registration and the messages, spaces, memberships and spaces.create scopes"
    (should= 'isaac.comm.gchat.registration/create!
             (get-in manifest [:isaac.google/registration :chat :create!]))
    (should= ["https://www.googleapis.com/auth/chat.messages"
              "https://www.googleapis.com/auth/chat.spaces.readonly"
              "https://www.googleapis.com/auth/chat.memberships.readonly"
              "https://www.googleapis.com/auth/chat.spaces.create"
              "https://www.googleapis.com/auth/chat.memberships"]
             (:isaac.google/scopes manifest)))
  )
