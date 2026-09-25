(ns isaac.comm.gchat.handler-spec
  (:require
    [isaac.api :as api]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.guidance :as guidance]
    [isaac.comm.gchat.inbound-attachment :as inbound-attachment]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.comm.gchat.handler :as sut]
    [isaac.comm.gchat.lookup :as lookup]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(def cfg
  {:comms {:gchat {:gchat/account    "yopp@tonotop.com"
                   :gchat/account-id "users/yopp"
                   :gchat/allow-from ["ada@tonotop.com"]
                   :gchat/spaces     {:spaces/ENG {:name "Engineering" :crew "main"}}}}})

(def mention-msg
  {:name         "spaces/ENG/messages/1"
   :sender       {:email "ada@tonotop.com"}
   :thread       {:name "spaces/ENG/threads/T1"}
   :text         "@Isaac can you look at the deploy?"
   :space        {:type "SPACE" :name "spaces/ENG"}
   :annotations  {:mention "users/yopp"}})

(describe "gchat handler"

  (with-stubs)

  (it "dispatches a mention and logs message-routed"
    (let [dispatched (atom nil)]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {})
                    chat-api/get-message! (fn [_] mention-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
          (should= "gchat-spaces-eng" (:session-key @dispatched))
          (should (some #(= :gchat/message-routed (:event %)) @log/captured-logs))))))

  (it "marks the input with the message's thread and carries the standing guidance"
    (let [dispatched (atom nil)]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {})
                    chat-api/get-message! (fn [_] mention-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
          (should= "[thread:T1] ada@tonotop.com: @Isaac can you look at the deploy?"
                   (:input @dispatched))
          (should= guidance/TEXT (:guidance @dispatched))))))

  (it "does not dispatch when the gate drops"
    (let [dispatched (atom false)]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {})
                    chat-api/get-message! (fn [_] (assoc mention-msg :annotations nil :text "lunch"))
                    api/dispatch!         (fn [_] (reset! dispatched true))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/2"}}})
          (should-not @dispatched)))))

  (it "a space nobody listed opens its canonical session, tagged with the space id"
    (let [created (atom nil)
          slice   {:gchat/account    "yopp@tonotop.com"
                   :gchat/account-id "users/yopp"
                   :gchat/allow-from ["ada@tonotop.com"]}]
      (with-redefs [sut/-load-cfg         (fn [] slice)
                    chat-api/get-message! (fn [_] (-> mention-msg
                                                      (assoc :name "spaces/AAQA7rg5Uyc/messages/1")
                                                      (assoc :space {:type "SPACE" :name "spaces/AAQA7rg5Uyc"})))
                    lookup/space-info     (fn [_ _ _] {:displayName "Yopp Test"})
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id opts] (reset! created [id opts]) {:name id})
                    api/dispatch!         (fn [_])]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/AAQA7rg5Uyc/messages/1"}}})
          (should= "gchat-yopp-test" (first @created))
          (should= #{:space:AAQA7rg5Uyc} (:tags (second @created)))))))

  (it "a renamed space renames the session its tag already names"
    (let [dispatched (atom nil)
          renamed    (atom nil)
          slice      {:gchat/account    "yopp@tonotop.com"
                      :gchat/account-id "users/yopp"
                      :gchat/allow-from ["ada@tonotop.com"]}]
      (with-redefs [sut/-load-cfg         (fn [] slice)
                    chat-api/get-message! (fn [_] (-> mention-msg
                                                      (assoc :name "spaces/AAQA7rg5Uyc/messages/2")
                                                      (assoc :space {:type "SPACE" :name "spaces/AAQA7rg5Uyc"})))
                    lookup/space-info     (fn [_ _ _] {:displayName "Yopp Lab"})
                    sut/-sessions         (fn [] [{:id   "gchat-yopp-test"
                                                   :name "gchat-yopp-test"
                                                   :tags #{:space:AAQA7rg5Uyc}}])
                    sut/-rename-session!  (fn [from to] (reset! renamed [from to]))
                    api/get-session       (fn [_] {:name "gchat-yopp-lab"})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/AAQA7rg5Uyc/messages/2"}}})
          (should= ["gchat-yopp-test" "gchat-yopp-lab"] @renamed)
          (should= "gchat-yopp-lab" (:session-key @dispatched))))))

  (it "a rename the store refuses leaves the session where it is"
    (let [dispatched (atom nil)
          slice      {:gchat/account    "yopp@tonotop.com"
                      :gchat/account-id "users/yopp"
                      :gchat/allow-from ["ada@tonotop.com"]}]
      (with-redefs [sut/-load-cfg         (fn [] slice)
                    chat-api/get-message! (fn [_] (-> mention-msg
                                                      (assoc :name "spaces/AAQA7rg5Uyc/messages/2")
                                                      (assoc :space {:type "SPACE" :name "spaces/AAQA7rg5Uyc"})))
                    lookup/space-info     (fn [_ _ _] {:displayName "Yopp Lab"})
                    sut/-sessions         (fn [] [{:id   "gchat-yopp-test"
                                                   :name "gchat-yopp-test"
                                                   :tags #{:space:AAQA7rg5Uyc}}])
                    sut/-rename-session!  (fn [_ _] (throw (ex-info "a turn is in progress" {})))
                    api/get-session       (fn [_] {:name "gchat-yopp-test"})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/AAQA7rg5Uyc/messages/2"}}})
          (should= "gchat-yopp-test" (:session-key @dispatched))
          (should (some #(= :gchat/session-rename-failed (:event %)) @log/captured-logs))))))

  (it "dispatches on the operator's defaults.crew when the space and the comm name none (isaac-rfmh)"
    (let [dispatched (atom nil)
          host       {:comms    {:gchat {:gchat/account    "yopp@tonotop.com"
                                         :gchat/account-id "users/yopp"
                                         :gchat/allow-from ["ada@tonotop.com"]}}
                      :defaults {:frequencies {:crew :yopp}}
                      :google   {:tonotop {:topic "projects/marigold/topics/isaac"}}}]
      (with-redefs [sut/-load-cfg         (fn [] (get-in host [:comms :gchat]))
                    sut/full-config       (fn [] host)
                    chat-api/get-message! (fn [_] mention-msg)
                    lookup/space-info     (fn [_ _ _] {})
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
          (should= "yopp" (:crew @dispatched))))))

  (it "the session name says which organization's space it is, on a host with one"
    (let [created (atom nil)
          one-org {:comms  {:gchat {:gchat/account    "yopp@tonotop.com"
                                    :gchat/account-id "users/yopp"
                                    :gchat/allow-from ["ada@tonotop.com"]}}
                   :google {:tonotop {:topic "projects/marigold/topics/isaac"}}}]
      (with-redefs [sut/-load-cfg         (fn [] (get-in one-org [:comms :gchat]))
                    sut/full-config       (fn [] one-org)
                    chat-api/get-message! (fn [_] (-> mention-msg
                                                      (assoc :name "spaces/AAQA7rg5Uyc/messages/1")
                                                      (assoc :space {:type "SPACE" :name "spaces/AAQA7rg5Uyc"})))
                    lookup/space-info     (fn [_ _ _] {:displayName "Yopp Test"})
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id opts] (reset! created [id opts]) {:name id})
                    api/dispatch!         (fn [_])]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/AAQA7rg5Uyc/messages/1"}}})
          (should= "gchat-tonotop-yopp-test" (first @created))))))

  (it "a membership event is noted and nothing else"
    (let [dispatched (atom false)]
      (with-redefs [api/dispatch! (fn [_] (reset! dispatched true))]
        (log/capture-logs
          (sut/acknowledge-event {:type "google.workspace.chat.membership.v1.deleted"
                                  :data {:space "spaces/AAQA7rg5Uyc"}})
          (should-not @dispatched)
          (should (some #(= :gchat/event-noted (:event %)) @log/captured-logs))))))

  (it "a DM the account is only invited to is dispatched with :invited? on the origin (isaac-qry7)"
    (let [dispatched (atom nil)
          dm-msg     (-> mention-msg
                         (assoc :name "spaces/INV1/messages/1")
                         (assoc :annotations nil)
                         (assoc :space {:type "DIRECT_MESSAGE" :name "spaces/INV1"}))]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {:spaceType "DIRECT_MESSAGE" :invited? true})
                    chat-api/get-message! (fn [_] dm-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/INV1/messages/1"}}})
          (should= true (get-in @dispatched [:origin :invited?]))))))

  (it "the dispatched origin carries the triggering message's own resource name (isaac-1bq1)"
    (let [dispatched (atom nil)]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {})
                    chat-api/get-message! (fn [_] mention-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
          (should= "spaces/ENG/messages/1" (get-in @dispatched [:origin :message]))))))

  (it "a routed DM the account has joined carries no :invited? key"
    (let [dispatched (atom nil)
          dm-msg     (-> mention-msg
                         (assoc :name "spaces/DM2/messages/1")
                         (assoc :annotations nil)
                         (assoc :space {:type "DIRECT_MESSAGE" :name "spaces/DM2"}))]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    lookup/space-info     (fn [_ _ _] {:spaceType "DIRECT_MESSAGE"})
                    chat-api/get-message! (fn [_] dm-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/DM2/messages/1"}}})
          (should-not (contains? (:origin @dispatched) :invited?))))))

  (it "logs fetch-failed when Chat API throws"
    (with-redefs [chat-api/get-message! (fn [_] (throw (ex-info "boom" {})))]
      (log/capture-logs
        (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
        (should (some #(and (= :error (:level %))
                            (= :gchat/fetch-failed (:event %)))
                      @log/captured-logs)))))
  )

(describe "gchat inbound attachments (isaac-e2zb)"
  (it "sanitizes a filename, saves the download, and frames it for the session"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [chat-api/download-attachment! (constantly "hello")]
          (should= ["[attachment: note.txt (text/plain, 5) at attachments/1/note.txt]"]
                   (inbound-attachment/save-all! "/work" "1"
                                                 [{:contentName "../note.txt" :contentType "text/plain"
                                                   :attachmentDataRef {:resourceName "a"}}]))
          (should= "hello" (fs/slurp fs* "/work/attachments/1/note.txt"))))))

  (it "does not save an attachment above the cap"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [inbound-attachment/MAX-BYTES 4
                      chat-api/download-attachment! (constantly "hello")]
          (should= ["[attachment: report.pdf (too large, not saved)]"]
                   (inbound-attachment/save-all! "/work" "1"
                                                 [{:contentName "report.pdf"
                                                   :attachmentDataRef {:resourceName "a"}}]))
          (should-not (fs/exists? fs* "/work/attachments/1/report.pdf"))))))

  (it "logs once and frames a failed download while continuing"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [chat-api/download-attachment! (fn [_] (throw (ex-info "gone" {})))]
          (log/capture-logs
            (should= ["[attachment: report.pdf (download failed)]"]
                     (inbound-attachment/save-all! "/work" "1"
                                                   [{:contentName "report.pdf"
                                                     :attachmentDataRef {:resourceName "a"}}]))
            (should= 1 (count (filter #(= :gchat.attachment/download-failed (:event %))
                                       @log/captured-logs)))))))))
