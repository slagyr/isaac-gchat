(ns isaac.comm.gchat.attachment-spec
  (:require
    [isaac.comm.gchat.attachment :as sut]
    [isaac.comm.gchat.chat-api :as chat-api]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "gchat outbound attachments (isaac-vlxz)"

  (it "names a content type by extension, case-insensitively"
    (should= "application/pdf" (sut/content-type "report.PDF"))
    (should= "image/png" (sut/content-type "shot.png"))
    (should= "text/plain" (sut/content-type "notes.txt")))

  (it "falls back to octet-stream for an unknown or missing extension"
    (should= "application/octet-stream" (sut/content-type "blob.xyz"))
    (should= "application/octet-stream" (sut/content-type "Makefile")))

  (it "throws naming the path when a file cannot be read"
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (with-redefs [chat-api/upload-attachment! (fn [_] {:resourceName "r"})]
        (should-throw clojure.lang.ExceptionInfo "attachment not found: /work/gone.pdf"
                      (sut/upload-all! "spaces/ENG" "at-1" ["/work/gone.pdf"])))))

  (it "uploads nothing for no paths"
    (should= [] (sut/upload-all! "spaces/ENG" "at-1" nil))))
