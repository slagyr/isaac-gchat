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
