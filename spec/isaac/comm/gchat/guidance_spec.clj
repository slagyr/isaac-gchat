(ns isaac.comm.gchat.guidance-spec
  (:require
    [isaac.comm.gchat.guidance :as sut]
    [speclj.core :refer :all]))

(describe "the gchat comm's standing thread guidance"

  (it "tells Yopp to group by thread marker and answer the addressed thread"
    (should (string? sut/TEXT))
    (should (re-find #"thread" sut/TEXT))
    (should (re-find #"names the thread you are answering" sut/TEXT)))

  (it "tells Yopp to summarise tools used in plain words when asked, and never trace otherwise (isaac-1bq1)"
    (should (re-find #"(?i)what you did" sut/TEXT))
    (should (re-find #"(?i)tools you used" sut/TEXT))
    (should (re-find #"(?i)never include a trace" sut/TEXT)))

  (it "draws the response/send line with comm__send, verbatim (isaac-baf1)"
    (should-contain (str "Your response is the text you end this turn with. It is delivered back over "
                         "the channel this message came from, so never send it with comm__send. That "
                         "tool is for additional messages of your own during the turn: several "
                         "messages in a row, a message to another thread, space or person, or "
                         "something you were asked to send. Those never replace your response, so "
                         "still end the turn with it, even if it is short.")
                    sut/TEXT))

  (it "no longer names the retired gchat__send (isaac-baf1)"
    (should-not (re-find #"gchat__send" sut/TEXT)))
  )
