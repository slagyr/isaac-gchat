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

  (it "routes to the operator's default crew when neither the space nor the comm names one (isaac-rfmh)"
    (let [bare (assoc cfg :gchat/spaces {:spaces/ENG {:name "Engineering"}})]
      (should= "yopp" (:crew (sut/decide bare (message) {:default-crew :yopp})))
      (should= "yopp" (:crew (sut/decide bare (message) {:default-crew "yopp"})))
      (should= "ops"  (:crew (sut/decide (assoc bare :crew "ops") (message) {:default-crew :yopp})))
      (should= "main" (:crew (sut/decide cfg (message) {:default-crew :yopp})))
      (should-be-nil (:crew (sut/decide bare (message))))))

  (it "routes a mention in a configured space"
    (let [result (sut/decide cfg (message))]
      (should= :route (:action result))
      (should= "spaces/ENG" (:space result))
      (should= "spaces/ENG/threads/T1" (:thread result))
      (should= "gchat-spaces-eng" (:session-key result))
      (should= #{:space:ENG} (:tags result))))

  (it "hears a space message with no mention but does not answer it"
    (let [result (sut/decide cfg (message :mention nil :text "lunch anyone?"))]
      (should= :log (:action result))
      (should= :logged (:reason result))
      (should= "spaces/ENG" (:space result))
      (should= "lunch anyone?" (:text result))
      (should= "ada@tonotop.com" (:sender result))))

  (it "routes a DM without a mention"
    (let [result (sut/decide cfg (message :name "spaces/DM1/messages/1"
                                          :mention nil
                                          :text "are you there?"
                                          :space-type "DIRECT_MESSAGE"))]
      (should= :route (:action result))
      (should= "gchat-spaces-dm1" (:session-key result))))

  (it "drops Isaac's own message"
    (let [result (sut/decide cfg (message :email account :text "On it."))]
      (should= :drop (:action result))
      (should= :self (:reason result))))

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

  (context "a space nobody listed (isaac-ihuc)"

    (it "routes it — belonging to the space is the grant"
      (let [result (sut/decide cfg (message :name "spaces/AAQA7rg5Uyc/messages/1")
                               {:space-info {:displayName "Yopp Test" :spaceType "SPACE"}})]
        (should= :route (:action result))
        (should= "gchat-yopp-test" (:session-key result))
        (should= #{:space:AAQA7rg5Uyc} (:tags result))))

    (it "names a DM for the member who spoke"
      (let [result (sut/decide cfg (-> (message :name "spaces/DM1/messages/1" :mention nil)
                                       (assoc :sender {:email "ada@tonotop.com"
                                                       :displayName "Micah Martin"}))
                               {:space-info {:spaceType "DIRECT_MESSAGE"}})]
        (should= :route (:action result))
        (should= "gchat-dm-micah-martin" (:session-key result))))

    (it "always carries the organization, so a session says whose space it is"
      (let [result (sut/decide cfg (message :name "spaces/AAQA7rg5Uyc/messages/1")
                               {:tenant :tonotop
                                :space-info {:displayName "Yopp Test"}})]
        (should= "gchat-tonotop-yopp-test" (:session-key result))))

    (it "an entry that pins a session keeps it, and does not claim the space tag"
      (let [cfg'   (assoc-in cfg [:gchat/spaces :spaces/ENG :session] "deploy-desk")
            result (sut/decide cfg' (message))]
        (should= "deploy-desk" (:session-key result))
        (should-be-nil (:tags result))))

    (it "an unknown sender is still refused, wherever they speak"
      (let [stranger (-> (message :name "spaces/RANDOM/messages/1")
                         (assoc :sender {:name "users/999" :domainId "0xother"}))]
        (should= :sender (:reason (sut/decide cfg stranger))))))

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

  (context "allow-from patterns (isaac-dymn)"

    (it "admits every address in a domain the list names as *@domain"
      (let [cfg' (assoc cfg :gchat/allow-from ["*@tonotop.com"])]
        (should= :route (:action (sut/decide cfg' (message :email "ada@tonotop.com"))))
        (should= :route (:action (sut/decide cfg' (message :email "grace@tonotop.com"))))))

    (it "matches a pattern and an address without regard to case"
      (let [cfg' (assoc cfg :gchat/allow-from ["*@Tonotop.com" "Ada@tonotop.com"])]
        (should= :route (:action (sut/decide cfg' (message :email "GRACE@TONOTOP.COM"))))
        (should= :route (:action (sut/decide cfg' (message :email "ada@TONOTOP.com"))))))

    (it "drops an address outside the pattern's domain"
      (let [cfg'   (assoc cfg :gchat/allow-from ["*@tonotop.com"])
            result (sut/decide cfg' (message :email "mallory@example.com"))]
        (should= :drop (:action result))
        (should= :sender (:reason result))))

    (it "does not let a domain pattern match an address that merely ends with it"
      (let [cfg' (assoc cfg :gchat/allow-from ["*@tonotop.com"])]
        (should= :drop (:action (sut/decide cfg' (message :email "eve@nottonotop.com"))))
        (should= :drop (:action (sut/decide cfg' (message :email "eve@tonotop.com.evil.net"))))))

    (it "admits a Chat sender whose id resolves into the pattern's domain"
      (let [cfg' (assoc cfg :gchat/allow-from ["*@tonotop.com"])
            d    (sut/decide cfg' human {:resolve-person (resolver {:display-name "Micah Martin"
                                                                    :email        "micah@tonotop.com"})})]
        (should= :route (:action d))
        (should= "Micah Martin <micah@tonotop.com>" (:sender d))))

    (it "still fails closed on an empty allow-from"
      (let [cfg' (assoc cfg :gchat/allow-from [])]
        (should= :drop (:action (sut/decide cfg' (message))))))

    (it "matches an entry that is a bare pattern against nothing when the sender has no email"
      (let [cfg' (assoc cfg :gchat/allow-from ["*@tonotop.com"])
            d    (sut/decide cfg' human {:resolve-person (constantly nil)})]
        (should= :drop (:action d))
        (should= :sender (:reason d)))))
  )
