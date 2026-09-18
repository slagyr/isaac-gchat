(ns isaac.comm.gchat-spec
  (:require
    [isaac.comm.gchat :as sut]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.protocol :as comm]
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

  )
