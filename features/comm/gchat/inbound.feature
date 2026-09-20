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
      | sender.name         | users/999             |
      | sender.domainId     | 0xother               |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/RANDOM/messages/1"
    And Google Chat delivers a message event for "spaces/ENG/messages/4"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason  | sender.user | sender.domain |
      | :debug | :gchat/message-dropped | :space  |             |               |
      | :info  | :gchat/message-dropped | :sender | users/999   | 0xother       |

  Scenario: a human sender is admitted by users/<id> or domain:<id> — Chat does not return emails for people
    Given config:
      | comms.gchat.gchat/allow-from | ["users/118285940969606191299"] |
    And the Chat API returns message "spaces/ENG/messages/5":
      | sender.name         | users/118285940969606191299 |
      | sender.displayName  | Micah Martin                |
      | sender.domainId     | 0ivzlyj                     |
      | thread.name         | spaces/ENG/threads/T5       |
      | text                | @Isaac hi                   |
      | annotations.mention | users/yopp                  |
    When Google Chat delivers a message event for "spaces/ENG/messages/5"
    Then the session count is 1
    And the log has entries matching:
      | level | event                 |
      | :info | :gchat/message-routed |

  Scenario: an email allow-list admits a Chat sender whose id resolves to that email
    Given config:
      | comms.gchat.gchat/allow-from | ["micah@tonotop.com"] |
    And the Google People API knows "users/118" as "Micah Martin" with email "micah@tonotop.com"
    And the Chat API returns message "spaces/ENG/messages/6":
      | sender.name         | users/118             |
      | sender.displayName  | Micah Martin          |
      | sender.domainId     | 0ivzlyj               |
      | thread.name         | spaces/ENG/threads/T6 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/ENG/messages/6"
    Then the session count is 1
    And the log has entries matching:
      | level | event                 |
      | :info | :gchat/message-routed |

  Scenario: the turn input names who spoke
    Given config:
      | comms.gchat.gchat/allow-from | ["micah@tonotop.com"] |
    And the Google People API knows "users/118" as "Micah Martin" with email "micah@tonotop.com"
    And the Chat API returns message "spaces/ENG/messages/7":
      | sender.name         | users/118                |
      | sender.displayName  | Micah Martin             |
      | sender.domainId     | 0ivzlyj                  |
      | thread.name         | spaces/ENG/threads/T7    |
      | text                | @Isaac can you look?     |
      | annotations.mention | users/yopp               |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Google Chat delivers a message event for "spaces/ENG/messages/7"
    Then session "gchat-spaces-ENG" has transcript matching:
      | type    | message.role | message.content                                     |
      | message | user         | #"Micah Martin <micah@tonotop\.com>: @Isaac can you look\?" |
      | message | assistant    | On it.                                              |

  Scenario: without the directory scope the allow-list falls back to users/<id> and warns once
    Given config:
      | comms.gchat.gchat/allow-from | ["users/118", "users/119"] |
    And the Google People API refuses with 403 "Request had insufficient authentication scopes."
    And the Chat API returns message "spaces/ENG/messages/8":
      | sender.name         | users/118             |
      | sender.displayName  | Micah Martin          |
      | sender.domainId     | 0ivzlyj               |
      | thread.name         | spaces/ENG/threads/T8 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    And the Chat API returns message "spaces/ENG/messages/8b":
      | sender.name         | users/119             |
      | sender.displayName  | Ada Lovelace          |
      | sender.domainId     | 0ivzlyj               |
      | thread.name         | spaces/ENG/threads/T8 |
      | text                | @Isaac hi again       |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/ENG/messages/8"
    And Google Chat delivers a message event for "spaces/ENG/messages/8b"
    Then the session count is 1
    And the log has entries matching:
      | level | event                        | scope                                              |
      | :warn | :google.people/scope-missing | https://www.googleapis.com/auth/directory.readonly |
      | :info | :gchat/message-routed        |                                                    |
    And exactly 1 log entry has event ":google.people/scope-missing"

  Scenario: a failed lookup does not block a sender the id list already admits
    Given config:
      | comms.gchat.gchat/allow-from | ["domain:0ivzlyj"] |
    And the Google People API refuses with 500 "backend error"
    And the Chat API returns message "spaces/ENG/messages/9":
      | sender.name         | users/999             |
      | sender.displayName  | Ada Lovelace          |
      | sender.domainId     | 0ivzlyj               |
      | thread.name         | spaces/ENG/threads/T9 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/ENG/messages/9"
    Then the session count is 1
    And the log has entries matching:
      | level | event                 |
      | :info | :gchat/message-routed |

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

  Scenario: a *@domain allow-list entry admits the whole domain and nobody else (isaac-dymn)
    Given config:
      | comms.gchat.gchat/allow-from | ["*@tonotop.com"] |
    And the Chat API returns message "spaces/ENG/messages/20":
      | sender.email        | grace@tonotop.com      |
      | thread.name         | spaces/ENG/threads/T20 |
      | text                | @Isaac ship it         |
      | annotations.mention | users/yopp             |
    And the Chat API returns message "spaces/ENG/messages/21":
      | sender.email        | mallory@example.com    |
      | thread.name         | spaces/ENG/threads/T21 |
      | text                | @Isaac ship it         |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | Shipping. |
    When Google Chat delivers a message event for "spaces/ENG/messages/20"
    And Google Chat delivers a message event for "spaces/ENG/messages/21"
    Then the session count is 1
    And the log has entries matching:
      | level | event                  | reason  | sender.email        |
      | :info | :gchat/message-routed  |         |                     |
      | :info | :gchat/message-dropped | :sender | mallory@example.com |
