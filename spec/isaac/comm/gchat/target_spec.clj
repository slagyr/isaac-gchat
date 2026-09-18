(ns isaac.comm.gchat.target-spec
  (:require
    [isaac.comm.gchat.target :as sut]
    [speclj.core :refer :all]))

(def cfg
  {:gchat/spaces {:spaces/ENG {:name "engineering" :crew "main"}}})

(describe "gchat target resolution"

  (it "resolves a friendly space name to the resource name"
    (should= "spaces/ENG" (sut/resolve-space cfg "engineering")))

  (it "accepts a resource name as-is"
    (should= "spaces/ENG" (sut/resolve-space cfg "spaces/ENG")))

  (it "returns nil when the space is unknown"
    (should-be-nil (sut/resolve-space cfg "unknown")))

  )
