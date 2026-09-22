Feature: Google Chat registrations and renewal
  One Workspace Events subscription per configured space, pointer-only,
  against the shared topic. A timer component creates what is missing,
  renews what is near expiry, deletes what left config, and reads expiry
  from Google rather than trusting local state. The registration itself is
  contributed to isaac-google's :isaac.google/registration berth; the timer
  lives in isaac-google. Bean: isaac-vo2q.

  Background:
    Given default Grover setup in "/test/gchat-registrations"
    And config:
      | log.output                                | memory                         |
      | google.tonotop.topic                      | projects/marigold/topics/isaac |
      | google.tonotop.renew-within-hours         | 24                             |
      | comms.gchat.gchat/account                 | yopp@tonotop.com               |
      | comms.gchat.gchat/spaces.spaces/ENG.name  | engineering                    |
      | comms.gchat.gchat/spaces.spaces/PROD.name | product                        |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: the first tick subscribes every configured space, pointer-only, to the shared topic
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | #index                                | 0                                        |
      | method                                | POST                                     |
      | headers.Authorization                 | Bearer at-1                              |
      | body.targetResource                   | //chat.googleapis.com/spaces/ENG         |
      | body.eventTypes.0                     | google.workspace.chat.message.v1.created |
      | body.notificationEndpoint.pubsubTopic | projects/marigold/topics/isaac           |
      | body.payloadOptions.includeResource   | false                                    |
    And an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | #index              | 1                                 |
      | body.targetResource | //chat.googleapis.com/spaces/PROD |
    And the log has entries matching:
      | level | event              | key         | expires-at           |
      | :info | :google/registered | spaces/ENG  | 2026-09-25T12:00:00Z |
      | :info | :google/registered | spaces/PROD | 2026-09-25T12:00:00Z |

  Scenario: a subscription inside the renew window is renewed and its new expiry read back
    Given the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-19T06:00:00Z"
    And the Workspace Events API has subscription "subscriptions/s-prod" for "spaces/PROD" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-eng" matches:
      | method           | PATCH   |
      | query.updateMask | ttl     |
      | body.ttl         | 604800s |
    And the log has entries matching:
      | level | event           | key        | expires-at           |
      | :info | :google/renewed | spaces/ENG | 2026-09-25T12:00:00Z |
    And no outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-prod" was made

  Scenario: a space removed from config is unsubscribed
    Given the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API has subscription "subscriptions/s-prod" for "spaces/PROD" expiring at "2026-09-24T12:00:00Z"
    And config:
      | comms.gchat.gchat/spaces.spaces/PROD | #delete |
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-prod" matches:
      | method | DELETE |
    And the log has entries matching:
      | level | event                | key         |
      | :info | :google/unregistered | spaces/PROD |

  Scenario: a refused create is logged with Google's reason and retried on the next tick
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API rejects creates for "spaces/ENG" with 403 "The caller does not have permission"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | level  | event                       | key         | reason                          | expires-at           |
      | :error | :google/registration-failed | spaces/ENG  | #".*does not have permission.*" |                      |
      | :info  | :google/registered          | spaces/PROD |                                 | 2026-09-25T12:00:00Z |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then 2 outbound HTTP requests to "https://workspaceevents.googleapis.com/v1/subscriptions" for "spaces/ENG" were made

  Scenario: with discovery on, the first tick subscribes every space the account belongs to (isaac-xy2i)
    Given config:
      | comms.gchat.gchat/discover | true |
    And the Chat API lists the account's spaces:
      | name               | displayName | spaceType      |
      | spaces/ENG         | Engineering | SPACE          |
      | spaces/AAQA7rg5Uyc | Yopp Test   | SPACE          |
      | spaces/DM1         |             | DIRECT_MESSAGE |
    And the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces" matches:
      | method                | GET                                                 |
      | headers.Authorization | Bearer at-1                                         |
      | query.filter          | spaceType = "SPACE" OR spaceType = "DIRECT_MESSAGE" |
    And the log has entries matching:
      | level | event              | key                | expires-at           |
      | :info | :google/registered | spaces/AAQA7rg5Uyc | 2026-09-25T12:00:00Z |
      | :info | :google/registered | spaces/DM1         | 2026-09-25T12:00:00Z |
      | :info | :google/registered | spaces/PROD        | 2026-09-25T12:00:00Z |

  Scenario: a space the account has left is no longer listed and is unsubscribed (isaac-xy2i)
    Given config:
      | comms.gchat.gchat/discover | true |
    And the Chat API lists the account's spaces:
      | name       | displayName | spaceType |
      | spaces/ENG | Engineering | SPACE     |
    And the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API has subscription "subscriptions/s-yopp" for "spaces/AAQA7rg5Uyc" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-yopp" matches:
      | method | DELETE |
    And the log has entries matching:
      | level | event                | key                |
      | :info | :google/unregistered | spaces/AAQA7rg5Uyc |

  Scenario: discovery asks Chat once per interval, however often the timer ticks (isaac-xy2i)
    The registration timer ticks every 30 seconds and membership does not, so
    a listing stands for gchat/discover-every-ms and every tick inside it is
    answered from that listing — including the keys it subscribes, which must
    not vanish and take their subscriptions with them.
    Given config:
      | comms.gchat.gchat/discover          | true   |
      | comms.gchat.gchat/discover-every-ms | 300000 |
    And the Chat API lists the account's spaces:
      | name               | displayName | spaceType |
      | spaces/AAQA7rg5Uyc | Yopp Test   | SPACE     |
    And the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    And the test clock advances 30000 milliseconds
    And the google registration timer ticks
    Then 1 outbound HTTP request to "https://chat.googleapis.com/v1/spaces" was made
    And no outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-AAQA7rg5Uyc" was made
    When the test clock advances 300000 milliseconds
    And the google registration timer ticks
    Then 2 outbound HTTP requests to "https://chat.googleapis.com/v1/spaces" were made
