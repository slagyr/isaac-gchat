(ns isaac.comm.gchat.chat-api-spec
  (:require
    [isaac.comm.gchat.chat-api :as sut]
    [speclj.core :refer :all]))

(describe "gchat chat-api outbound"

  (it "POSTs a thread reply with messageReplyOption and bearer"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:name "spaces/ENG/messages/9"}})]
        (sut/create-message! {:space  "spaces/ENG"
                              :thread "spaces/ENG/threads/T1"
                              :text   "All green."
                              :token  "at-1"})
        (should= "POST" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces/ENG/messages" (:url @captured))
        (should= "Bearer at-1" (get-in @captured [:headers "Authorization"]))
        (should= "REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD"
                 (get-in @captured [:query :messageReplyOption]))
        (should= "spaces/ENG/threads/T1" (get-in @captured [:body :thread :name]))
        (should= "All green." (get-in @captured [:body :text])))))

  (it "finds a DM space by user email"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:name "spaces/DMBOB"}})]
        (should= "spaces/DMBOB" (:name (sut/find-direct-message! "bob@tonotop.com" "at-1")))
        (should= "GET" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces:findDirectMessage" (:url @captured))
        (should= "users/bob@tonotop.com" (get-in @captured [:query :name])))))

  (it "returns nil when findDirectMessage is 404"
    (with-redefs [sut/-http! (fn [_] {:status 404 :body {}})]
      (should-be-nil (sut/find-direct-message! "bob@tonotop.com" "at-1"))))

  (it "creates a DM space via spaces:setup"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:name "spaces/DMBOB"}})]
        (should= "spaces/DMBOB" (:name (sut/setup-direct-message! "bob@tonotop.com" "at-1")))
        (should= "POST" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces:setup" (:url @captured))
        (should= "DIRECT_MESSAGE" (get-in @captured [:body :space :spaceType]))
        (should= "users/bob@tonotop.com"
                 (get-in @captured [:body :memberships 0 :member :name])))))

  )
