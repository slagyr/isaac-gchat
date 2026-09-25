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

  (it "asks Chat what one space is"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:name "spaces/AAQA7rg5Uyc" :displayName "Yopp Test"}})]
        (should= "Yopp Test" (:displayName (sut/get-space! "at-1" "spaces/AAQA7rg5Uyc")))
        (should= "GET" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces/AAQA7rg5Uyc" (:url @captured))
        (should= "Bearer at-1" (get-in @captured [:headers "Authorization"])))))

  (it "throws when Chat refuses a spaces.get"
    (with-redefs [sut/-http! (fn [_] {:status 403 :body {}})]
      (should-throw (sut/get-space! "at-1" "spaces/AAQA7rg5Uyc"))))

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

  (it "POSTs an emoji reaction on the triggering message"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:name "spaces/ENG/messages/1/reactions/1"}})]
        (should= "spaces/ENG/messages/1/reactions/1"
                 (:name (sut/create-reaction! {:message "spaces/ENG/messages/1" :emoji "👀" :token "at-1"})))
        (should= "POST" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces/ENG/messages/1/reactions" (:url @captured))
        (should= "Bearer at-1" (get-in @captured [:headers "Authorization"]))
        (should= "👀" (get-in @captured [:body :emoji :unicode])))))

  (it "throws when Chat refuses a reactions.create"
    (with-redefs [sut/-http! (fn [_] {:status 403 :body {}})]
      (should-throw (sut/create-reaction! {:message "spaces/ENG/messages/1" :emoji "👀" :token "at-1"}))))

  (it "DELETEs a reaction by its own resource name"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {}})]
        (sut/delete-reaction! {:reaction "spaces/ENG/messages/1/reactions/1" :token "at-1"})
        (should= "DELETE" (:method @captured))
        (should= "https://chat.googleapis.com/v1/spaces/ENG/messages/1/reactions/1" (:url @captured))
        (should= "Bearer at-1" (get-in @captured [:headers "Authorization"])))))

  (it "throws when Chat refuses a reactions.delete"
    (with-redefs [sut/-http! (fn [_] {:status 403 :body {}})]
      (should-throw (sut/delete-reaction! {:reaction "spaces/ENG/messages/1/reactions/1" :token "at-1"}))))

  (it "uploads an attachment as a multipart request and answers its data ref (isaac-vlxz)"
    (let [captured (atom nil)]
      (with-redefs [sut/-http!
                    (fn [req]
                      (reset! captured req)
                      {:status 200 :body {:attachmentDataRef {:resourceName "ref-1"}}})]
        (should= {:resourceName "ref-1"}
                 (sut/upload-attachment! {:space        "spaces/ENG"
                                          :filename     "report.pdf"
                                          :content-type "application/pdf"
                                          :bytes        (.getBytes "%PDF-1.4 stub" "UTF-8")
                                          :token        "at-1"}))
        (should= "POST" (:method @captured))
        (should= "https://chat.googleapis.com/upload/v1/spaces/ENG/attachments:upload" (:url @captured))
        (should= "multipart" (get-in @captured [:query :uploadType]))
        (should= "Bearer at-1" (get-in @captured [:headers "Authorization"]))
        (let [content-type (get-in @captured [:headers "Content-Type"])
              boundary     (second (re-find #"boundary=(\S+)" content-type))
              body         (String. ^bytes (:raw-body @captured) "UTF-8")]
          (should (.startsWith ^String content-type "multipart/related"))
          (should-contain (str "--" boundary) body)
          (should-contain "{\"filename\":\"report.pdf\"}" body)
          (should-contain "Content-Type: application/pdf" body)
          (should-contain "%PDF-1.4 stub" body)
          (should-contain (str "--" boundary "--") body)))))

  (it "throws when Chat refuses an attachment upload"
    (with-redefs [sut/-http! (fn [_] {:status 413 :body {}})]
      (should-throw (sut/upload-attachment! {:space "spaces/ENG" :filename "a.txt" :content-type "text/plain"
                                             :bytes (byte-array 0) :token "at-1"}))))

  (it "posts a message carrying attachment refs alongside its text"
    (let [captured (atom nil)]
      (with-redefs [sut/-http! (fn [req] (reset! captured req) {:status 200 :body {:name "m1"}})]
        (sut/create-message! {:space "spaces/ENG" :text "Here." :token "at-1"
                              :attachments [{:resourceName "ref-1"}]})
        (should= "Here." (get-in @captured [:body :text]))
        (should= [{:attachmentDataRef {:resourceName "ref-1"}}] (get-in @captured [:body :attachment])))))

  (it "posts no attachment key when there are no attachments"
    (let [captured (atom nil)]
      (with-redefs [sut/-http! (fn [req] (reset! captured req) {:status 200 :body {:name "m1"}})]
        (sut/create-message! {:space "spaces/ENG" :text "Here." :token "at-1"})
        (should-not-contain :attachment (:body @captured)))))

  )
