(ns isaac.comm.gchat.tenant
  "Which Google organization a Chat comm speaks for.

   One Isaac can carry several Google organizations (isaac-1zkz). A comm names
   its own with `:gchat/google <tenant>`; its sends use that organization's token and
   its spaces subscribe to that organization's topic. A host with one
   organization names none and every comm speaks for it, exactly as before.
   Which organization a comm speaks for is isaac.google.tenants' answer; this
   namespace is only the Chat side of it."
  (:require
    [isaac.comm.gchat.spaces :as spaces]
    [isaac.config.loader :as loader]
    [isaac.google.tenants :as tenants]))

(def KIND :gchat)

(defn- live-config []
  (or (try (loader/snapshot "gchat tenant") (catch Exception _ nil)) {}))

(defn of-comm
  "The organization a live comm's slice speaks for, against the config the
   host is running."
  [slice]
  (tenants/of-comm (live-config) slice))

(defn spaces-for
  "The configured Chat space keys of one organization's comms."
  [config id]
  (vec (mapcat (fn [[_ slice]] (keys (or (:gchat/spaces slice) {})))
               (tenants/comms-for config KIND id))))

(defn discovering?
  "Does one organization let Chat say which spaces it belongs to? One comm
   asking is enough — discovery is per organization, because the token and
   the listing are (isaac-xy2i)."
  [config id]
  (boolean (some (fn [[_ slice]] (:gchat/discover slice))
                 (tenants/comms-for config KIND id))))

(defn discover-every-ms
  "How long one organization's listing stands before discovery asks Chat
   again. The shortest any of its comms asks for wins."
  [config id]
  (let [asks (map (fn [[_ slice]] (spaces/every-ms slice))
                  (tenants/comms-for config KIND id))]
    (if (seq asks) (apply min asks) spaces/DEFAULT-EVERY-MS)))
