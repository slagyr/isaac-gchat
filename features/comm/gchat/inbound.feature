Feature: Google Chat inbound gate
  A Workspace Events pointer arrives for a space the Google user belongs
  to. The gate is deterministic and runs before any turn: drop Isaac's own
  messages, fail closed on unconfigured spaces and senders, classify DM vs
  space, detect a mention of the account, apply the space's respond
  policy, then route with the shared vocabulary. The inbound step hands
  the pointer event to the contributed :isaac.google/handler directly, the
  way Discord's faked gateway does; the push door is isaac-1jep. Bean: isaac-0gtc.

  Background:
    Given default Grover setup in "/test/gchat-inbound"
    And config:
      | log.output                               | memory              |
      | comms.gchat.gchat/account                | yopp@tonotop.com    |
      | comms.gchat.gchat/allow-from             | ["ada@tonotop.com"] |
      | comms.gchat.gchat/spaces.spaces/ENG.name | Engineering         |
      | comms.gchat.gchat/spaces.spaces/ENG.crew | main                |
      | sessions.naming-strategy                 | sequential          |
    And the Chat API returns message "spaces/ENG/messages/1":
      | sender.email        | ada@tonotop.com                    |
      | thread.name         | spaces/ENG/threads/T1              |
      | text                | @Isaac can you look at the deploy? |
      | annotations.mention | users/yopp                         |

  Scenario: a mention in a configured space starts a turn on the space's session
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    Then session "gchat-spaces-ENG" has transcript matching:
      | type    | message.role | message.content                            |
      | message | user         | #".*ada@tonotop.com.*look at the deploy.*" |
      | message | assistant    | On it.                                     |
    And the log has entries matching:
      | level | event                 | space      | thread                |
      | :info | :gchat/message-routed | spaces/ENG | spaces/ENG/threads/T1 |

  Scenario: a message that does not mention the account starts no turn under the default policy
    Given the Chat API returns message "spaces/ENG/messages/2":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/T1 |
      | text         | lunch anyone?         |
    When Google Chat delivers a message event for "spaces/ENG/messages/2"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason      |
      | :debug | :gchat/message-dropped | :no-mention |

  Scenario: a DM starts a turn without a mention
    Given the Chat API returns message "spaces/DM1/messages/1":
      | sender.email | ada@tonotop.com        |
      | space.type   | DIRECT_MESSAGE         |
      | thread.name  | spaces/DM1/threads/T1  |
      | text         | are you there?         |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
    When Google Chat delivers a message event for "spaces/DM1/messages/1"
    Then session "gchat-spaces-DM1" has transcript matching:
      | type    | message.role | message.content       |
      | message | user         | #".*are you there\?.*" |
      | message | assistant    | Here.                 |

  Scenario: Isaac's own message is dropped
    Given the Chat API returns message "spaces/ENG/messages/3":
      | sender.email        | yopp@tonotop.com      |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | On it.                |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/ENG/messages/3"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason |
      | :debug | :gchat/message-dropped | :self  |

  Scenario: unconfigured spaces and unknown senders fail closed
    Given the Chat API returns message "spaces/RANDOM/messages/1":
      | sender.email        | ada@tonotop.com          |
      | thread.name         | spaces/RANDOM/threads/T1 |
      | text                | @Isaac hi                |
      | annotations.mention | users/yopp               |
    And the Chat API returns message "spaces/ENG/messages/4":
      | sender.email        | mallory@example.com   |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/RANDOM/messages/1"
    And Google Chat delivers a message event for "spaces/ENG/messages/4"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason  |
      | :debug | :gchat/message-dropped | :space  |
      | :debug | :gchat/message-dropped | :sender |

  Scenario: a space with respond policy all answers without a mention
    Given config:
      | comms.gchat.gchat/spaces.spaces/ENG.respond | all |
    And the Chat API returns message "spaces/ENG/messages/2":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/T1 |
      | text         | lunch anyone?         |
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | Tacos.    |
    When Google Chat delivers a message event for "spaces/ENG/messages/2"
    Then session "gchat-spaces-ENG" has transcript matching:
      | type    | message.role | message.content      |
      | message | user         | #".*lunch anyone\?.*" |
      | message | assistant    | Tacos.               |
