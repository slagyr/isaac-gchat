(ns isaac.comm.gchat.guidance-spec
  (:require
    [isaac.comm.gchat.guidance :as sut]
    [speclj.core :refer :all]))

(describe "the gchat comm's standing thread guidance"

  (it "tells Yopp to group by thread marker and answer the addressed thread"
    (should (string? sut/TEXT))
    (should (re-find #"thread" sut/TEXT))
    (should (re-find #"addressed thread" sut/TEXT)))

  (it "tells Yopp to summarise tools used in plain words when asked, and never trace otherwise (isaac-1bq1)"
    (should (re-find #"(?i)what you did" sut/TEXT))
    (should (re-find #"(?i)tools you used" sut/TEXT))
    (should (re-find #"(?i)never include a trace" sut/TEXT)))
  )
