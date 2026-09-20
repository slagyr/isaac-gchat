(ns isaac.comm.gchat.tools-spec
  (:require
    [isaac.comm.gchat :as gchat]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.tools :as sut]
    [isaac.google.people :as people]
    [speclj.core :refer :all]))

(defn with-token [f]
  (with-redefs [gchat/access-token (constantly "at-1")]
    (f)))

(describe "chat tools (isaac-jqk2)"

  (around [it] (with-token it))
  (before (people/reset-memo!))

  (context "gchat__spaces"

    (it "names the spaces the account is in"
      (with-redefs [chat-api/list-spaces!
                    (fn [_ & _] {:spaces [{:name "spaces/ENG" :displayName "Engineering" :spaceType "SPACE"}
                                          {:name "spaces/DM1" :spaceType "DIRECT_MESSAGE"}]})]
        (should= [{:space "spaces/ENG" :display-name "Engineering" :type "SPACE"}
                  {:space "spaces/DM1" :display-name nil :type "DIRECT_MESSAGE"}]
                 (:spaces (:result (sut/spaces {}))))))

    (it "reports a Chat failure as a tool error"
      (with-redefs [chat-api/list-spaces! (fn [_ & _] (throw (ex-info "403" {})))]
        (should (:isError (sut/spaces {}))))))

  (context "gchat__history"

    (it "insists on a space"
      (should (:isError (sut/history {}))))

    (it "reads a space oldest first, naming who spoke"
      (with-redefs [chat-api/list-messages!
                    (fn [_ _ & _] {:messages [{:text "second" :createTime "2026-09-20T02:00:00Z"
                                               :thread {:name "spaces/ENG/threads/T1"}
                                               :sender {:name "users/118" :displayName "Micah Martin"}}
                                              {:text "first" :createTime "2026-09-20T01:00:00Z"
                                               :thread {:name "spaces/ENG/threads/T1"}
                                               :sender {:name "users/118" :displayName "Micah Martin"}}]})
                    people/fetch! (fn [_] {:names [{:displayName "Micah Martin"}]
                                           :emailAddresses [{:value "micah@tonotop.com"}]})]
        (let [result (:result (sut/history {:space "spaces/ENG"}))]
          (should= ["first" "second"] (mapv :text (:messages result)))
          (should= "Micah Martin <micah@tonotop.com>" (:sender (first (:messages result))))
          (should= false (:more? result)))))

    (it "passes a thread and a since through to Chat's filter"
      (let [seen (atom nil)]
        (with-redefs [chat-api/list-messages! (fn [_ space opts] (reset! seen [space opts]) {:messages []})]
          (sut/history {:space "spaces/ENG" :thread "spaces/ENG/threads/T1" :since "2026-09-20T00:00:00Z" :limit 10})
          (should= "spaces/ENG" (first @seen))
          (should= "spaces/ENG/threads/T1" (:thread (second @seen)))
          (should= 10 (:page-size (second @seen)))
          (should-contain "2026-09-20T00:00:00Z" (:filter* (second @seen))))))

    (it "says when there is more to read"
      (with-redefs [chat-api/list-messages! (fn [_ _ & _] {:messages [] :nextPageToken "abc"})]
        (should= true (:more? (:result (sut/history {:space "spaces/ENG"})))))))

  (context "gchat__send"

    (it "insists on a space and text"
      (should (:isError (sut/send-message {:text "hi"})))
      (should (:isError (sut/send-message {:space "spaces/ENG"}))))

    (it "posts and answers with the message it created"
      (let [sent (atom nil)]
        (with-redefs [chat-api/create-message! (fn [req] (reset! sent req)
                                                 {:name "spaces/ENG/messages/9"
                                                  :thread {:name "spaces/ENG/threads/T1"}})]
          (should= {:message "spaces/ENG/messages/9" :thread "spaces/ENG/threads/T1"}
                   (:result (sut/send-message {:space "spaces/ENG" :text "on it"})))
          (should= "on it" (:text @sent))
          (should= "at-1" (:token @sent)))))

    (it "reports a Chat failure as a tool error"
      (with-redefs [chat-api/create-message! (fn [_] (throw (ex-info "boom" {})))]
        (should (:isError (sut/send-message {:space "spaces/ENG" :text "hi"}))))))

  (it "describes each tool for the model, and says which one is loud"
    (should-contain "Side-effecting" (:description (sut/send-tool-factory {})))
    (should= ["space"] (:required (:parameters (sut/history-tool-factory {}))))
    (should= {} (:properties (:parameters (sut/spaces-tool-factory {})))))
  )
