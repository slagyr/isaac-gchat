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

(def human
  (-> (message)
      (assoc :sender {:name "users/118" :displayName "Micah Martin" :domainId "0ivzlyj"})))

(defn resolver
  "Stands in for isaac.google.people/resolve."
  [entry]
  (fn [user _opts] (assoc entry :user user)))

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

  (it "admits a human sender Google reports as users/<id> with no email, when the allow-list names the id"
    (let [human (-> (message) (assoc :sender {:name "users/118285940969606191299" :displayName "Micah Martin" :type "HUMAN" :domainId "0ivzlyj"}))
          cfg'  (assoc cfg :gchat/allow-from ["users/118285940969606191299"])]
      (should= :route (:action (sut/decide cfg' human)))
      ;; no email to render without a lookup, so the display name names who spoke
      (should= "Micah Martin" (:sender (sut/decide cfg' human)))))

  (it "admits any sender in the Workspace when the allow-list names domain:<domainId>"
    (let [human (-> (message) (assoc :sender {:name "users/42" :type "HUMAN" :domainId "0ivzlyj"}))]
      (should= :route (:action (sut/decide (assoc cfg :gchat/allow-from ["domain:0ivzlyj"]) human)))
      (should= :drop (:action (sut/decide (assoc cfg :gchat/allow-from ["domain:other"]) human)))))

  (it "names the sender it dropped so the operator can allow it"
    (let [human (-> (message) (assoc :sender {:name "users/42" :type "HUMAN" :domainId "0ivzlyj"}))
          d     (sut/decide cfg human)]
      (should= :sender (:reason d))
      (should= {:email nil :user "users/42" :domain "0ivzlyj"} (:sender d))))

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

  (context "resolving who spoke"

    (it "admits an email allow-list entry when the sender id resolves to that email"
      (let [cfg' (assoc cfg :gchat/allow-from ["micah@tonotop.com"])
            d    (sut/decide cfg' human {:resolve-person (resolver {:display-name "Micah Martin"
                                                                    :email        "micah@tonotop.com"})})]
        (should= :route (:action d))
        (should= "Micah Martin <micah@tonotop.com>" (:sender d))))

    (it "asks nobody when the message already carries an email"
      (let [asked (atom 0)
            d     (sut/decide cfg (message) {:resolve-person (fn [& _] (swap! asked inc) nil)})]
        (should= :route (:action d))
        (should= 0 @asked)
        (should= "ada@tonotop.com" (:sender d))))

    (it "still admits a sender the id list names when the lookup fails"
      (let [cfg' (assoc cfg :gchat/allow-from ["users/118"])
            d    (sut/decide cfg' human {:resolve-person (fn [user opts]
                                                           {:user         user
                                                            :display-name (:display-name opts)
                                                            :email        nil})})]
        (should= :route (:action d))
        (should= "Micah Martin" (:sender d))))

    (it "falls back to domain:<id> when the lookup fails"
      (let [cfg' (assoc cfg :gchat/allow-from ["domain:0ivzlyj"])
            d    (sut/decide cfg' human {:resolve-person (constantly nil)})]
        (should= :route (:action d))))

    (it "resolves nothing without a resolver"
      (let [cfg' (assoc cfg :gchat/allow-from ["micah@tonotop.com"])
            d    (sut/decide cfg' human)]
        (should= :drop (:action d))
        (should= :sender (:reason d))))

    (it "drops Isaac's own message even when the account email arrives by lookup"
      (let [d (sut/decide cfg human {:resolve-person (resolver {:email account})})]
        (should= :drop (:action d))
        (should= :self (:reason d)))))
  )
