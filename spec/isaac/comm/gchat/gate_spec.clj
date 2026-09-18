(ns isaac.comm.gchat.gate-spec
  (:require
    [isaac.comm.gchat.gate :as sut]
    [speclj.core :refer :all]))

(def account "yopp@tonotop.com")

(def cfg
  {:gchat/account    account
   :gchat/allow-from ["ada@tonotop.com"]
   :gchat/spaces     {:spaces/ENG {:name "Engineering" :crew "main"}}})

(defn message
  [& {:keys [email text mention space-type name]
      :or   {email      "ada@tonotop.com"
             text       "@Isaac can you look at the deploy?"
             mention    "users/yopp"
             space-type "SPACE"
             name       "spaces/ENG/messages/1"}}]
  (cond-> {:name         name
           :sender       {:email email}
           :thread       {:name "spaces/ENG/threads/T1"}
           :text         text
           :space        {:type space-type :name (second (re-find #"(spaces/[^/]+)" name))}}
    mention (assoc :annotations {:mention mention})))

(describe "gchat inbound gate"

  (it "routes a mention in a configured space"
    (let [result (sut/decide cfg (message))]
      (should= :route (:action result))
      (should= "spaces/ENG" (:space result))
      (should= "spaces/ENG/threads/T1" (:thread result))
      (should= "gchat-spaces-ENG" (:session-key result))))

  (it "drops a space message with no mention under the default policy"
    (let [result (sut/decide cfg (message :mention nil :text "lunch anyone?"))]
      (should= :drop (:action result))
      (should= :no-mention (:reason result))))

  (it "routes a DM without a mention"
    (let [result (sut/decide cfg (message :name "spaces/DM1/messages/1"
                                          :mention nil
                                          :text "are you there?"
                                          :space-type "DIRECT_MESSAGE"))]
      (should= :route (:action result))
      (should= "gchat-spaces-DM1" (:session-key result))))

  (it "drops Isaac's own message"
    (let [result (sut/decide cfg (message :email account :text "On it."))]
      (should= :drop (:action result))
      (should= :self (:reason result))))

  (it "drops an unconfigured space"
    (let [result (sut/decide cfg (message :name "spaces/RANDOM/messages/1"))]
      (should= :drop (:action result))
      (should= :space (:reason result))))

  (it "drops an unknown sender"
    (let [result (sut/decide cfg (message :email "mallory@example.com"))]
      (should= :drop (:action result))
      (should= :sender (:reason result))))

  (it "routes a space message with no mention when respond is all"
    (let [cfg*   (assoc-in cfg [:gchat/spaces :spaces/ENG :respond] "all")
          result (sut/decide cfg* (message :mention nil :text "lunch anyone?"))]
      (should= :route (:action result))))

  (it "drops when respond is never"
    (let [cfg*   (assoc-in cfg [:gchat/spaces :spaces/ENG :respond] :never)
          result (sut/decide cfg* (message))]
      (should= :drop (:action result))
      (should= :policy (:reason result))))

  (it "fail-closes when allow-from is missing"
    (let [result (sut/decide (dissoc cfg :gchat/allow-from) (message))]
      (should= :drop (:action result))
      (should= :sender (:reason result))))

  (it "names the session by replacing slashes in the space name"
    (should= "gchat-spaces-ENG" (sut/session-name "spaces/ENG")))
  )
