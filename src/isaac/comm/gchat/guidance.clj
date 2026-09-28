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
       "conversations; use them only if the current thread refers to them. "
       "Several messages in one thread may arrive together; answer them as one reply. "
       "Your response is the text you end this turn with. It is delivered back over "
       "the channel this message came from, so never send it with comm__send. That "
       "tool is for additional messages of your own during the turn: several "
       "messages in a row, a message to another thread, space or person, or "
       "something you were asked to send. Those never replace your response, so "
       "still end the turn with it, even if it is short. "
       "If asked what you did or how you know something, "
       "summarise the tools you used from the transcript in plain words; otherwise "
       "never include a trace or list of tools in your reply. Files people attach are saved under attachments/ in your working directory and listed with the message; read them with the file tools. You cannot view images yet — say so if asked what an image shows."))
