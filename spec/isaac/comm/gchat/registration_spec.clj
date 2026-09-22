(ns isaac.comm.gchat.registration-spec
  (:require
    [isaac.comm.gchat.registration :as sut]
    [isaac.config.loader :as loader]
    [isaac.google.events :as events]
    [isaac.google.tenants :as tenants]
    [speclj.core :refer :all]))

(def one-organization
  {:google {:tonotop {:topic "projects/marigold/topics/isaac"}}
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

  (it "an organization has exactly one key — every space at once"
    (with-redefs [loader/snapshot (fn [_] one-organization)]
      (should= ["spaces/-"] (sut/subscription-keys))))

  (it "configured spaces subscribe nothing of their own"
    (with-redefs [loader/snapshot (fn [_] tenanted)]
      (should= ["spaces/-"] (binding [tenants/*tenant* :acme] (sut/subscription-keys)))
      (should= ["spaces/-"] (binding [tenants/*tenant* :tonotop] (sut/subscription-keys)))))

  (it "create body targets all spaces, pointer-only, against the shared topic"
    (let [body (created one-organization nil "spaces/-")]
      (should= "//chat.googleapis.com/spaces/-" (:targetResource body))
      (should= false (get-in body [:payloadOptions :includeResource]))
      (should= "projects/marigold/topics/isaac" (get-in body [:notificationEndpoint :pubsubTopic]))))

  (it "the subscription carries what was said and who came and went"
    (let [types (:eventTypes (created one-organization nil "spaces/-"))]
      (should-contain "google.workspace.chat.message.v1.created" types)
      (should-contain "google.workspace.chat.message.v1.updated" types)
      (should-contain "google.workspace.chat.message.v1.deleted" types)
      (should-contain "google.workspace.chat.membership.v1.created" types)
      (should-contain "google.workspace.chat.membership.v1.updated" types)
      (should-contain "google.workspace.chat.membership.v1.deleted" types)))

  (it "each organization's subscription rides its own topic"
    (should= "projects/acme-prod/topics/isaac"
             (get-in (created tenanted :acme "spaces/-") [:notificationEndpoint :pubsubTopic]))
    (should= "projects/marigold/topics/isaac"
             (get-in (created tenanted :tonotop "spaces/-") [:notificationEndpoint :pubsubTopic])))

  (it "expiry reads expireTime from Google"
    (should= "2026-09-25T12:00:00Z" (sut/expiry {:expireTime "2026-09-25T12:00:00Z"})))
  )
