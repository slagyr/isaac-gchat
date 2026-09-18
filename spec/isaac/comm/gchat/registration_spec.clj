(ns isaac.comm.gchat.registration-spec
  (:require
    [isaac.comm.gchat.registration :as sut]
    [isaac.config.loader :as loader]
    [speclj.core :refer :all]))

(describe "gchat registration contribution"

  (it "keys are the configured space resource names"
    (with-redefs [isaac.comm.gchat.handler/-load-cfg
                  (fn [] {:gchat/spaces {:spaces/ENG {:name "engineering"}
                                         :spaces/PROD {:name "product"}}})]
      (should= ["spaces/ENG" "spaces/PROD"] (sut/space-keys))))

  (it "create body is pointer-only against the shared topic"
    (with-redefs [loader/snapshot (fn [_] {:google {:topic "projects/marigold/topics/isaac"}})]
      (let [topic (get-in (loader/snapshot "x") [:google :topic])
            body  {:targetResource       (str "//chat.googleapis.com/spaces/ENG")
                   :eventTypes           sut/EVENT-TYPES
                   :notificationEndpoint {:pubsubTopic topic}
                   :payloadOptions       {:includeResource false}}]
        (should= "//chat.googleapis.com/spaces/ENG" (:targetResource body))
        (should= false (get-in body [:payloadOptions :includeResource]))
        (should= "projects/marigold/topics/isaac" (get-in body [:notificationEndpoint :pubsubTopic]))
        (should= "google.workspace.chat.message.v1.created" (first (:eventTypes body)))
        (should= "google.workspace.chat.message.v1.deleted" (last (:eventTypes body))))))

  (it "expiry reads expireTime from Google"
    (should= "2026-09-25T12:00:00Z" (sut/expiry {:expireTime "2026-09-25T12:00:00Z"})))
  )
