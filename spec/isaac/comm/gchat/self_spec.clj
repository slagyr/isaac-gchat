(ns isaac.comm.gchat.self-spec
  (:require
    [isaac.comm.gchat.self :as sut]
    [speclj.core :refer :all]))

(describe "Isaac's own Chat identity, per tenant (isaac-mm7o)"

  (before (sut/forget!))
  (after (sut/forget!))

  (it "learns a tenant's users/<id> from the message an outbound send returns"
    (sut/learn-from-send! :tonotop {:name "spaces/ENG/messages/9"
                                    :sender {:name "users/101936183306307394083"}})
    (should= "users/101936183306307394083" (sut/account-user :tonotop)))

  (it "reads string-keyed responses too"
    (sut/learn-from-send! :tonotop {"sender" {"name" "users/42"}})
    (should= "users/42" (sut/account-user :tonotop)))

  (it "returns the response unchanged so it can wrap a send"
    (let [resp {:sender {:name "users/7"} :text "hi"}]
      (should= resp (sut/learn-from-send! :tonotop resp))))

  (it "keeps what it knows when a response carries no sender"
    (sut/learn-from-send! :tonotop {:sender {:name "users/7"}})
    (sut/learn-from-send! :tonotop {:text "no sender here"})
    (should= "users/7" (sut/account-user :tonotop)))

  (it "keeps two tenants' ids apart"
    (sut/learn-from-send! :tonotop {:sender {:name "users/tonotop-id"}})
    (sut/learn-from-send! :acme {:sender {:name "users/acme-id"}})
    (should= "users/tonotop-id" (sut/account-user :tonotop))
    (should= "users/acme-id" (sut/account-user :acme)))

  (it "a comm naming no tenant (a single-organization host) still learns, keyed by nil"
    (sut/learn-from-send! nil {:sender {:name "users/only-one"}})
    (should= "users/only-one" (sut/account-user nil)))

  (describe "resolve-account-user"

    (it "prefers the configured id over anything a send taught us, for that tenant"
      (sut/learn-from-send! :tonotop {:sender {:name "users/learned"}})
      (should= "users/configured"
               (sut/resolve-account-user :tonotop {:gchat/account-id "users/configured"})))

    (it "falls back to the learned id for that tenant when config sets none"
      (sut/learn-from-send! :tonotop {:sender {:name "users/learned"}})
      (should= "users/learned" (sut/resolve-account-user :tonotop {})))

    (it "never answers with another tenant's learned id"
      (sut/learn-from-send! :acme {:sender {:name "users/acme-id"}})
      (should= nil (sut/resolve-account-user :tonotop {})))

    (it "is nil before any send and with no config"
      (should= nil (sut/resolve-account-user :tonotop {})))))
