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

(defn- origined [c session-key]
  (comm/on-cycle-start c session-key
                       {:origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"}}))

(describe "gchat comm on-turn-end — what went wrong"

  (it "posts one notice naming the reason and retry time on provider weather"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "weather-1")
        (comm/on-turn-end c "weather-1"
                          {:ended-by     :provider-unavailable
                           :unavailable? true
                           :reason       :wall
                           :retry-at     "2026-04-21T16:40:00Z"})
        (should= 1 (count @posted))
        (should (re-find #"(?i)out of tokens" (:text (first @posted))))
        (should (re-find #"\d{1,2}:\d{2}(am|pm)" (:text (first @posted))))
        (should (re-find #"I will answer then" (:text (first @posted)))))))

  (it "does not repost the park notice for a second walled cycle in the same park"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "weather-2")
        (comm/on-turn-end c "weather-2"
                          {:ended-by :provider-unavailable :unavailable? true
                           :reason   :wall :retry-at "2026-04-21T16:40:00Z"})
        (origined c "weather-2")
        (comm/on-turn-end c "weather-2"
                          {:ended-by :provider-unavailable :unavailable? true
                           :reason   :wall :retry-at "2026-04-21T17:10:00Z"})
        (should= 1 (count @posted)))))

  (it "posts no extra notice when the resumed turn ends with a reply"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "weather-3")
        (comm/on-turn-end c "weather-3"
                          {:ended-by :provider-unavailable :unavailable? true
                           :reason   :wall :retry-at "2026-04-21T16:40:00Z"})
        (origined c "weather-3")
        (comm/on-reply c "weather-3" "Who's there")
        (comm/on-turn-end c "weather-3" {:ended-by :reply})
        (should= 2 (count @posted))
        (should= "Who's there" (:text (second @posted))))))

  (it "reposts on a fresh park once the prior one has cleared"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "weather-4")
        (comm/on-turn-end c "weather-4"
                          {:ended-by :provider-unavailable :unavailable? true
                           :reason   :wall :retry-at "2026-04-21T16:40:00Z"})
        (origined c "weather-4")
        (comm/on-turn-end c "weather-4" {:ended-by :reply})
        (origined c "weather-4")
        (comm/on-turn-end c "weather-4"
                          {:ended-by :provider-unavailable :unavailable? true
                           :reason   :wall :retry-at "2026-04-21T18:00:00Z"})
        (should= 2 (count @posted)))))

  (it "posts a short notice naming the failure class on a hard error, never the raw message"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "boom-1")
        (comm/on-turn-end c "boom-1"
                          {:ended-by :error :error :exception
                           :message  "secret-token-abc123 wire format mismatch"
                           :ex-class "java.lang.Exception"})
        (should= 1 (count @posted))
        (should (re-find #"(?i)provider error" (:text (first @posted))))
        (should-not (re-find #"secret-token" (:text (first @posted))))
        (should-not (re-find #"wire format mismatch" (:text (first @posted)))))))

  (it "classifies a tool exception as a tool failure"
    (let [posted (atom [])
          c      (comm-with slice)]
      (with-redefs [sut/access-token (constantly "at-1")
                    chat-api/create-message! (fn [opts] (swap! posted conj opts) {:name "m1"})]
        (origined c "boom-2")
        (comm/on-turn-end c "boom-2"
                          {:ended-by :error :error :exception
                           :ex-class "isaac.tool.ToolExecutionException"})
        (should (re-find #"(?i)tool failure" (:text (first @posted)))))))

  )

(defn- origined-msg [c session-key message]
  (comm/on-cycle-start c session-key
                       {:n 1 :origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"
                                     :message message}}))

(describe "gchat comm progress reactions (isaac-1bq1)"

  (it "adds the working glyph to the triggering message on the first cycle"
    (let [created (atom nil)
          c       (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-reaction! (fn [opts] (reset! created opts) {:name "spaces/ENG/messages/1/reactions/1"})]
        (origined-msg c "reaction-working-1" "spaces/ENG/messages/1")
        (should= "spaces/ENG/messages/1" (:message @created))
        (should= "👀" (:emoji @created)))))

  (it "does not add a second working reaction on a later cycle of the same turn"
    (let [calls (atom 0)
          c     (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-reaction! (fn [_] (swap! calls inc) {:name "r1"})]
        (comm/on-cycle-start c "reaction-cycle-1"
                             {:n 1 :origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"
                                           :message "spaces/ENG/messages/1"}})
        (comm/on-cycle-start c "reaction-cycle-1"
                             {:n 2 :origin {:kind :gchat :space "spaces/ENG" :thread "spaces/ENG/threads/T1"
                                           :message "spaces/ENG/messages/1"}})
        (should= 1 @calls))))

  (it "removes the working reaction and adds done once the reply posts"
    (let [reactions (atom [])
          c         (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-message!  (fn [_] {:name "m1"})
                    chat-api/create-reaction! (fn [opts]
                                                (swap! reactions conj [:create opts])
                                                {:name "spaces/ENG/messages/1/reactions/1"})
                    chat-api/delete-reaction! (fn [opts] (swap! reactions conj [:delete opts]))]
        (origined-msg c "reaction-done-1" "spaces/ENG/messages/1")
        (comm/on-reply c "reaction-done-1" "All green.")
        (should= [:create :delete :create] (mapv first @reactions))
        (should= "👀" (get-in @reactions [0 1 :emoji]))
        (should= "spaces/ENG/messages/1/reactions/1" (get-in @reactions [1 1 :reaction]))
        (should= "✅" (get-in @reactions [2 1 :emoji])))))

  (it "removes working and adds failed when the turn ends in error"
    (let [reactions (atom [])
          c         (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-message!  (fn [_] {:name "m1"})
                    chat-api/create-reaction! (fn [opts]
                                                (swap! reactions conj [:create opts])
                                                {:name "r1"})
                    chat-api/delete-reaction! (fn [opts] (swap! reactions conj [:delete opts]))]
        (origined-msg c "reaction-failed-1" "spaces/ENG/messages/1")
        (comm/on-turn-end c "reaction-failed-1" {:ended-by :error :ex-class "java.lang.Exception"})
        (should= [:create :delete :create] (mapv first @reactions))
        (should= "⚠️" (get-in @reactions [2 1 :emoji])))))

  (it "keeps the parked glyph through a same-message resume, then done on the reply"
    (let [reactions (atom [])
          c         (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-message!  (fn [_] {:name "m1"})
                    chat-api/create-reaction! (fn [opts]
                                                (swap! reactions conj [:create opts])
                                                {:name "r1"})
                    chat-api/delete-reaction! (fn [opts] (swap! reactions conj [:delete opts]))]
        (origined-msg c "reaction-parked-1" "spaces/ENG/messages/1")
        (comm/on-turn-end c "reaction-parked-1"
                          {:ended-by :provider-unavailable :unavailable? true :reason :wall
                           :retry-at "2026-04-21T16:40:00Z"})
        (should= [:create :delete :create] (mapv first @reactions))
        (should= "⏳" (get-in @reactions [2 1 :emoji]))
        ;; the same message resumes - no flicker back to working
        (origined-msg c "reaction-parked-1" "spaces/ENG/messages/1")
        (should= 3 (count @reactions))
        (comm/on-reply c "reaction-parked-1" "All clear.")
        (should= [:create :delete :create :delete :create] (mapv first @reactions))
        (should= "✅" (get-in @reactions [4 1 :emoji])))))

  (it "makes no reaction calls when gchat/reactions is false"
    (let [calls (atom 0)
          c     (comm-with (assoc slice :gchat/reactions false))]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-message!  (fn [_] {:name "m1"})
                    chat-api/create-reaction! (fn [_] (swap! calls inc) {:name "r1"})
                    chat-api/delete-reaction! (fn [_] (swap! calls inc))]
        (origined-msg c "gchat-reactions-off" "spaces/ENG/messages/1")
        (comm/on-reply c "gchat-reactions-off" "All green.")
        (comm/on-turn-end c "gchat-reactions-off" {:ended-by :reply})
        (should= 0 @calls))))

  (it "honors a configured override, merged over the other defaults"
    (let [created (atom nil)
          c       (comm-with (assoc slice :gchat/reactions {:working "🚀"}))]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-reaction! (fn [opts] (reset! created opts) {:name "r1"})]
        (origined-msg c "reaction-override-1" "spaces/ENG/messages/1")
        (should= "🚀" (:emoji @created)))))

  (it "logs once at debug and never throws when the Chat API refuses a reaction"
    (let [c (comm-with slice)]
      (with-redefs [sut/access-token          (constantly "at-1")
                    chat-api/create-reaction! (fn [_] (throw (ex-info "Chat API reactions.create failed: 403"
                                                                      {:status 403})))]
        (log/capture-logs
          (origined-msg c "reaction-fail-log-1" "spaces/ENG/messages/1")
          (let [entry (first (filter #(= :gchat.reaction/failed (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= :debug (:level entry))
            (should= "spaces/ENG/messages/1" (:message entry))
            (should= 403 (:status entry)))))))

  )

(describe "gchat comm on-turn-end — reactions unrelated to what went wrong"

  (it "logs once and does not retry when the notice itself fails to deliver"
    (let [attempts (atom 0)
          c        (comm-with slice)]
      (log/capture-logs
        (with-redefs [sut/access-token (constantly "at-1")
                      chat-api/create-message! (fn [_] (swap! attempts inc) (throw (Exception. "network down")))]
          (origined c "weather-5")
          (comm/on-turn-end c "weather-5"
                            {:ended-by :provider-unavailable :unavailable? true
                             :reason   :wall :retry-at "2026-04-21T16:40:00Z"}))
        (should= 1 @attempts)
        (should= 1 (count (filter #(= :gchat.notice/failed (:event %)) @log/captured-logs))))))

  )
