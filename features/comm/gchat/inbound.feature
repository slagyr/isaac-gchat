Feature: Google Chat inbound gate
  A Workspace Events pointer arrives for a space the Google user belongs
  to. The gate is deterministic and runs before any turn: drop Isaac's own
  messages, fail closed on unknown senders, classify DM vs space, detect a
  mention of the account, apply the space's respond policy, then route with
  the shared vocabulary. Belonging to the space is the grant - one
  subscription on spaces/- hears every space the account is in, so a space
  nobody configured is heard too (isaac-ihuc). The inbound step hands the
  pointer event to the contributed :isaac.google/handler directly, the way
  Discord's faked gateway does; the push door is isaac-1jep.
  Beans: isaac-0gtc, isaac-ihuc.

  Background:
    Given default Grover setup in "/test/gchat-inbound"
    And config:
      | log.output                               | memory              |
      | comms.gchat.gchat/account                | yopp@tonotop.com    |
      | comms.gchat.gchat/account-id             | users/yopp          |
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

  Scenario: a message that does not mention the account is heard, not answered
    Given the Chat API returns message "spaces/ENG/messages/2":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/T1 |
      | text         | lunch anyone?         |
    When Google Chat delivers a message event for "spaces/ENG/messages/2"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                 | space      |
      | :debug | :gchat/message-logged | spaces/ENG |

  Scenario: a message that mentions someone else is heard, not answered (isaac-klye)
    A mention means the account. A message that @-mentions a colleague is
    not addressed to Isaac, even though it carries a user mention.
    Given the Chat API returns message "spaces/ENG/messages/3":
      | sender.email        | ada@tonotop.com       |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Chris can you look?  |
      | annotations.mention | users/chris           |
    When Google Chat delivers a message event for "spaces/ENG/messages/3"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                 | space      |
      | :debug | :gchat/message-logged | spaces/ENG |

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

  Scenario: an unknown sender fails closed, wherever they speak
    Given the Chat API returns message "spaces/ENG/messages/4":
      | sender.name         | users/999             |
      | sender.domainId     | 0xother               |
      | thread.name         | spaces/ENG/threads/T1 |
      | text                | @Isaac hi             |
      | annotations.mention | users/yopp            |
    When Google Chat delivers a message event for "spaces/ENG/messages/4"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level | event                  | reason  | sender.user | sender.domain |
      | :info | :gchat/message-dropped | :sender | users/999   | 0xother       |

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

  Scenario: the session remembers who spoke, by an id a rename cannot orphan (isaac-bklu)
    Given config:
      | comms.gchat.gchat/allow-from | ["micah@tonotop.com"] |
    And the Google People API knows "users/118285940969606191299" as "Micah Martin" with email "micah@tonotop.com"
    And the Chat API returns message "spaces/ENG/messages/22":
      | sender.name         | users/118285940969606191299 |
      | sender.displayName  | Micah Martin                |
      | sender.domainId     | 0ivzlyj                     |
      | thread.name         | spaces/ENG/threads/T22      |
      | text                | @Isaac who am I?            |
      | annotations.mention | users/yopp                  |
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | You, Micah. |
    When Google Chat delivers a message event for "spaces/ENG/messages/22"
    Then session "gchat-spaces-ENG" has origin:
      | kind         | :gchat                      |
      | space        | spaces/ENG                  |
      | user         | users/118285940969606191299 |
      | display-name | Micah Martin                |
      | email        | micah@tonotop.com           |

  Scenario: a mention carries what the space said while Isaac was quiet, framed as context (isaac-iv5c)
    Given the Chat API returns message "spaces/ENG/messages/30":
      | sender.email | ada@tonotop.com        |
      | thread.name  | spaces/ENG/threads/T30 |
      | text         | the deploy is stuck    |
    And the Chat API returns message "spaces/ENG/messages/31":
      | sender.email | ada@tonotop.com          |
      | thread.name  | spaces/ENG/threads/T30   |
      | text         | send everyone a postcard |
    And the Chat API returns message "spaces/ENG/messages/32":
      | sender.email        | ada@tonotop.com        |
      | thread.name         | spaces/ENG/threads/T30 |
      | text                | @Isaac what happened?  |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | The deploy is. |
    When Google Chat delivers a message event for "spaces/ENG/messages/30"
    And Google Chat delivers a message event for "spaces/ENG/messages/31"
    And Google Chat delivers a message event for "spaces/ENG/messages/32"
    Then session "gchat-spaces-ENG" has transcript matching:
      | type    | message.role | message.content                                                                              |
      | message | user         | #"(?s)\[Chat context; not requests\].*the deploy is stuck.*send everyone a postcard.*\[End chat context\].*what happened\?" |
      | message | assistant    | The deploy is.                                                                               |
    And the session count is 1

  Scenario: a space entry's session-tags choose the session, the way hail does (isaac-tund)
    Given config:
      | comms.gchat.gchat/spaces.spaces/ENG.session-tags | [:ops] |
      | comms.gchat.gchat/spaces.spaces/ENG.create       | if-missing |
    And the following sessions exist:
      | name     | crew | tags    |
      | ops-room | main | #{:ops} |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    Then session "ops-room" has transcript matching:
      | type    | message.role | message.content           |
      | message | user         | #".*look at the deploy.*" |
      | message | assistant    | On it.                    |
    And the log has entries matching:
      | level | event                 | session  |
      | :info | :gchat/message-routed | ops-room |

  Scenario: an explicit session on the entry still pins the session (isaac-tund)
    Given config:
      | comms.gchat.gchat/spaces.spaces/ENG.session | deploy-desk |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    Then session "deploy-desk" has transcript matching:
      | type    | message.role | message.content           |
      | message | user         | #".*look at the deploy.*" |
      | message | assistant    | On it.                    |

  Scenario: Isaac's own reply pushed back with only users/<id> drops as self, not as sender (isaac-mm7o)
    Chat pushes Isaac's own replies back as events, and under user auth the
    sender carries users/<id> and no email. Matching self on the account email
    alone cannot see them. On 2026-09-19 they were dropped as :sender instead —
    the right outcome by luck, because the id was not in the allow-list. With a
    domain allow-list they would have been let through, and Isaac would have
    answered itself.
    Given config:
      | comms.gchat.gchat/account-id | users/101936183306307394083            |
      | comms.gchat.gchat/allow-from | ["domain:tonotop.com", "ada@tonotop.com"] |
    And the Chat API returns message "spaces/ENG/messages/9":
      | sender.name         | users/101936183306307394083 |
      | sender.domainId     | tonotop.com                 |
      | thread.name         | spaces/ENG/threads/T1       |
      | text                | On it.                      |
      | annotations.mention | users/yopp                  |
    When Google Chat delivers a message event for "spaces/ENG/messages/9"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason |
      | :debug | :gchat/message-dropped | :self  |

  Scenario: two tenants each learn their own id; one's echo is never mistaken for the other's (isaac-mm7o)
    A Chat comm speaks for exactly one Google organization (isaac-1zkz). Each
    learns its own users/<id> from its own outbound sends, keyed by that
    organization - an id learned for one organization must never drop a
    message as self for another.
    Given config:
      | comms.gchat.gchat/google       | tonotop                |
      | comms.gchat.gchat/account-id   | #delete                |
      | comms.gchat.gchat/allow-from   | ["domain:tonotop.com"] |
      | comms.gchat-acme.type          | gchat                  |
      | comms.gchat-acme.gchat/google  | acme                   |
      | comms.gchat-acme.gchat/account | isaac@acme.example     |
    And the google auth store for organization "tonotop" has access "at-tonotop" and refresh "rt-tonotop"
    And the google auth store for organization "acme" has access "at-acme" and refresh "rt-acme"
    And gchat comm "gchat" is registered
    When gchat comm send! is invoked with:
      | path        | value       |
      | gchat/space | spaces/ENG  |
      | content     | Tonotop ack |
    Given gchat comm "gchat-acme" is registered
    When gchat comm send! is invoked with:
      | path        | value       |
      | gchat/space | spaces/ACME |
      | content     | Acme ack    |
    And the Chat API returns message "spaces/ENG/messages/10":
      | sender.name         | users/self-at-acme    |
      | sender.domainId     | tonotop.com            |
      | thread.name         | spaces/ENG/threads/T2  |
      | text                | look at this           |
      | annotations.mention | users/self-at-tonotop  |
    When Google Chat delivers a message event for "spaces/ENG/messages/10"
    Then the session count is 1
    And the log has entries matching:
      | level | event                 | space      |
      | :info | :gchat/message-routed | spaces/ENG |
    Given config:
      | comms.gchat.gchat/google | acme |
    And the Chat API returns message "spaces/ENG/messages/11":
      | sender.name         | users/self-at-acme   |
      | sender.domainId     | tonotop.com          |
      | thread.name         | spaces/ENG/threads/T2 |
      | text                | echo                 |
      | annotations.mention | users/yopp           |
    When Google Chat delivers a message event for "spaces/ENG/messages/11"
    Then the session count is 1
    And the log has entries matching:
      | level  | event                  | reason |
      | :debug | :gchat/message-dropped | :self  |

  Scenario: a space nobody configured starts a turn on its canonical session, named from one spaces.get (isaac-ihuc)
    Membership is the grant: the one spaces/- subscription carries a space
    nothing in config mentions, and Chat is asked once what it is called.
    Given config:
      | google.tonotop.topic | projects/marigold/topics/isaac |
    And the Chat API knows space "spaces/AAQA7rg5Uyc":
      | displayName | Yopp Test |
      | spaceType   | SPACE     |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/1":
      | sender.email        | ada@tonotop.com               |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac are you there?         |
      | annotations.mention | users/yopp                    |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/2":
      | sender.email        | ada@tonotop.com               |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac still there?           |
      | annotations.mention | users/yopp                    |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
      | echo  | text | Still.  |
    When Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/1"
    And Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/2"
    Then session "gchat-tonotop-yopp-test" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | #".*are you there\?.*" |
      | message | assistant    | Here.                  |
      | message | user         | #".*still there\?.*"   |
      | message | assistant    | Still.                 |
    And session "gchat-tonotop-yopp-test" is tagged "space:AAQA7rg5Uyc"
    And 1 outbound HTTP request to "https://chat.googleapis.com/v1/spaces/AAQA7rg5Uyc" was made

  Scenario: an event carrying a new display name renames the session, which keeps its history (isaac-ihuc)
    The tag is what holds the session together; the name follows Chat.
    Given config:
      | google.tonotop.topic | projects/marigold/topics/isaac |
    And the Chat API knows space "spaces/AAQA7rg5Uyc":
      | displayName | Yopp Test |
      | spaceType   | SPACE     |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/1":
      | sender.email        | ada@tonotop.com               |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac are you there?         |
      | annotations.mention | users/yopp                    |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/2":
      | sender.email        | ada@tonotop.com               |
      | space.displayName   | Yopp Lab                      |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac still there?           |
      | annotations.mention | users/yopp                    |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
      | echo  | text | Still.  |
    When Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/1"
    And Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/2"
    Then the session count is 1
    And session "gchat-tonotop-yopp-lab" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | #".*are you there\?.*" |
      | message | assistant    | Here.                  |
      | message | user         | #".*still there\?.*"   |
      | message | assistant    | Still.                 |
    And session "gchat-tonotop-yopp-lab" is tagged "space:AAQA7rg5Uyc"
    And the log has entries matching:
      | level | event                   | from                    | to                     |
      | :info | :gchat/session-renamed  | gchat-tonotop-yopp-test | gchat-tonotop-yopp-lab |

  Scenario: a DM's first message starts a session named for the other member (isaac-ihuc)
    Given config:
      | google.tonotop.topic         | projects/marigold/topics/isaac |
      | comms.gchat.gchat/allow-from | ["micah@tonotop.com"]          |
    And the Chat API knows space "spaces/DMM":
      | spaceType | DIRECT_MESSAGE |
    And the Chat API returns message "spaces/DMM/messages/1":
      | sender.email       | micah@tonotop.com     |
      | sender.displayName | Micah Martin          |
      | thread.name        | spaces/DMM/threads/T1 |
      | text               | are you there?        |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
    When Google Chat delivers a message event for "spaces/DMM/messages/1"
    Then session "gchat-tonotop-dm-micah-martin" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | #".*are you there\?.*" |
      | message | assistant    | Here.                  |
    And session "gchat-tonotop-dm-micah-martin" is tagged "space:DMM"

  Scenario: two spaces sharing a display name get two sessions (isaac-xy2i)
    Given config:
      | google.tonotop.topic | projects/marigold/topics/isaac |
    And the Chat API knows space "spaces/AAQA7rg5Uyc":
      | displayName | Yopp Test |
      | spaceType   | SPACE     |
    And the Chat API knows space "spaces/BBBB2222":
      | displayName | Yopp Test |
      | spaceType   | SPACE     |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/1":
      | sender.email        | ada@tonotop.com               |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac which room is this?    |
      | annotations.mention | users/yopp                    |
    And the Chat API returns message "spaces/BBBB2222/messages/1":
      | sender.email        | ada@tonotop.com            |
      | thread.name         | spaces/BBBB2222/threads/T1 |
      | text                | @Isaac which room is this? |
      | annotations.mention | users/yopp                 |
    And the following model responses are queued:
      | model | type | content    |
      | echo  | text | The first. |
      | echo  | text | The other. |
    When Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/1"
    And Google Chat delivers a message event for "spaces/BBBB2222/messages/1"
    Then the session count is 2
    And session "gchat-tonotop-yopp-test" is tagged "space:AAQA7rg5Uyc"
    And session "gchat-tonotop-yopp-test-bbbb2222" is tagged "space:BBBB2222"

  Scenario: a DM the account is only invited to warns once, does not post, and diverts the reply (isaac-qry7)
    Chat 403s spaces.get and messages.create alike on a DM the account was
    invited to but never accepted - a message request pending in its Chat UI.
    The turn still runs; the reply goes to the attention comm instead, named
    for the DM and the sender.
    Given config:
      | comms.gchat.gchat/allow-from | ["cordelia@tonotop.com"] |
      | attention.notify.comm        | logbook                  |
      | attention.notify.target      | ops-room                 |
    And the Chat API knows space "spaces/INV1":
      | spaceType | DIRECT_MESSAGE |
    And the Chat API refuses spaces.get for "spaces/INV1" with 403
    And the Chat API returns message "spaces/INV1/messages/1":
      | sender.email       | cordelia@tonotop.com   |
      | sender.displayName | Cordelia               |
      | thread.name        | spaces/INV1/threads/T1 |
      | text                | are you there?         |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
    When Google Chat delivers a message event for "spaces/INV1/messages/1"
    Then the log has entries matching:
      | level | event              | space       |
      | :warn | :gchat.dm/invited  | spaces/INV1 |
    And exactly 1 log entry has event ":gchat.dm/invited"
    And 0 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/INV1/messages" were made
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                              |
      | comm    | :logbook                                           |
      | target  | ops-room                                           |
      | content | contains "spaces/INV1" and "Cordelia" and "Here."  |

  Scenario: a second message in the same invited DM does not warn again (isaac-qry7)
    Given config:
      | comms.gchat.gchat/allow-from | ["cordelia@tonotop.com"] |
    And the Chat API knows space "spaces/INV1":
      | spaceType | DIRECT_MESSAGE |
    And the Chat API refuses spaces.get for "spaces/INV1" with 403
    And the Chat API returns message "spaces/INV1/messages/1":
      | sender.email       | cordelia@tonotop.com   |
      | sender.displayName | Cordelia               |
      | thread.name        | spaces/INV1/threads/T1 |
      | text                | are you there?         |
    And the Chat API returns message "spaces/INV1/messages/2":
      | sender.email       | cordelia@tonotop.com   |
      | sender.displayName | Cordelia               |
      | thread.name        | spaces/INV1/threads/T1 |
      | text                | still there?           |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
      | echo  | text | Still.  |
    When Google Chat delivers a message event for "spaces/INV1/messages/1"
    And Google Chat delivers a message event for "spaces/INV1/messages/2"
    Then exactly 1 log entry has event ":gchat.dm/invited"

  Scenario: a mention in one thread keeps two threads' history straight, each line marked (isaac-acou)
    The session stays per space (isaac-ihuc); a mention answers the thread
    that addressed it, and every line — whichever thread it belongs to —
    carries a [thread:xx] marker so Yopp can tell them apart.
    Given the Chat API returns message "spaces/ENG/messages/40":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/TA |
      | text         | deploy started        |
    And the Chat API returns message "spaces/ENG/messages/41":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/TA |
      | text         | build is green        |
    And the Chat API returns message "spaces/ENG/messages/42":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/TB |
      | text         | anyone up for lunch?  |
    And the Chat API returns message "spaces/ENG/messages/43":
      | sender.email | ada@tonotop.com       |
      | thread.name  | spaces/ENG/threads/TB |
      | text         | tacos again?          |
    And the Chat API returns message "spaces/ENG/messages/44":
      | sender.email        | ada@tonotop.com        |
      | thread.name         | spaces/ENG/threads/TA  |
      | text                | @Isaac is it deployed? |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | Deployed. |
    When Google Chat delivers a message event for "spaces/ENG/messages/40"
    And Google Chat delivers a message event for "spaces/ENG/messages/41"
    And Google Chat delivers a message event for "spaces/ENG/messages/42"
    And Google Chat delivers a message event for "spaces/ENG/messages/43"
    And Google Chat delivers a message event for "spaces/ENG/messages/44"
    Then session "gchat-spaces-ENG" has transcript matching:
      | type    | message.role | message.content                                                                                                                                                                        |
      | message | user         | #"(?s)\[thread:TA\] ada@tonotop\.com: deploy started.*\[thread:TA\] ada@tonotop\.com: build is green.*\[thread:TB\] ada@tonotop\.com: anyone up for lunch\?.*\[thread:TB\] ada@tonotop\.com: tacos again\?.*\[thread:TA\] ada@tonotop\.com: @Isaac is it deployed\?" |
      | message | assistant    | Deployed.                                                                                                                                                                              |
    And an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | body.thread.name | spaces/ENG/threads/TA |
      | body.text        | Deployed.              |

  Scenario: the gchat guidance frames the triggered turn exactly once (isaac-acou)
    Given the following model responses are queued:
      | model | type | content |
      | echo  | text | On it.  |
    When Google Chat delivers a message event for "spaces/ENG/messages/1"
    Then the last LLM request carries the gchat thread guidance exactly once

  Scenario: an entry's explicit session overrides the canonical name (isaac-ihuc)
    Given config:
      | google.tonotop.topic                                | projects/marigold/topics/isaac |
      | comms.gchat.gchat/spaces.spaces/AAQA7rg5Uyc.session | deploy-desk                    |
    And the Chat API knows space "spaces/AAQA7rg5Uyc":
      | displayName | Yopp Test |
      | spaceType   | SPACE     |
    And the Chat API returns message "spaces/AAQA7rg5Uyc/messages/1":
      | sender.email        | ada@tonotop.com               |
      | thread.name         | spaces/AAQA7rg5Uyc/threads/T1 |
      | text                | @Isaac are you there?         |
      | annotations.mention | users/yopp                    |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Here.   |
    When Google Chat delivers a message event for "spaces/AAQA7rg5Uyc/messages/1"
    Then session "deploy-desk" has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | #".*are you there\?.*" |
      | message | assistant    | Here.                  |
    And the session count is 1

  # Inbound attachments (isaac-e2zb): a file a person attaches is downloaded under
  # the session's working directory before the turn, and the framed input
  # names it so the model can read it with the file tools.

  Scenario: an attachment on the addressing message is saved under the session working directory and the turn is told (isaac-e2zb)
    Given the crew "main" allows tools: "fs/*"
    And config:
      | comms.gchat.gchat/spaces.spaces/IA1.name | inbound-attach |
      | comms.gchat.gchat/spaces.spaces/IA1.crew | main           |
    And the Chat API returns message "spaces/IA1/messages/1":
      | sender.email                                | ada@tonotop.com                        |
      | thread.name                                 | spaces/IA1/threads/T1                  |
      | text                                        | @Isaac what is this?                   |
      | annotations.mention                         | users/yopp                             |
      | attachment.0.contentName                    | report.pdf                             |
      | attachment.0.contentType                    | application/pdf                        |
      | attachment.0.attachmentDataRef.resourceName | spaces/IA1/attachments/att-1           |
    And the Chat API serves attachment "spaces/IA1/attachments/att-1" with content "%PDF-1.4 stub"
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | A PDF, got it. |
    When Google Chat delivers a message event for "spaces/IA1/messages/1"
    Then the file "attachments/1/report.pdf" under the session working directory contains "%PDF-1.4 stub"
    And session "gchat-spaces-IA1" has transcript matching:
      | type    | message.role | message.content                                                        |
      | message | user         | #"(?s).*\[attachment: report\.pdf \(application/pdf, .*\) at attachments/1/report\.pdf\].*" |
      | message | assistant    | A PDF, got it.                                                         |

  # Chat serves attachment bytes from the media endpoint (isaac-468y). The e2zb stub
  # accepted any URL, so the wrong path went unnoticed until yopp got a 404.

  @wip
  Scenario: an attachment is downloaded from Chat's media endpoint (isaac-468y)
    Given the crew "main" allows tools: "fs/*"
    And config:
      | comms.gchat.gchat/spaces.spaces/IA2.name | media-attach |
      | comms.gchat.gchat/spaces.spaces/IA2.crew | main         |
    And the Chat API returns message "spaces/IA2/messages/1":
      | sender.email                                | ada@tonotop.com              |
      | thread.name                                 | spaces/IA2/threads/T1        |
      | text                                        | @Isaac see attached          |
      | annotations.mention                         | users/yopp                   |
      | attachment.0.contentName                    | notes.txt                    |
      | attachment.0.contentType                    | text/plain                   |
      | attachment.0.attachmentDataRef.resourceName | spaces/IA2/attachments/att-2 |
    And the Chat API serves attachment "spaces/IA2/attachments/att-2" with content "meeting notes"
    And the following model responses are queued:
      | model | type | content   |
      | echo  | text | Read them. |
    When Google Chat delivers a message event for "spaces/IA2/messages/1"
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/media/spaces/IA2/attachments/att-2" were made
    And the file "attachments/1/notes.txt" under the session working directory contains "meeting notes"

  # Waiting room + consolidation (isaac-xoqn): a session mid-turn does not refuse the
  # next messages; prompts in one thread are answered together.

  Scenario: three quick messages in one DM thread get one consolidated reply (isaac-xoqn)
    Given config:
      | comms.gchat.gchat/spaces.spaces/DMQ.name | dm-queue |
      | comms.gchat.gchat/spaces.spaces/DMQ.crew | main     |
    And session "gchat-spaces-DMQ" is in flight
    And the Chat API returns message "spaces/DMQ/messages/1":
      | sender.email        | ada@tonotop.com        |
      | thread.name         | spaces/DMQ/threads/T1  |
      | text                | @Isaac first           |
      | annotations.mention | users/yopp             |
    And the Chat API returns message "spaces/DMQ/messages/2":
      | sender.email        | ada@tonotop.com        |
      | thread.name         | spaces/DMQ/threads/T1  |
      | text                | @Isaac second          |
      | annotations.mention | users/yopp             |
    And the Chat API returns message "spaces/DMQ/messages/3":
      | sender.email        | ada@tonotop.com        |
      | thread.name         | spaces/DMQ/threads/T1  |
      | text                | @Isaac third           |
      | annotations.mention | users/yopp             |
    And the following model responses are queued:
      | model | type | content              |
      | echo  | text | All three, answered. |
    When Google Chat delivers a message event for "spaces/DMQ/messages/1"
    And Google Chat delivers a message event for "spaces/DMQ/messages/2"
    And Google Chat delivers a message event for "spaces/DMQ/messages/3"
    And the in-flight turn on session "gchat-spaces-DMQ" ends
    Then 1 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces/DMQ/messages" were made
    And session "gchat-spaces-DMQ" has transcript matching:
      | type    | message.role | message.content                             |
      | message | user         | #"(?s).*first.*second.*third.*"             |
      | message | assistant    | All three, answered.                        |
    And the log has entries matching:
      | level | event           | session          | count |
      | :info | :turn/coalesced | gchat-spaces-DMQ | 3     |
