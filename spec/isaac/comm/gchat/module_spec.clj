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
    (should= :isaac.comm.gchat (:id manifest))))
