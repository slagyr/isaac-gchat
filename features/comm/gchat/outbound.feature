Feature: Google Chat outbound
  A turn that came from Chat replies in the thread it came from. Unprompted
  sends target a space by name, or a person by email, resolving or creating
  the DM space. Long replies split like Discord's. Sent as the Google user
  with the access token from isaac-6aw3. Bean: isaac-2wr9.

  Background:
    Given default Grover setup in "/test/gchat-outbound"
    And config:
      | log.output                               | memory              |
      | google.tonotop.project                   | marigold            |
      | comms.gchat.gchat/account                | yopp@tonotop.com    |
      | comms.gchat.gchat/account-id             | users/yopp          |
      | comms.gchat.gchat/allow-from             | ["ada@tonotop.com"] |
      | comms.gchat.gchat/spaces.spaces/ENG.name | engineering         |
      | comms.gchat.gchat/spaces.spaces/ENG.crew | main                |
      | sessions.naming-strategy                 | sequential          |
    And the google auth store has access "at-1" and refresh "rt-1"
    And gchat outbound comm is registered

  Scenario: a turn's reply is posted in the originating thread as the Google user
    Given the Chat API returns message "spaces/ENG/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp            |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | All green. |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | method                   | POST                                 |
      | headers.Authorization    | Bearer at-1                          |
      | query.messageReplyOption | REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD |
      | body.thread.name         | spaces/ENG/threads/T1                |
      | body.text                | All green.                           |

  Scenario: send! to a person resolves the DM space, creating it when absent
    Given the Chat API has no direct message space with "bob@tonotop.com"
    And the Chat API creates space "spaces/DMBOB" on setup
    When gchat comm send! is invoked with:
      | path     | value           |
      | gchat/to | bob@tonotop.com |
      | content  | Standup in 5.   |
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces:findDirectMessage" matches:
      | method     | GET                   |
      | query.name | users/bob@tonotop.com |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces:setup" matches:
      | method                         | POST                  |
      | body.space.spaceType           | DIRECT_MESSAGE        |
      | body.memberships.0.member.name | users/bob@tonotop.com |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/DMBOB/messages" matches:
      | method    | POST          |
      | body.text | Standup in 5. |

  Scenario: send! to a space accepts the configured name or the resource name, with an optional thread
    When gchat comm send! is invoked with:
      | path        | value       |
      | gchat/space | engineering |
      | content     | Red alert!  |
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | method    | POST       |
      | body.text | Red alert! |
    When gchat comm send! is invoked with:
      | path         | value                 |
      | gchat/space  | spaces/ENG            |
      | gchat/thread | spaces/ENG/threads/T1 |
      | content      | All clear.            |
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | #index           | 1                     |
      | method           | POST                  |
      | body.thread.name | spaces/ENG/threads/T1 |
      | body.text        | All clear.            |

  Scenario: a reply longer than the message cap is split at newline boundaries, in order
    Given config:
      | comms.gchat.gchat/message-cap | 13 |
    And the Chat API returns message "spaces/ENG/messages/2":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/ENG/threads/T2 |
      | text                | @Isaac report         |
      | annotations.mention | users/yopp            |
    And the following model responses are queued:
      | model | type | content                          |
      | echo  | text | alpha bravo\ncharlie delta\necho |
    When Google Chat delivers a message event for "spaces/ENG/messages/2"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | #index           | 0                     |
      | body.thread.name | spaces/ENG/threads/T2 |
      | body.text        | alpha bravo           |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | #index    | 1             |
      | body.text | charlie delta |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | #index    | 2    |
      | body.text | echo |

  Scenario: a reply Chat 403s in a joined room surfaces as a delivery failure, not a bare create-failed error (isaac-qry7)
    Given the Chat API refuses messages.create in "spaces/ENG" with 403
    And the Chat API returns message "spaces/ENG/messages/5":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/ENG/threads/T5 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | All green. |
    When Google Chat delivers a message event for "spaces/ENG/messages/5"
    Then the log has entries matching:
      | level  | event                  | space      | thread                | status |
      | :error | :gchat/delivery-failed | spaces/ENG | spaces/ENG/threads/T5 | 403    |
    And the log has entries matching:
      | level | event            | class              |
      | :warn | :gchat/turn-notice | :delivery-failure |

  Scenario: two threads in one DM each get their own reply, in their own thread (isaac-acou)
    The session stays per DM (isaac-ihuc), so both threads share one
    transcript; each reply still goes to the thread that addressed it, and
    the transcript keeps each message's thread marked.
    Given the Chat API returns message "spaces/DM2/messages/1":
      | sender.email | ada@tonotop.com        |
      | space.type   | DIRECT_MESSAGE         |
      | thread.name  | spaces/DM2/threads/TA  |
      | text         | did we ship yet?       |
    And the Chat API returns message "spaces/DM2/messages/2":
      | sender.email | ada@tonotop.com          |
      | space.type   | DIRECT_MESSAGE           |
      | thread.name  | spaces/DM2/threads/TB    |
      | text         | are you free for lunch? |
    And the following model responses are queued:
      | model | type | content  |
      | echo  | text | Not yet. |
      | echo  | text | Yes.     |
    When Google Chat delivers a message event for "spaces/DM2/messages/1"
    And Google Chat delivers a message event for "spaces/DM2/messages/2"
    Then session "gchat-tonotop-spaces-dm2" has transcript matching:
      | type    | message.role | message.content                                              |
      | message | user         | #"\[thread:TA\] ada@tonotop\.com: did we ship yet\?"         |
      | message | assistant    | Not yet.                                                     |
      | message | user         | #"\[thread:TB\] ada@tonotop\.com: are you free for lunch\?"  |
      | message | assistant    | Yes.                                                          |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/DM2/messages" matches:
      | #index           | 0                     |
      | body.thread.name | spaces/DM2/threads/TA |
      | body.text        | Not yet.              |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/DM2/messages" matches:
      | #index           | 1                     |
      | body.thread.name | spaces/DM2/threads/TB |
      | body.text        | Yes.                  |

  Scenario: what Isaac sends does not come back as a turn
    Given the Chat API returns message "spaces/ENG/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp            |
    And the Chat API returns message "spaces/ENG/messages/9":
      | sender.email | yopp@tonotop.com      |
      | thread.name  | spaces/ENG/threads/T1 |
      | text         | All green.            |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | All green. |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    And Google Chat delivers a message event for "spaces/ENG/messages/9"
    Then the session count is 1
    And the log has entries matching:
      | level  | event                  | reason |
      | :debug | :gchat/message-dropped | :self  |

  # What went wrong — isaac-h5v8. A turn the drive ends badly still gets an
  # answer in the thread: never silence, never a stack trace or provider
  # payload, never a token. Each scenario below gets its own space so the
  # per-session park state one scenario leaves behind never leaks into the
  # next (isaac.comm.gchat/parked-sessions lives for the process, not the
  # scenario).

  Scenario: a turn that errors gets a short in-thread notice naming the failure class
    Given config:
      | comms.gchat.gchat/spaces.spaces/ERR1.name | errors |
      | comms.gchat.gchat/spaces.spaces/ERR1.crew | main   |
    And the Chat API returns message "spaces/ERR1/messages/10":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/ERR1/threads/T10 |
      | text                | @Isaac status?          |
      | annotations.mention | users/yopp              |
    And the following model responses are queued:
      | model | type  | content                         |
      | echo  | error | wire format mismatch: token xy9 |
    When Google Chat delivers a message event for "spaces/ERR1/messages/10"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/ERR1/messages" were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ERR1/messages" matches:
      | method           | POST                                                         |
      | body.thread.name | spaces/ERR1/threads/T10                                      |
      | body.text        | #"(?is)(?=.*provider error)(?!.*mismatch)(?!.*token xy9).*"  |

  Scenario: a wall mid-turn gets one in-thread notice with the reason and retry time
    Given config:
      | comms.gchat.gchat/spaces.spaces/WX1.name | wall-one |
      | comms.gchat.gchat/spaces.spaces/WX1.crew | main     |
    And the Chat API returns message "spaces/WX1/messages/20":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/WX1/threads/T20  |
      | text                | @Isaac status?          |
      | annotations.mention | users/yopp              |
    And the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When Google Chat delivers a message event for "spaces/WX1/messages/20"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/WX1/messages" were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/WX1/messages" matches:
      | method           | POST                                                                        |
      | body.thread.name | spaces/WX1/threads/T20                                                     |
      | body.text        | #"(?is).*out of tokens until \d{1,2}:\d{2}(am\|pm); i will answer then\..*" |

  Scenario: after a park, the reply that follows posts no extra notice
    Given config:
      | comms.gchat.gchat/spaces.spaces/WX2.name | wall-two |
      | comms.gchat.gchat/spaces.spaces/WX2.crew | main     |
    And the Chat API returns message "spaces/WX2/messages/30":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/WX2/threads/T30  |
      | text                | @Isaac status?          |
      | annotations.mention | users/yopp              |
    And the Chat API returns message "spaces/WX2/messages/31":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/WX2/threads/T30  |
      | text                | @Isaac still there?     |
      | annotations.mention | users/yopp              |
    And the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When Google Chat delivers a message event for "spaces/WX2/messages/30"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/WX2/messages" were made
    Given the following model responses are queued:
      | model | type | content    |
      | echo  | text | All clear. |
    When Google Chat delivers a message event for "spaces/WX2/messages/31"
    Then 2 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/WX2/messages" were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/WX2/messages" matches:
      | #index    | 1          |
      | body.text | All clear. |

  Scenario: a second wall in the same park posts no second notice
    Given config:
      | comms.gchat.gchat/spaces.spaces/WX3.name | wall-three |
      | comms.gchat.gchat/spaces.spaces/WX3.crew | main       |
    And the Chat API returns message "spaces/WX3/messages/40":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/WX3/threads/T40  |
      | text                | @Isaac status?          |
      | annotations.mention | users/yopp              |
    And the Chat API returns message "spaces/WX3/messages/41":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/WX3/threads/T40  |
      | text                | @Isaac still there?     |
      | annotations.mention | users/yopp              |
    And the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When Google Chat delivers a message event for "spaces/WX3/messages/40"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/WX3/messages" were made
    Given the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When Google Chat delivers a message event for "spaces/WX3/messages/41"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/WX3/messages" were made

  # Reactions on the triggering message show progress instead of a status
  # post — 👀 working, ✅ answered, ⚠️ failed, ⏳ parked (isaac-1bq1). Chat lets
  # a user add and remove reactions and neither notifies. Each scenario below
  # gets its own space so the per-session reaction state (isaac.comm.gchat/
  # reaction-state*) one scenario leaves behind never leaks into the next.

  Scenario: an addressed message gets a working reaction, then done once the reply posts (isaac-1bq1)
    Given config:
      | comms.gchat.gchat/spaces.spaces/RX1.name | reactions-one |
      | comms.gchat.gchat/spaces.spaces/RX1.crew | main          |
    And the Chat API returns message "spaces/RX1/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX1/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | All green. |
    When Google Chat delivers a message event for "spaces/RX1/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX1/messages/1/reactions" matches:
      | #index             | 0    |
      | method              | POST |
      | body.emoji.unicode  | 👀   |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX1/messages/1/reactions/1" matches:
      | method | DELETE |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX1/messages/1/reactions" matches:
      | #index             | 1    |
      | method              | POST |
      | body.emoji.unicode  | ✅   |
    And 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX1/messages/1/reactions/1" were made

  Scenario: an error turn gets a working reaction, then failed, alongside the h5v8 in-thread notice (isaac-1bq1)
    Given config:
      | comms.gchat.gchat/spaces.spaces/RX2.name | reactions-two |
      | comms.gchat.gchat/spaces.spaces/RX2.crew | main          |
    And the Chat API returns message "spaces/RX2/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX2/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type  | content                         |
      | echo  | error | wire format mismatch: token xy9 |
    When Google Chat delivers a message event for "spaces/RX2/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX2/messages/1/reactions" matches:
      | #index             | 0  |
      | body.emoji.unicode | 👀 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX2/messages/1/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | ⚠️ |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX2/messages" matches:
      | body.text | #"(?is)(?=.*provider error).*" |

  Scenario: a parked turn shows the parked reaction until the reply that follows answers (isaac-1bq1)
    Given config:
      | comms.gchat.gchat/spaces.spaces/RX3.name | reactions-three |
      | comms.gchat.gchat/spaces.spaces/RX3.crew | main            |
    And the Chat API returns message "spaces/RX3/messages/1":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/RX3/threads/T1   |
      | text                | @Isaac status?          |
      | annotations.mention | users/yopp              |
    And the Chat API returns message "spaces/RX3/messages/2":
      | sender.email        | ada@tonotop.com         |
      | thread.name         | spaces/RX3/threads/T1   |
      | text                | @Isaac still there?     |
      | annotations.mention | users/yopp              |
    And the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When Google Chat delivers a message event for "spaces/RX3/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX3/messages/1/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | ⏳ |
    And 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX3/messages" were made
    Given the following model responses are queued:
      | model | type | content    |
      | echo  | text | All clear. |
    When Google Chat delivers a message event for "spaces/RX3/messages/2"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX3/messages/2/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | ✅ |
    And 2 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX3/messages" were made

  Scenario: a message Isaac only heard, not addressed, gets no reaction (isaac-1bq1)
    Given config:
      | comms.gchat.gchat/spaces.spaces/RX4.name | reactions-four |
      | comms.gchat.gchat/spaces.spaces/RX4.crew | main           |
    And the Chat API returns message "spaces/RX4/messages/1":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/RX4/threads/T1 |
      | text         | anyone around?        |
    When Google Chat delivers a message event for "spaces/RX4/messages/1"
    Then no reaction calls were made

  Scenario: gchat/reactions false turns the lifecycle off, the reply still posts once (isaac-1bq1)
    Given config:
      | comms.gchat.gchat/reactions               | false          |
      | comms.gchat.gchat/spaces.spaces/RX5.name  | reactions-five |
      | comms.gchat.gchat/spaces.spaces/RX5.crew  | main           |
    And the Chat API returns message "spaces/RX5/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX5/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | All clear. |
    When Google Chat delivers a message event for "spaces/RX5/messages/1"
    Then no reaction calls were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX5/messages" matches:
      | body.text | All clear. |

  # Accumulated progress reactions — 🧠 thought, 🔧 tool, 💬 aside stay on the
  # triggering message once added, alongside the 1bq1 status lifecycle
  # (isaac-oits). Each scenario below gets its own space, same reason as RX1-5.

  Scenario: a turn with two reasoning bursts and a tool call carries 🧠 🔧 💬 ✅ — only 👀 was ever deleted (isaac-oits)
    Given the crew "main" allows tools: "gchat__spaces"
    And config:
      | comms.gchat.gchat/spaces.spaces/RX6.name | reactions-six |
      | comms.gchat.gchat/spaces.spaces/RX6.crew | main          |
    And the Chat API returns message "spaces/RX6/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX6/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type      | content         | tool_call     | arguments |
      | echo  | reasoning | First thought.  |               |           |
      | echo  |           |                 | gchat__spaces | {}        |
      | echo  | reasoning | Second thought. |               |           |
      | echo  | text      | All set.        |               |           |
    When Google Chat delivers a message event for "spaces/RX6/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" matches:
      | #index             | 0  |
      | body.emoji.unicode | 👀 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | 🧠 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" matches:
      | #index             | 2  |
      | body.emoji.unicode | 🔧 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" matches:
      | #index             | 3  |
      | body.emoji.unicode | 💬 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" matches:
      | #index             | 4  |
      | body.emoji.unicode | ✅ |
    And 5 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions" were made
    And 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX6/messages/1/reactions/1" were made

  Scenario: three tool calls in one turn add the tool and aside glyphs once each, not per call (isaac-oits)
    Given the crew "main" allows tools: "gchat__spaces"
    And config:
      | comms.gchat.gchat/spaces.spaces/RX7.name | reactions-seven |
      | comms.gchat.gchat/spaces.spaces/RX7.crew | main            |
    And the Chat API returns message "spaces/RX7/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX7/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content | tool_call     | arguments |
      | echo  |      |         | gchat__spaces | {}        |
      | echo  |      |         | gchat__spaces | {}        |
      | echo  |      |         | gchat__spaces | {}        |
      | echo  | text | Done.   |               |           |
    When Google Chat delivers a message event for "spaces/RX7/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX7/messages/1/reactions" matches:
      | #index             | 0  |
      | body.emoji.unicode | 👀 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX7/messages/1/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | 🔧 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX7/messages/1/reactions" matches:
      | #index             | 2  |
      | body.emoji.unicode | 💬 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX7/messages/1/reactions" matches:
      | #index             | 3  |
      | body.emoji.unicode | ✅ |
    And 4 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX7/messages/1/reactions" were made

  Scenario: gchat/reactions {tool false} skips only the tool glyph, other kinds unaffected (isaac-oits)
    Given the crew "main" allows tools: "gchat__spaces"
    And config:
      | comms.gchat.gchat/reactions.tool         | false           |
      | comms.gchat.gchat/spaces.spaces/RX8.name | reactions-eight |
      | comms.gchat.gchat/spaces.spaces/RX8.crew | main            |
    And the Chat API returns message "spaces/RX8/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/RX8/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type      | content        | tool_call     | arguments |
      | echo  | reasoning | Thinking away. |               |           |
      | echo  |           |                | gchat__spaces | {}        |
      | echo  | text      | Done.          |               |           |
    When Google Chat delivers a message event for "spaces/RX8/messages/1"
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX8/messages/1/reactions" matches:
      | #index             | 0  |
      | body.emoji.unicode | 👀 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX8/messages/1/reactions" matches:
      | #index             | 1  |
      | body.emoji.unicode | 🧠 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX8/messages/1/reactions" matches:
      | #index             | 2  |
      | body.emoji.unicode | 💬 |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/RX8/messages/1/reactions" matches:
      | #index             | 3  |
      | body.emoji.unicode | ✅ |
    And 4 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/RX8/messages/1/reactions" were made

  # One send tool (isaac-baf1). The response is the text the turn ends with
  # and the comm posts it; comm__send is for additional messages. A send into
  # the origin thread is such a message — it never stands in for the
  # response, so both post. comm__send is queue-first, so the reply posts
  # first and the tool delivery posts when the delivery worker ticks.

  @wip
  Scenario: comm__send into the origin thread during the turn, then the answer — both post (isaac-baf1)
    Given the crew "main" allows tools: "comm/send"
    And config:
      | comms.gchat.gchat/spaces.spaces/OS1.name | one-send |
      | comms.gchat.gchat/spaces.spaces/OS1.crew | main     |
    And the Chat API returns message "spaces/OS1/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/OS1/threads/T1 |
      | text                | @Isaac status?        |
      | annotations.mention | users/yopp            |
    And the following model responses are queued:
      | model | type | content    | tool_call  | arguments                                                                                                  |
      | echo  |      |            | comm__send | {"comm":"gchat","gchat.space":"spaces/OS1","gchat.thread":"spaces/OS1/threads/T1","content":"Looking now."} |
      | echo  | text | All green. |            |                                                                                                            |
    When Google Chat delivers a message event for "spaces/OS1/messages/1"
    And the delivery worker ticks
    Then 2 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/OS1/messages" were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/OS1/messages" matches:
      | #index           | 0                     |
      | body.thread.name | spaces/OS1/threads/T1 |
      | body.text        | All green.            |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/OS1/messages" matches:
      | #index           | 1                     |
      | body.thread.name | spaces/OS1/threads/T1 |
      | body.text        | Looking now.          |

  # Attachments (isaac-vlxz): Chat takes a media upload per file first, then
  # the message references what was uploaded.

  Scenario: comm__send with an attachment uploads it, then posts the message referencing it (isaac-vlxz)
    Given the crew "main" allows tools: "comm/send"
    And config:
      | comms.gchat.gchat/spaces.spaces/AT1.name | attach-one |
      | comms.gchat.gchat/spaces.spaces/AT1.crew | main       |
    And a file "report.pdf" exists in the session working directory with content "%PDF-1.4 stub"
    And the Chat API returns message "spaces/AT1/messages/1":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/AT1/threads/T1 |
      | text                | @Isaac send the report |
      | annotations.mention | users/yopp            |
    And the following model responses are queued:
      | model | type | content | tool_call  | arguments                                                                                                                                       |
      | echo  |      |         | comm__send | {"comm":"gchat","gchat.space":"spaces/AT1","gchat.thread":"spaces/AT1/threads/T1","content":"Here is the report.","attachments":["report.pdf"]} |
      | echo  | text | Sent.   |            |                                                                                                                                                 |
    When Google Chat delivers a message event for "spaces/AT1/messages/1"
    And the delivery worker ticks
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/upload/v1/spaces/AT1/attachments:upload" were made
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/AT1/messages" matches:
      | #index                                           | 1                     |
      | body.thread.name                                 | spaces/AT1/threads/T1 |
      | body.text                                        | Here is the report.   |
      | body.attachment.0.attachmentDataRef.resourceName | #".+"                 |
