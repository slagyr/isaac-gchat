(ns isaac.comm.gchat.tenant-spec
  (:require
    [isaac.comm.gchat.tenant :as sut]
    [speclj.core :refer :all]))

(def tenanted
  {:google {:tonotop {:project "marigold"  :topic "projects/marigold/topics/isaac"}
            :acme    {:project "acme-prod" :topic "projects/acme-prod/topics/isaac"}}
   :comms  {:gchat      {:gchat/account "yopp@tonotop.com"
                         :google        :tonotop
                         :gchat/spaces  {:spaces/ENG {:name "engineering"}}}
            :gchat-acme {:type         :gchat
                         :google       :acme
                         :gchat/spaces {:spaces/ACME {:name "acme-eng"}}}
            :discord    {:type :discord}}})

(def one-organization
  {:google {:tonotop {:topic "projects/marigold/topics/isaac"}}
   :comms  {:gchat {:gchat/spaces {:spaces/ENG {:name "engineering"}}}}})

(describe "the Chat spaces of one organization"

  (it "spaces belong to the organization their comm speaks for"
    (should= [:spaces/ACME] (sut/spaces-for tenanted :acme))
    (should= [:spaces/ENG] (sut/spaces-for tenanted :tonotop)))

  ;; A comm on a one-organization host names none, and the one configured
  ;; organization is what it speaks for (isaac-okfj).
  (it "a host with one organization keeps every comm's spaces"
    (should= [:spaces/ENG] (sut/spaces-for one-organization :tonotop)))

  (it "an organization with no comm has no spaces"
    (should= [] (sut/spaces-for tenanted :nobody)))
  )
