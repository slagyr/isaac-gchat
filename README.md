# 🍏 Isaac GChat 💬

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-gchat/main/isaac-gchat.png" alt="isaac-gchat" style="margin-right: 20px; margin-bottom: 10px;">

Google Chat comm for [Isaac](https://github.com/slagyr/isaac) — inbound gate,
routing and dispatch for spaces and DMs; outbound replies and sends. Built on
[isaac-google](https://github.com/slagyr/isaac-google).

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes the
`:isaac.comm.gchat` comm. Part of the Google Workspace comms epic (isaac-bv1l).

<br>

[![GChat](https://github.com/slagyr/isaac-gchat/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-gchat/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Module (`isaac.comm.gchat.module/create-module`), manifest id `:isaac.comm.gchat`.
- Inbound allowlist, space routing, and `POST` replies/sends on Chat's 4096 cap.
- Further work is planned in the beans under isaac-bv1l.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-google/
  isaac-gchat/   # this repo
```

```sh
bb hooks:install   # once, on a fresh checkout
bb spec
bb features
bb ci
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-gchat {:local/root "../isaac-gchat"}
;; or {:git/url "https://github.com/slagyr/isaac-gchat.git" :git/sha "..."}
```
