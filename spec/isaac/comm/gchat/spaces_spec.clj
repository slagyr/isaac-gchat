(ns isaac.comm.gchat.spaces-spec
  (:require
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.comm.gchat.spaces :as sut]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def yopp-test {:name "spaces/AAQA7rg5Uyc" :displayName "Yopp Test" :spaceType "SPACE"})
(def micah-dm  {:name "spaces/DM1" :spaceType "DIRECT_MESSAGE"})

(def start (Instant/parse "2026-09-18T12:00:00Z"))

(defn- at-ms
  "Run f with the clock standing n milliseconds past the start of the spec."
  [n f]
  (binding [memory/*now* (.plusMillis start n)] (f)))

(defn- listing [& pages]
  (let [pages (atom (vec pages))
        asked (atom [])]
    [asked (fn [_token & [opts]]
             (swap! asked conj opts)
             (let [page (first @pages)]
               (swap! pages rest)
               page))]))

(defn- counting-listing []
  (let [calls (atom 0)]
    [calls (fn [& _] (swap! calls inc) {:spaces [yopp-test]})]))

(describe "the spaces the account belongs to"

  (before (sut/forget-known!))

  (it "asks Chat for spaces and DMs, following every page"
    (let [[asked stub] (listing {:spaces [yopp-test] :nextPageToken "p2"}
                                {:spaces [micah-dm]})]
      (with-redefs [chat-api/list-spaces! stub]
        (should= [yopp-test micah-dm] (sut/list-all! "at-1"))
        (should= sut/SPACE-TYPES (:filter* (first @asked)))
        (should= "p2" (:page-token (second @asked))))))

  (it "a refused listing leaves the configured spaces standing alone"
    (with-redefs [chat-api/list-spaces! (fn [& _] (throw (ex-info "403" {})))
                  sut/-token            (fn [_] "at-1")]
      (log/capture-logs
        (should= [] (sut/discovered :tonotop))
        (should (some #(= :gchat.discovery/failed (:event %)) @log/captured-logs)))))

  (it "what Chat last said about a space answers without asking again"
    (let [[calls stub] (counting-listing)]
      (with-redefs [chat-api/list-spaces! stub
                    sut/-token            (fn [_] "at-1")]
        (should= yopp-test (sut/known :tonotop sut/DEFAULT-EVERY-MS "spaces/AAQA7rg5Uyc"))
        (should= yopp-test (sut/known :tonotop sut/DEFAULT-EVERY-MS "spaces/AAQA7rg5Uyc"))
        (should= 1 @calls))))

  (it "a space the listing does not hold is nobody's"
    (let [[calls stub] (counting-listing)]
      (with-redefs [chat-api/list-spaces! stub
                    sut/-token            (fn [_] "at-1")]
        (should-be-nil (sut/known :tonotop sut/DEFAULT-EVERY-MS "spaces/GONE"))
        (should-be-nil (sut/known :tonotop sut/DEFAULT-EVERY-MS "spaces/GONE"))
        (should= 1 @calls))))

  (context "the listing stands for an interval"

    (it "a second ask inside the interval is answered from the last listing"
      (let [[calls stub] (counting-listing)]
        (with-redefs [chat-api/list-spaces! stub
                      sut/-token            (fn [_] "at-1")]
          (at-ms 0     #(should= [yopp-test] (sut/discovered :tonotop 300000)))
          (at-ms 30000 #(should= [yopp-test] (sut/discovered :tonotop 300000)))
          (should= 1 @calls))))

    (it "an ask past the interval lists again"
      (let [[calls stub] (counting-listing)]
        (with-redefs [chat-api/list-spaces! stub
                      sut/-token            (fn [_] "at-1")]
          (at-ms 0      #(sut/discovered :tonotop 300000))
          (at-ms 300000 #(sut/discovered :tonotop 300000))
          (should= 2 @calls))))

    (it "each organization keeps its own listing and its own clock"
      (let [[calls stub] (counting-listing)]
        (with-redefs [chat-api/list-spaces! stub
                      sut/-token            (fn [_] "at-1")]
          (at-ms 0 #(sut/discovered :tonotop 300000))
          (at-ms 0 #(sut/discovered :acme 300000))
          (should= 2 @calls))))

    (it "a comm's own interval overrides the default"
      (should= sut/DEFAULT-EVERY-MS (sut/every-ms {}))
      (should= 60000 (sut/every-ms {:gchat/discover-every-ms 60000})))
    )
  )
