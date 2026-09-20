(ns isaac.comm.gchat.registration-spec
  (:require
    [isaac.comm.gchat.registration :as sut]
    [isaac.config.loader :as loader]
    [isaac.google.events :as events]
    [isaac.google.tenants :as tenants]
    [speclj.core :refer :all]))

(def flat
  {:google {:topic "projects/marigold/topics/isaac"}
   :comms  {:gchat {:gchat/spaces {:spaces/ENG  {:name "engineering"}
                                   :spaces/PROD {:name "product"}}}}})

(def tenanted
  {:google {:tonotop {:project "marigold"  :topic "projects/marigold/topics/isaac"}
            :acme    {:project "acme-prod" :topic "projects/acme-prod/topics/isaac"}}
   :comms  {:gchat      {:google       :tonotop
                         :gchat/spaces {:spaces/ENG {:name "engineering"}}}
            :gchat-acme {:type         :gchat
                         :google       :acme
                         :gchat/spaces {:spaces/ACME {:name "acme-eng"}}}}})

(defn- created [config tenant key]
  (let [body (atom nil)]
    (with-redefs [loader/snapshot            (fn [_] config)
                  events/create-subscription! (fn [b] (reset! body b) {:name "subscriptions/s-1"})]
      (binding [tenants/*tenant* tenant]
        (sut/create! key)))
    @body))

(describe "gchat registration contribution"

  (it "keys are the configured space resource names"
    (with-redefs [loader/snapshot (fn [_] flat)]
      (should= ["spaces/ENG" "spaces/PROD"] (sut/space-keys))))

  (it "keys are only the spaces of the organization this pass acts for"
    (with-redefs [loader/snapshot (fn [_] tenanted)]
      (should= ["spaces/ACME"] (binding [tenants/*tenant* :acme] (sut/space-keys)))
      (should= ["spaces/ENG"] (binding [tenants/*tenant* :tonotop] (sut/space-keys)))))

  (it "create body is pointer-only against the shared topic"
    (let [body (created flat nil "spaces/ENG")]
      (should= "//chat.googleapis.com/spaces/ENG" (:targetResource body))
      (should= false (get-in body [:payloadOptions :includeResource]))
      (should= "projects/marigold/topics/isaac" (get-in body [:notificationEndpoint :pubsubTopic]))
      (should= "google.workspace.chat.message.v1.created" (first (:eventTypes body)))
      (should= "google.workspace.chat.message.v1.deleted" (last (:eventTypes body)))))

  (it "a space subscribes to its own organization's topic"
    (should= "projects/acme-prod/topics/isaac"
             (get-in (created tenanted :acme "spaces/ACME") [:notificationEndpoint :pubsubTopic]))
    (should= "projects/marigold/topics/isaac"
             (get-in (created tenanted :tonotop "spaces/ENG") [:notificationEndpoint :pubsubTopic])))

  (it "expiry reads expireTime from Google"
    (should= "2026-09-25T12:00:00Z" (sut/expiry {:expireTime "2026-09-25T12:00:00Z"})))
  )
