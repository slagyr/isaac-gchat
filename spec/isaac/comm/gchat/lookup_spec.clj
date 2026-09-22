(ns isaac.comm.gchat.lookup-spec
  (:require
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.lookup :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(defn- asking
  "Answers spaces.get with `answers` and counts how often it was asked."
  [asked answers]
  (fn [_token space]
    (swap! asked inc)
    (or (get answers space)
        (throw (ex-info "no such space" {:space space})))))

(describe "what Chat says a space is"

  (before (sut/forget!))

  (it "asks Chat once for a space and remembers the answer"
    (let [asked (atom 0)]
      (with-redefs [sut/-token        (fn [_] "at-1")
                    chat-api/get-space! (asking asked {"spaces/AAQA7rg5Uyc" {:displayName "Yopp Test"}})]
        (should= "Yopp Test" (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" nil)))
        (should= "Yopp Test" (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" nil)))
        (should= 1 @asked))))

  (it "each organization asks for itself — two accounts, two answers"
    (let [asked (atom 0)]
      (with-redefs [sut/-token        (fn [_] "at-1")
                    chat-api/get-space! (asking asked {"spaces/SHARED" {:displayName "Shared"}})]
        (sut/space-info :tonotop "spaces/SHARED" nil)
        (sut/space-info :acme "spaces/SHARED" nil)
        (should= 2 @asked))))

  (it "a display name the event carries replaces what was remembered — that is a rename"
    (let [asked (atom 0)]
      (with-redefs [sut/-token        (fn [_] "at-1")
                    chat-api/get-space! (asking asked {"spaces/AAQA7rg5Uyc" {:displayName "Yopp Test"}})]
        (should= "Yopp Test" (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" nil)))
        (should= "Yopp Lab"
                 (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" {:displayName "Yopp Lab"})))
        (should= "Yopp Lab" (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" nil)))
        (should= 1 @asked))))

  (it "keeps the space type Chat reported"
    (with-redefs [sut/-token        (fn [_] "at-1")
                  chat-api/get-space! (fn [_ _] {:spaceType "DIRECT_MESSAGE"})]
      (should= "DIRECT_MESSAGE" (:spaceType (sut/space-info :tonotop "spaces/DMM" nil)))))

  (it "a space Chat refuses falls back to what the event itself said, says so, and is asked again"
    (let [asked (atom 0)]
      (with-redefs [sut/-token        (fn [_] "at-1")
                    chat-api/get-space! (fn [_ _] (swap! asked inc) (throw (ex-info "403" {})))]
        (log/capture-logs
          (should= "Yopp Test"
                   (:displayName (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" {:displayName "Yopp Test"})))
          (should (some #(= :gchat.space/unknown (:event %)) @log/captured-logs))
          (sut/space-info :tonotop "spaces/AAQA7rg5Uyc" nil)
          (should= 2 @asked)))))

  (it "no space is nothing to ask about"
    (should-be-nil (sut/space-info :tonotop nil nil)))
  )
