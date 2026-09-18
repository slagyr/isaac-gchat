@wip
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
      | google.topic                              | projects/marigold/topics/isaac |
      | google.renew-within-hours                 | 24                             |
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
      | comms.gchat.gchat/spaces.spaces/PROD | #remove |
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
      | level  | event                       | key        | reason                                   |
      | :error | :google/registration-failed | spaces/ENG | #".*does not have permission.*"          |
      | :info  | :google/registered          | spaces/PROD | 2026-09-25T12:00:00Z                    |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then 2 outbound HTTP requests to "https://workspaceevents.googleapis.com/v1/subscriptions" for "spaces/ENG" were made
