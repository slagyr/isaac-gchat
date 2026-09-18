(ns isaac.comm.gchat.format-spec
  (:require
    [isaac.comm.gchat.format :as sut]
    [speclj.core :refer :all]))

(describe "gchat text format"

  (it "passes plain text through"
    (should= "All green." (sut/->chat-text "All green.")))

  (it "translates markdown bold to Chat asterisks"
    (should= "hello *world*" (sut/->chat-text "hello **world**")))

  (it "translates markdown italic to Chat underscore"
    (should= "hello _world_" (sut/->chat-text "hello *world*")))

  (it "translates a markdown link to Chat link syntax"
    (should= "see <https://ex.com|docs>" (sut/->chat-text "see [docs](https://ex.com)")))

  (it "turns a markdown table into a code block"
    (should= "```\n| a | b |\n| --- | --- |\n| 1 | 2 |\n```"
             (sut/->chat-text "| a | b |\n| --- | --- |\n| 1 | 2 |")))

  (it "splits at newline boundaries under the cap"
    (should= ["alpha bravo" "charlie delta" "echo"]
             (sut/split-content "alpha bravo\ncharlie delta\necho" 13)))

  (it "does not split when under the cap"
    (should= ["All green."] (sut/split-content "All green." 4096)))

  )
