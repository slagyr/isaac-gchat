(ns isaac.comm.gchat.tenant-spec
  (:require
    [isaac.comm.gchat.tenant :as sut]
    [isaac.config.loader :as loader]
    [speclj.core :refer :all]))

(def tenanted
  {:google {:tonotop {:project "marigold"  :topic "projects/marigold/topics/isaac"}
            :acme    {:project "acme-prod" :topic "projects/acme-prod/topics/isaac"}}
   :comms  {:gchat      {:gchat/account "yopp@tonotop.com"
                         :google        :tonotop}
            :gchat-acme {:type   :gchat
                         :google :acme}
            :discord    {:type :discord}}})

(def one-organization
  {:google {:tonotop {:topic "projects/marigold/topics/isaac"}}
   :comms  {:gchat {:gchat/account "yopp@tonotop.com"}}})

(describe "which organization a Chat comm speaks for"

  (it "the one its slice names"
    (with-redefs [loader/snapshot (fn [_] tenanted)]
      (should= :acme (sut/of-comm (get-in tenanted [:comms :gchat-acme])))
      (should= :tonotop (sut/of-comm (get-in tenanted [:comms :gchat])))))

  ;; A comm on a one-organization host names none, and the one configured
  ;; organization is what it speaks for (isaac-okfj).
  (it "a host with one organization needs no naming"
    (with-redefs [loader/snapshot (fn [_] one-organization)]
      (should= :tonotop (sut/of-comm (get-in one-organization [:comms :gchat])))))
  )
