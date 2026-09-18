(ns isaac.comm.gchat.target
  "Resolve send! targets: configured space name, resource name, or DM email."
  (:require
    [clojure.string :as str]))

(defn- space-resource [k]
  (let [n (if (keyword? k) (name k) (str k))]
    (if (str/starts-with? n "spaces/")
      n
      (str "spaces/" n))))

(defn resolve-space
  "Friendly :name or resource name → Chat space resource (spaces/…)."
  [cfg space]
  (when (seq space)
    (let [spaces (or (:gchat/spaces cfg) {})]
      (or (when (contains? spaces (keyword space))
            (space-resource space))
          (when (contains? spaces space)
            (space-resource space))
          (some (fn [[k v]]
                  (when (= space (:name v))
                    (space-resource k)))
                spaces)
          (when (str/starts-with? (str space) "spaces/")
            (str space))))))
