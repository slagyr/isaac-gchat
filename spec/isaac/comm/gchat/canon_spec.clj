(ns isaac.comm.gchat.canon-spec
  (:require
    [isaac.comm.gchat.canon :as sut]
    [speclj.core :refer :all]))

(describe "the canonical session of a space"

  (it "slugs the way the session store will"
    (should= "yopp-test" (sut/slug "Yopp Test"))
    (should= "micah-martin" (sut/slug "Micah Martin"))
    (should= "spaces-aaqa7rg5uyc" (sut/slug "spaces/AAQA7rg5Uyc")))

  (it "the space id is the resource's last part, case and all"
    (should= "AAQA7rg5Uyc" (sut/space-id "spaces/AAQA7rg5Uyc"))
    (should= "AAQA7rg5Uyc" (sut/space-id "spaces/AAQA7rg5Uyc/messages/1")))

  (it "the tag carries the id verbatim so a rename cannot orphan the session"
    (should= :space:AAQA7rg5Uyc (sut/space-tag "spaces/AAQA7rg5Uyc"))
    (should-be-nil (sut/space-tag "")))

  (it "the thread id is the resource's last part, case and all"
    (should= "T1" (sut/thread-id "spaces/ENG/threads/T1"))
    (should= "mtEwy7PEiSs" (sut/thread-id "spaces/ENG/threads/mtEwy7PEiSs"))
    (should= "bare" (sut/thread-id "bare")))

  (it "the marker keeps only the tail of a long thread id"
    (should= "T1" (sut/thread-short "spaces/ENG/threads/T1"))
    (should= "wy7PEiSs" (sut/thread-short "spaces/ENG/threads/mtEwy7PEiSs"))
    (should= "[thread:T1]" (sut/thread-marker "spaces/ENG/threads/T1"))
    (should-be-nil (sut/thread-marker nil))
    (should-be-nil (sut/thread-marker "")))

  (it "a rendered line carries the marker ahead of who spoke"
    (should= "[thread:T1] Ada Lovelace: hi there"
             (sut/rendered-line {:thread "spaces/ENG/threads/T1" :sender "Ada Lovelace" :text "hi there"}))
    (should= "someone: "
             (sut/rendered-line {:sender nil :text nil}))
    (should= "Yopp: on it"
             (sut/rendered-line {:sender "Yopp" :text "on it"})))

  (it "a named space is named for its display name"
    (should= "gchat-yopp-test"
             (sut/canonical-name {:space "spaces/AAQA7rg5Uyc" :display-name "Yopp Test"})))

  (it "a DM is named for the other member"
    (should= "gchat-dm-micah-martin"
             (sut/canonical-name {:space "spaces/DM1" :dm? true :member "Micah Martin"})))

  (it "a space Chat has not named falls back to its resource name"
    (should= "gchat-spaces-eng" (sut/canonical-name {:space "spaces/ENG"}))
    (should= "gchat-spaces-dm1" (sut/canonical-name {:space "spaces/DM1" :dm? true})))

  (it "the name carries the organization"
    (should= "gchat-tonotop-yopp-test"
             (sut/canonical-name {:space        "spaces/AAQA7rg5Uyc"
                                  :display-name "Yopp Test"
                                  :tenant       :tonotop})))

  (context "settling the session"

    (it "the session already tagged with this space is the one, and follows the new name"
      (should= {:session-key "gchat-yopp-lab" :rename-from "gchat-yopp-test"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-lab"}
                           [{:id "gchat-yopp-test" :name "gchat-yopp-test"
                             :tags #{:space:AAQA7rg5Uyc}}])))

    (it "a name that has not moved renames nothing"
      (should= {:session-key "gchat-yopp-test"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-test"}
                           [{:id "gchat-yopp-test" :name "gchat-yopp-test"
                             :tags #{:space:AAQA7rg5Uyc}}])))

    (it "an untagged session of the same name is this space's — nothing else claims it"
      (should= {:session-key "gchat-yopp-test"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-test"}
                           [{:id "gchat-yopp-test" :name "gchat-yopp-test" :tags #{}}])))

    (it "two spaces sharing a display name get distinct sessions"
      (should= {:session-key "gchat-yopp-test-aaqa7rg5uyc"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-test"}
                           [{:id "gchat-yopp-test" :name "gchat-yopp-test"
                             :tags #{:space:BBBB2222}}])))

    (it "a rename onto a name another space holds takes the space id with it"
      (should= {:session-key "gchat-yopp-test-aaqa7rg5uyc" :rename-from "gchat-yopp-lab"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-test"}
                           [{:id "gchat-yopp-lab" :name "gchat-yopp-lab"
                             :tags #{:space:AAQA7rg5Uyc}}
                            {:id "gchat-yopp-test" :name "gchat-yopp-test"
                             :tags #{:space:BBBB2222}}])))

    (it "an unknown space keeps the name it was given"
      (should= {:session-key "gchat-yopp-test"}
               (sut/settle {:space "spaces/AAQA7rg5Uyc" :session-key "gchat-yopp-test"} [])))
    )
  )
