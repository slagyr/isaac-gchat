(ns isaac.comm.gchat.handler-spec
  (:require
    [isaac.api :as api]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.handler :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(def cfg
  {:comms {:gchat {:gchat/account    "yopp@tonotop.com"
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
                    chat-api/get-message! (fn [_] mention-msg)
                    api/get-session       (fn [_] nil)
                    api/create-session!   (fn [id _] {:name id})
                    api/dispatch!         (fn [req] (reset! dispatched req))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
          (should= "gchat-spaces-ENG" (:session-key @dispatched))
          (should (some #(= :gchat/message-routed (:event %)) @log/captured-logs))))))

  (it "does not dispatch when the gate drops"
    (let [dispatched (atom false)]
      (with-redefs [sut/-load-cfg         (fn [] (get-in cfg [:comms :gchat]))
                    chat-api/get-message! (fn [_] (assoc mention-msg :annotations nil :text "lunch"))
                    api/dispatch!         (fn [_] (reset! dispatched true))]
        (log/capture-logs
          (sut/handle-event {:data {:message {:name "spaces/ENG/messages/2"}}})
          (should-not @dispatched)))))

  (it "logs fetch-failed when Chat API throws"
    (with-redefs [chat-api/get-message! (fn [_] (throw (ex-info "boom" {})))]
      (log/capture-logs
        (sut/handle-event {:data {:message {:name "spaces/ENG/messages/1"}}})
        (should (some #(and (= :error (:level %))
                            (= :gchat/fetch-failed (:event %)))
                      @log/captured-logs)))))
  )
