(ns isaac.comm.gchat.guidance-spec
  (:require
    [isaac.comm.gchat.guidance :as sut]
    [speclj.core :refer :all]))

(describe "the gchat comm's standing thread guidance"

  (it "tells Isaac to group by thread marker and answer the addressed thread"
    (should (string? sut/TEXT))
    (should (re-find #"thread" sut/TEXT))
    (should (re-find #"names the thread you are answering" sut/TEXT)))

  (it "tells Isaac to summarise tools used in plain words when asked, and never trace otherwise (isaac-1bq1)"
    (should (re-find #"(?i)what you did" sut/TEXT))
    (should (re-find #"(?i)tools you used" sut/TEXT))
    (should (re-find #"(?i)never include a trace" sut/TEXT)))

  (it "explains reply placement without sending the response with comm__send"
    (should-contain "your reply will post" sut/TEXT)
    (should-contain "gchat__reply_in" sut/TEXT)
    (should-contain "never send it with comm__send" sut/TEXT))

  (it "no longer names the retired gchat__send (isaac-baf1)"
    (should-not (re-find #"gchat__send" sut/TEXT)))
  )
