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

  ;; Chat refuses spaces.get on a direct message it lists without complaint
  ;; (403, yopp 2026-09-23). The listing is the fallback, asked once, and its
  ;; answer is remembered like a get (isaac-f4ab).
  (it "falls back to the account's listing when spaces.get is refused, and remembers it"
    (let [asked  (atom 0)
          listed (atom 0)]
      (with-redefs [sut/-token          (fn [_] "at-1")
                    chat-api/get-space! (asking asked {})
                    chat-api/list-spaces! (fn [_token & _]
                                            (swap! listed inc)
                                            {:spaces [{:name "spaces/ROOM" :spaceType "SPACE" :displayName "Eng"}
                                                      {:name "spaces/DM1" :spaceType "DIRECT_MESSAGE"}]})]
        (should= "DIRECT_MESSAGE" (:spaceType (sut/space-info :tonotop "spaces/DM1" nil)))
        (should= "DIRECT_MESSAGE" (:spaceType (sut/space-info :tonotop "spaces/DM1" nil)))
        (should= 1 @listed))))

  (it "walks the listing's pages before giving up"
    (let [pages (atom 0)]
      (with-redefs [sut/-token          (fn [_] "at-1")
                    chat-api/get-space! (asking (atom 0) {})
                    chat-api/list-spaces! (fn [_token & [{:keys [page-token]}]]
                                            (swap! pages inc)
                                            (if page-token
                                              {:spaces [{:name "spaces/DM2" :spaceType "DIRECT_MESSAGE"}]}
                                              {:spaces [{:name "spaces/OTHER" :spaceType "SPACE"}] :nextPageToken "p2"}))]
        (should= "DIRECT_MESSAGE" (:spaceType (sut/space-info :tonotop "spaces/DM2" nil)))
        (should= 2 @pages))))

  (it "does not list when spaces.get answers"
    (let [listed (atom 0)]
      (with-redefs [sut/-token          (fn [_] "at-1")
                    chat-api/get-space! (asking (atom 0) {"spaces/ROOM" {:displayName "Eng" :spaceType "SPACE"}})
                    chat-api/list-spaces! (fn [& _] (swap! listed inc) {:spaces []})]
        (sut/space-info :tonotop "spaces/ROOM" nil)
        (should= 0 @listed))))

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
