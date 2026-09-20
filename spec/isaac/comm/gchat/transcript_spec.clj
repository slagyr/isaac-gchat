(ns isaac.comm.gchat.transcript-spec
  (:require
    [isaac.comm.gchat.transcript :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def space "spaces/ENG")

(defn with-memory-fs [f]
  (nexus/-with-nested-nexus {:fs (fs/mem-fs) :root "/test/gchat"}
    (f)))

(describe "the per-space chat transcript (isaac-iv5c)"

  (around [it] (with-memory-fs it))

  (it "names one file per space, with no slashes left in it"
    (should= "/test/gchat/google/chat/spaces-ENG.ednl" (sut/space-file space)))

  (it "keeps what was said, oldest first"
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "morning"}))
    (sut/append! space (sut/entry {:sender "grace@tonotop.com" :text "morning back"}))
    (should= ["morning" "morning back"] (mapv :text (sut/recent space))))

  (it "bounds the file"
    (doseq [n (range (+ sut/LIMIT 25))]
      (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text (str "line " n)})))
    (let [kept (sut/recent space (* 2 sut/LIMIT))]
      (should= sut/LIMIT (count kept))
      (should= "line 224" (:text (last kept)))))

  (it "hands a turn only what was said after Isaac last spoke"
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "before"}))
    (sut/append! space (sut/entry {:sender "yopp@tonotop.com" :text "" :self? true}))
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "after one"}))
    (sut/append! space (sut/entry {:sender "grace@tonotop.com" :text "after two"}))
    (should= ["after one" "after two"] (mapv :text (sut/since-reply space))))

  (it "hands a turn the recent past of a space it has never spoken in"
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "one"}))
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "two"}))
    (should= ["one" "two"] (mapv :text (sut/since-reply space))))

  (it "caps the context it hands over"
    (doseq [n (range 40)]
      (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text (str "line " n)})))
    (should= sut/CONTEXT-LINES (count (sut/since-reply space))))

  (it "answers nothing for a space with no file"
    (should= [] (sut/recent "spaces/NEVER"))
    (should= [] (sut/since-reply "spaces/NEVER")))

  (it "keeps the thread and the speaker on each line"
    (sut/append! space (sut/entry {:sender "ada@tonotop.com" :text "hi" :thread "spaces/ENG/threads/T1"}))
    (let [line (last (sut/recent space))]
      (should= "ada@tonotop.com" (:sender line))
      (should= "spaces/ENG/threads/T1" (:thread line))))
  )
