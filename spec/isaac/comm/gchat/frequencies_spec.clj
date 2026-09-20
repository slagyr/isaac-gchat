(ns isaac.comm.gchat.frequencies-spec
  "A space entry speaks the agent's session vocabulary (isaac-tund)."
  (:require
    [isaac.comm.gchat.handler :as sut]
    [speclj.core :refer :all]))

(describe "a space entry as session frequencies"

  (it "keeps the canonical per-space session when the entry selects nothing"
    (should= {:create :if-missing :reach :one :prefer :recent
              :default-session-key "gchat-spaces-ENG"}
             (sut/space->frequencies {:name "Engineering"} "gchat-spaces-ENG")))

  (it "selects by tags, with the defaults hail uses"
    (should= {:session-tags [:ops] :create :if-missing :reach :one :prefer :recent}
             (sut/space->frequencies {:session-tags [:ops]} "gchat-spaces-ENG")))

  (it "carries crew, reach, prefer and create through as written"
    (should= {:session-tags [:ops] :crew "yopp" :reach :all :prefer :oldest :create :never}
             (sut/space->frequencies {:session-tags [:ops] :crew "yopp"
                                      :reach :all :prefer :oldest :create :never}
                                     "gchat-spaces-ENG")))

  (it "pins an explicit session as an exact selector"
    (should= {:session ["ops-room"] :create :if-missing :reach :one :prefer :recent}
             (sut/space->frequencies {:session "ops-room"} "gchat-spaces-ENG")))

  (it "prefers tags over an explicit session when an entry names both"
    (let [freq (sut/space->frequencies {:session "ops-room" :session-tags [:ops]} "gchat-spaces-ENG")]
      (should= [:ops] (:session-tags freq))))

  (it "ignores keys that are not the agent's vocabulary"
    (should-not (contains? (sut/space->frequencies {:name "Engineering" :respond :all} "k") :respond)))
  )
