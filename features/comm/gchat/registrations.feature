Feature: Google Chat registrations and renewal
  One Workspace Events subscription per Google organization, on
  //chat.googleapis.com/spaces/- — "all spaces for a user" — pointer-only,
  against that organization's topic. It carries every space the account
  belongs to, named spaces, unnamed group chats and DMs alike, so a
  gchat/spaces entry subscribes nothing at all. A timer component creates
  what is missing, renews what is near expiry, deletes what is no longer
  configured, and reads expiry from Google rather than trusting local state.
  The registration itself is contributed to isaac-google's
  :isaac.google/registration berth; the timer lives in isaac-google.
  Beans: isaac-vo2q, isaac-ihuc.

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

  Scenario: the first tick subscribes spaces/- and nothing else (isaac-ihuc)
    Two spaces are configured and neither subscribes: the one subscription
    already hears them, and every space nobody listed besides.
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | #index                                | 0                                        |
      | method                                | POST                                     |
      | headers.Authorization                 | Bearer at-1                              |
      | body.targetResource                   | //chat.googleapis.com/spaces/-           |
      | body.eventTypes.0                     | google.workspace.chat.message.v1.created |
      | body.notificationEndpoint.pubsubTopic | projects/marigold/topics/isaac           |
      | body.payloadOptions.includeResource   | false                                    |
    And 1 outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" was made
    And the log has entries matching:
      | level | event              | key      | expires-at           |
      | :info | :google/registered | spaces/- | 2026-09-25T12:00:00Z |

  Scenario: the one subscription carries messages and memberships (isaac-ihuc)
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | body.eventTypes.1 | google.workspace.chat.message.v1.updated    |
      | body.eventTypes.2 | google.workspace.chat.message.v1.deleted    |
      | body.eventTypes.3 | google.workspace.chat.membership.v1.created |
      | body.eventTypes.4 | google.workspace.chat.membership.v1.updated |
      | body.eventTypes.5 | google.workspace.chat.membership.v1.deleted |

  Scenario: a subscription inside the renew window is renewed and its new expiry read back
    Given the Workspace Events API has subscription "subscriptions/s-all" for "spaces/-" expiring at "2026-09-19T06:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-all" matches:
      | method           | PATCH   |
      | query.updateMask | ttl     |
      | body.ttl         | 604800s |
    And the log has entries matching:
      | level | event           | key      | expires-at           |
      | :info | :google/renewed | spaces/- | 2026-09-25T12:00:00Z |

  Scenario: a subscription outside the renew window is left alone
    Given the Workspace Events API has subscription "subscriptions/s-all" for "spaces/-" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then no outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-all" was made

  Scenario: per-space subscriptions left over from the old scheme are unsubscribed (isaac-ihuc)
    Given the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API has subscription "subscriptions/s-prod" for "spaces/PROD" expiring at "2026-09-24T12:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-eng" matches:
      | method | DELETE |
    And an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions/s-prod" matches:
      | method | DELETE |
    And the log has entries matching:
      | level | event                | key         |
      | :info | :google/registered   | spaces/-    |
      | :info | :google/unregistered | spaces/ENG  |
      | :info | :google/unregistered | spaces/PROD |

  Scenario: a refused create is logged with Google's reason and retried on the next tick
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API rejects creates for "spaces/-" with 403 "The caller does not have permission"
    When the google registration timer ticks
    Then the log has entries matching:
      | level  | event                       | key      | reason                          |
      | :error | :google/registration-failed | spaces/- | #".*does not have permission.*" |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then 2 outbound HTTP requests to "https://workspaceevents.googleapis.com/v1/subscriptions" for "spaces/-" were made
