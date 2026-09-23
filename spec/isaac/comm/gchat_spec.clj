(ns isaac.comm.gchat-spec
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as delivery-queue]
    [isaac.comm.gchat :as sut]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.transcript :as transcript]
    [isaac.comm.protocol :as comm]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- comm-with [slice]
  (let [c (sut/make {:name :gchat :root "/tmp"})]
    (reset! (.-cfg c) slice)
    c))

(def slice
  {:gchat/account     "yopp@tonotop.com"
   :gchat/allow-from  ["ada@tonotop.com"]
   :gchat/spaces      {:spaces/ENG {:name "engineering" :crew "main"}}
   :gchat/message-cap 4096})

(describe "gchat comm send!"

  (it "posts to a configured space name"
    (let [captured (atom nil)
          c        (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (reset! captured opts) {:name "m1"})]
        (should= {:ok true} (comm/send! c {:gchat/space "engineering" :content "Red alert!"}))
        (should= "spaces/ENG" (:space @captured))
        (should= "Red alert!" (:text @captured))
        (should= "at-1" (:token @captured)))))

  (it "posts to a resource name with an optional thread"
    (let [captured (atom nil)
          c        (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (reset! captured opts) {:name "m1"})]
        (comm/send! c {:gchat/space "spaces/ENG"
                       :gchat/thread "spaces/ENG/threads/T1"
                       :content "All clear."})
        (should= "spaces/ENG" (:space @captured))
        (should= "spaces/ENG/threads/T1" (:thread @captured))
        (should= "All clear." (:text @captured)))))

  (it "creates a DM space when findDirectMessage is 404"
    (let [calls (atom [])
          c     (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/find-direct-message! (fn [email token]
                                                    (swap! calls conj [:find email token])
                                                    nil)
                    chat-api/setup-direct-message! (fn [email token]
                                                     (swap! calls conj [:setup email token])
                                                     {:name "spaces/DMBOB"})
                    chat-api/create-message! (fn [opts]
                                               (swap! calls conj [:create opts])
                                               {:name "m1"})]
        (should= {:ok true} (comm/send! c {:gchat/to "bob@tonotop.com" :content "Standup in 5."}))
        (should= [:find "bob@tonotop.com" "at-1"] (first @calls))
        (should= [:setup "bob@tonotop.com" "at-1"] (second @calls))
        (should= "spaces/DMBOB" (get-in (nth @calls 2) [1 :space]))
        (should= "Standup in 5." (get-in (nth @calls 2) [1 :text])))))

  (it "replies in the originating thread on on-reply"
    (let [captured (atom nil)
          c        (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (reset! captured opts) {:name "m1"})]
        (comm/on-cycle-start c "gchat-spaces-ENG"
                             {:origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"}})
        (comm/on-reply c "gchat-spaces-ENG" "All green.")
        (should= "spaces/ENG" (:space @captured))
        (should= "spaces/ENG/threads/T1" (:thread @captured))
        (should= "All green." (:text @captured)))))

  (it "on-reply marks the reply with the thread it went to, in the space transcript"
    (nexus/-with-nested-nexus {:fs (fs/mem-fs) :root "/test/gchat-reply"}
      (let [c (comm-with slice)]
        (with-redefs [sut/access-token (constantly "at-1")
                      chat-api/create-message! (fn [_] {:name "m1"})]
          (comm/on-cycle-start c "gchat-spaces-ENG"
                               {:origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"}})
          (comm/on-reply c "gchat-spaces-ENG" "All green.")
          (let [entry (last (transcript/recent "spaces/ENG"))]
            (should= "spaces/ENG/threads/T1" (:thread entry))
            (should= "All green." (:text entry))
            (should (:self? entry)))))))

  (context "a DM the account is only invited to (isaac-qry7)"

    (it "diverts the reply to the attention comm instead of posting, prefixed with the DM and sender"
      (let [created (atom nil)
            posted  (atom false)
            c       (comm-with slice)]
        (with-redefs [sut/-full-cfg (constantly {:attention {:notify {:comm "logbook" :target "ops-room"}}})
                      chat-api/create-message! (fn [_] (reset! posted true) {:name "m1"})
                      delivery-queue/enqueue! (fn [record] (reset! created record) record)]
          (comm/on-cycle-start c "gchat-tonotop-dm-cordelia"
                               {:origin {:kind :gchat :space "spaces/INV1" :thread "spaces/INV1/threads/T1"
                                        :display-name "Cordelia" :invited? true}})
          (log/capture-logs
            (comm/on-reply c "gchat-tonotop-dm-cordelia" "Standing by."))
          (should-not @posted)
          (should= :logbook (:comm @created))
          (should= "ops-room" (:target @created))
          (should (str/includes? (:content @created) "spaces/INV1"))
          (should (str/includes? (:content @created) "Cordelia"))
          (should (str/includes? (:content @created) "Standing by.")))))

    (it "with no attention comm configured, logs once and drops the reply"
      (let [posted (atom false)
            c      (comm-with slice)]
        (with-redefs [sut/-full-cfg (constantly {})
                      chat-api/create-message! (fn [_] (reset! posted true) {:name "m1"})
                      delivery-queue/enqueue! (fn [_] (throw (ex-info "must not enqueue" {})))]
          (comm/on-cycle-start c "gchat-tonotop-dm-cordelia"
                               {:origin {:kind :gchat :space "spaces/INV1" :thread "spaces/INV1/threads/T1"
                                        :invited? true}})
          (log/capture-logs
            (comm/on-reply c "gchat-tonotop-dm-cordelia" "Standing by.")
            (should-not @posted)
            (should (some #(= :gchat.dm/reply-diverted (:event %)) @log/captured-logs)))))))

  (context "a reply create-message! fails"

    (it "logs :gchat/delivery-failed with space, thread, status and reason - not a bare create-failed error"
      (let [c (comm-with slice)]
        (with-redefs [sut/access-token (constantly "at-1")
                      chat-api/create-message! (fn [_]
                                                 (throw (ex-info "Chat API create failed: 403"
                                                                 {:status 403
                                                                  :body   {:error {:message "PERMISSION_DENIED"}}})))]
          (comm/on-cycle-start c "gchat-spaces-ENG"
                               {:origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"}})
          (log/capture-logs
            (comm/on-reply c "gchat-spaces-ENG" "All green.")
            (let [entry (first (filter #(= :gchat/delivery-failed (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= :error (:level entry))
              (should= "spaces/ENG" (:space entry))
              (should= "spaces/ENG/threads/T1" (:thread entry))
              (should= 403 (:status entry))
              (should= "PERMISSION_DENIED" (:reason entry)))))))

    (it "reflects the delivery failure at on-turn-end, tagged :delivery-failure, then clears it"
      (let [c (comm-with slice)]
        (with-redefs [sut/access-token (constantly "at-1")
                      chat-api/create-message! (fn [_]
                                                 (throw (ex-info "Chat API create failed: 403"
                                                                 {:status 403 :body {:error {:message "nope"}}})))]
          (comm/on-cycle-start c "gchat-spaces-ENG"
                               {:origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"}})
          (log/capture-logs
            (comm/on-reply c "gchat-spaces-ENG" "All green."))
          (log/capture-logs
            (comm/on-turn-end c "gchat-spaces-ENG" {})
            (let [entry (first (filter #(= :gchat/turn-notice (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= :delivery-failure (:class entry))))
          (log/capture-logs
            (comm/on-turn-end c "gchat-spaces-ENG" {})
            (should-not (some #(= :gchat/turn-notice (:event %)) @log/captured-logs)))))))

  )
