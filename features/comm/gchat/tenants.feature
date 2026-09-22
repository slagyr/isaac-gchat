Feature: Google Chat across several Google organizations
  One Isaac can carry several Google organizations (isaac-1zkz). A Chat comm
  names the one it speaks for with `gchat/google`: it posts as that organization's
  Google user, with that organization's token, and its one spaces/-
  subscription rides that organization's own topic in that organization's
  project. A host with one organization names none and nothing about it
  changes. Beans: isaac-1zkz, isaac-ihuc.

  Background:
    Given default Grover setup in "/test/gchat-tenants"
    And config:
      | log.output                                     | memory                          |
      | google.tonotop.project                         | marigold                        |
      | google.tonotop.topic                           | projects/marigold/topics/isaac  |
      | google.acme.project                            | acme-prod                       |
      | google.acme.topic                              | projects/acme-prod/topics/isaac |
      | comms.gchat.gchat/google                             | tonotop                         |
      | comms.gchat.gchat/account                      | yopp@tonotop.com                |
      | comms.gchat.gchat/spaces.spaces/ENG.name       | engineering                     |
      | comms.gchat-acme.type                          | gchat                           |
      | comms.gchat-acme.gchat/google                        | acme                            |
      | comms.gchat-acme.gchat/account                 | isaac@acme.example              |
      | comms.gchat-acme.gchat/spaces.spaces/ACME.name | acme-eng                        |
    And the google auth store for organization "tonotop" has access "at-tonotop" and refresh "rt-tonotop"
    And the google auth store for organization "acme" has access "at-acme" and refresh "rt-acme"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: a comm bound to an organization sends with that organization's token
    Given gchat comm "gchat-acme" is registered
    When gchat comm send! is invoked with:
      | path        | value      |
      | gchat/space | acme-eng   |
      | content     | Red alert! |
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ACME/messages" matches:
      | method                | POST           |
      | headers.Authorization | Bearer at-acme |
      | body.text             | Red alert!     |
    Given gchat comm "gchat" is registered
    When gchat comm send! is invoked with:
      | path        | value       |
      | gchat/space | engineering |
      | content     | All clear.  |
    Then an outbound HTTP request to "https://chat.googleapis.com/v1/spaces/ENG/messages" matches:
      | method                | POST              |
      | headers.Authorization | Bearer at-tonotop |
      | body.text             | All clear.        |

  Scenario: each organization gets its own spaces/- subscription on its own topic
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    When the google registration timer ticks
    Then an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | #index                                | 0                               |
      | method                                | POST                            |
      | headers.Authorization                 | Bearer at-acme                  |
      | body.targetResource                   | //chat.googleapis.com/spaces/-  |
      | body.notificationEndpoint.pubsubTopic | projects/acme-prod/topics/isaac |
    And an outbound HTTP request to "https://workspaceevents.googleapis.com/v1/subscriptions" matches:
      | #index                                | 1                              |
      | method                                | POST                           |
      | headers.Authorization                 | Bearer at-tonotop              |
      | body.targetResource                   | //chat.googleapis.com/spaces/- |
      | body.notificationEndpoint.pubsubTopic | projects/marigold/topics/isaac |
    And 2 outbound HTTP requests to "https://workspaceevents.googleapis.com/v1/subscriptions" were made
    And the log has entries matching:
      | level | event              | key      |
      | :info | :google/registered | spaces/- |
