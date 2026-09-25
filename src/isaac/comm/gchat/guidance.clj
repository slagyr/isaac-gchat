(ns isaac.comm.gchat.guidance
  "The standing instruction gchat attaches to every turn it dispatches
   (isaac-acou). The session is per space, not per thread (isaac-ihuc), so one
   transcript holds every thread in the space; this text tells Yopp how to
   read it — group by the [thread:...] markers on each line, answer the
   thread that was addressed, and treat other threads as separate
   conversations. It rides the charge's :guidance, which the prompt builder
   frames into the current user turn exactly once (isaac.charge, hail's own
   metadata preamble uses the same seam) — never the system prompt, and never
   present on a non-gchat turn, since only gchat sets it.")

(def TEXT
  (str "Messages in this space are grouped by thread markers, e.g. [thread:abcd1234]. "
       "The message that addressed you names the thread you are answering. Build your "
       "context from that thread first — other threads in this space are separate "
       "conversations; use them only if the current thread refers to them. Your answer "
       "text is delivered to the addressed thread automatically — do not post it with "
       "gchat__send; that tool is for other threads or spaces. If asked what you did or how you know something, "
       "summarise the tools you used from the transcript in plain words; otherwise "
       "never include a trace or list of tools in your reply."))
