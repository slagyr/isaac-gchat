(ns isaac.comm.gchat.tenant
  "Which Google organization a Chat comm speaks for.

   One Isaac can carry several Google organizations (isaac-1zkz). A comm names
   its own with `:gchat/google <tenant>`; its sends use that organization's
   token and its one Chat subscription rides that organization's topic. A host
   with one organization names none and every comm speaks for it, exactly as
   before. Which organization a comm speaks for is isaac.google.tenants'
   answer; this namespace is only the Chat side of it."
  (:require
    [isaac.config.loader :as loader]
    [isaac.google.tenants :as tenants]))

(defn- live-config []
  (or (try (loader/snapshot "gchat tenant") (catch Exception _ nil)) {}))

(defn of-comm
  "The organization a live comm's slice speaks for, against the config the
   host is running."
  [slice]
  (tenants/of-comm (live-config) slice))
