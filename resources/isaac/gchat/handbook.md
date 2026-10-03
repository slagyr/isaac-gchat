# isaac.comm.gchat — Google Chat

You are a crew running inside Isaac. This chapter covers what
**isaac-gchat** owns: a `gchat`-type entry in the `comms` table, the
inbound gate that decides which Chat messages start a turn, outbound
replies and sends, and two read-only Chat tools. It is built on top of
**isaac.google** (OAuth, the Pub/Sub push door, registration renewal) —
read that module's chapter for how a Google organization's credentials,
project, and topic are set up; this chapter only covers the Chat-specific
slice on top of it. It uses "crew", "session", "frequencies", and
`comm__send` the way `isaac.agent` defines them (`isaac.agent#frequencies`
for the frequencies shape and matching rules) — read that chapter first if
you haven't; this one names them once and moves on. Config mechanics
(`handbook__configure`, hot reload, `${VAR}` secrets) are `isaac.foundation`'s.

A `gchat` comm has no CLI commands of its own — everything here is a
config path under that comm's entry in `comms.<name>.*`, or a
`google.<organization>.*` entry owned by isaac.google.

## The comm entry

**What it is.** A Google Chat comm is one entry in the `comms` table with
`type` `gchat` (the conventional name is `comms.gchat`, any name works as
long as `type` is set). Its fields:

| Field | Type | Purpose |
|---|---|---|
| `gchat/google` | keyword | Which Google organization (isaac.google tenant) this comm speaks for — sends with that organization's token, and its Chat subscription rides that organization's topic. Omit on a host with a single organization. |
| `gchat/account` | string | The Isaac Google account's email, used to recognize and drop Isaac's own messages coming back through the inbound gate. |
| `gchat/account-id` | string | The account's `users/<id>` Chat resource name. Chat's own push events carry `users/<id>` and never an email for the sender, so `gchat/account` alone cannot recognize Isaac's own replies bouncing back — this closes that gap. Learned automatically from the first outbound send if left unset. |
| `gchat/allow-from` | seq of strings | Inbound sender allowlist — see Inbound: the gate, below. Missing or empty is fail-closed: nothing routes. |
| `gchat/message-cap` | int | Character count above which a reply is split into multiple messages. Chat's own limit is 4096; that's also the default. |
| `gchat/reactions` | boolean or map | Progress emoji on the triggering message — see Progress reactions, below. |
| `gchat/spaces` | map | Per-space overrides — see Spaces, sessions, and routing, below. |

**How to change it.** Set fields with `handbook__configure` (or
`isaac config set`):

```
config set comms.gchat.gchat/account yopp@example.com
config set comms.gchat.gchat/allow-from '["*@example.com"]'
config set comms.gchat.gchat/message-cap 2000
```

A second Chat comm for a second organization is a second `comms` entry
with its own `type gchat` and its own `gchat/google` value; see isaac.google
for what an organization itself needs (project, topic, OAuth client).

**How to verify.** `config get comms.gchat` (or `config get
comms.<name>`) shows the resolved entry; `isaac config validate` reports
an unknown or mistyped field by name.

### Troubleshooting

- **A comm's field doesn't seem to apply.** Confirm the entry's `type` is
  actually `gchat` — an entry with a typo'd type is a different (or no)
  comm kind and this schema never applies to it.
- **Isaac starts replying to its own messages.** Set `gchat/account-id`
  explicitly rather than waiting for it to be learned from a first send —
  useful right after a restart, before any outbound message has taught
  Isaac its own `users/<id>` for this organization.
- **Two Chat comms on the same host seem to cross-talk.** Check each
  entry's `gchat/google` — a comm with none defaults to the host's single
  configured organization; on a host with more than one, name each comm's
  organization explicitly.

## Inbound: the gate

**What it is.** Every Chat event for a space the account belongs to
arrives through the Pub/Sub door (isaac.google's one line, below) and
passes through a deterministic gate, in order:

1. **Self.** A message from the configured account (by email, or by
   `gchat/account-id`/learned id) is dropped — never routed, never logged
   above debug.
2. **Allowlist.** `gchat/allow-from` entries admit a sender three ways: an
   exact email, `*@domain` for every address in a domain, a bare
   `users/<id>` resource name, or `domain:<domainId>` (the Workspace
   customer id Chat reports on the sender). Chat's message API does not
   return an email for a human sender under user auth, so `users/<id>` or
   `domain:<id>` is usually what actually matches. A sender matching
   nothing is dropped and logged at `:info` with the identity Chat gave —
   that log line is meant to be copied straight into the allowlist.
3. **Respond policy.** Each space resolves to `mentions` (the default for
   a named space), `all` (the default for a DM), or `never` — see Spaces,
   below, for setting it per space. A message that doesn't meet its
   space's policy (not a mention, in a `mentions` space) is still heard
   and appended to that space's transcript, just not turned into a turn —
   the next mention picks that context back up.

A mention is detected by a Chat `@`-annotation naming the account's own
`users/<id>` (or, before that id is known, by asking Google who the
mentioned id is) — a message that `@`-mentions someone else is not a
mention of the account, even in a space Isaac otherwise answers in.

**How to change it.** `gchat/allow-from` and each space's `respond`
policy are the two levers:

```
config set comms.gchat.gchat/allow-from '["ada@example.com", "*@example.com", "domain:C0examp1e"]'
config set comms.gchat.gchat/spaces.spaces/ENG.respond all
```

**How to verify.** `isaac logs server` (or `cli`) shows `:gchat/message-routed`
for anything that started a turn, `:gchat/message-logged` (debug) for a
heard-not-answered message, and `:gchat/message-dropped` — `:info` with
reason `:sender` (an unknown sender; the log names the identity to copy
into the allowlist), or `:debug` for `:self`/`:policy`.

### Troubleshooting

- **A sender the allowlist should admit is still dropped.** Chat often
  gives only `users/<id>` and a `domainId`, never an email, for a human
  sender — check the `:gchat/message-dropped` log's `sender` fields for
  what Chat actually sent, and allow that exact `users/<id>` or
  `domain:<id>` rather than assuming the email form will match.
- **A message in a space is heard but never answered.** Check that space's
  `respond` policy and whether the message actually `@`-mentions the
  account — a plain message in a `mentions` (the default) space is
  deliberately not a turn.
- **The bot answers a message meant for someone else.** A message that
  `@`-mentions a different user, even alongside other text, is not treated
  as addressed to the account — if it's still starting turns, check
  whether that space's policy is `all` rather than `mentions`.

- **An attachment landed but is not a valid image.** Older inbound media downloads decoded binary as UTF-8 text, replacing invalid bytes (for example PNG byte `89`) with `EF BF BD`. This is file corruption, not a missing download; ask the sender to resend after upgrading the gchat module.

## Spaces, sessions, and routing

**What it is.** A space **is** a conversation: every space the account
belongs to routes, with no config at all, to its own canonical session —
named from the Google organization plus the space's Chat display name
(or `dm-<person>` for a direct message). Belonging to the space is the
grant: the one Pub/Sub subscription hears every space the account is in
(see Pub/Sub and registration, below), so an unlisted space is heard,
never silently skipped. A `gchat/spaces` entry is an **override** on top
of that default, keyed by the space's resource name:

| Field | Type | Purpose |
|---|---|---|
| `name` | string | Friendly display name (documentation only). |
| `session` | string | Pin this space to one explicit session id, instead of the canonical per-space name. |
| `session-tags` | seq of keywords | Route to whichever session carries every listed tag (`isaac.agent#frequencies` matching). |
| `crew` | string | Crew this space's session runs (or is created) on. |
| `prefer` | keyword | `recent` or `oldest` — tiebreak when `session-tags` matches more than one session. |
| `create` | keyword | `never`, `if-missing`, or `always`. |
| `respond` | keyword | `mentions` (default for a named space), `all` (default for a DM), or `never`. |

A space's session is retargeted with `config set
comms.gchat.gchat/spaces.<resource>.<field> <value>` — `<resource>` is the
space's resource name as a config path segment, e.g.
`spaces.spaces/ENG.crew`. The canonical id itself is stable across a
Chat-side rename: the session is tagged with the space's own id, so
renaming a space in Chat renames its session (keeping history) rather than
starting a new one; two spaces that happen to share a display name still
get two distinct sessions.

**How to change it.**

```
config set comms.gchat.gchat/spaces.spaces/ENG.crew cordelia
config set comms.gchat.gchat/spaces.spaces/ENG.respond all
config set comms.gchat.gchat/spaces.spaces/ENG.session-tags.role/ops
```

**How to verify.** `isaac sessions list` shows the session a space landed
on, named `gchat-<org>-<space-or-dm>` unless an entry pins `session`
explicitly; `isaac logs server` logs `:gchat/session-renamed` when a
Chat-side rename follows through to the session.

### Troubleshooting

- **A space's messages land on an unexpected session.** Check for a
  `session` or `session-tags` override on that space's `gchat/spaces`
  entry — without one, the canonical per-space name is used, derived from
  the space's current Chat display name, which can drift after a rename.
- **Two differently-named spaces seem to share one session.** The
  canonical name collides only on display name, not the underlying space
  id — a session already carrying another space's tag forces the newer
  space's session name to disambiguate with the space id appended.

## Pub/Sub and registration

**What it is.** Inbound delivery itself is `isaac.google`'s: Chat events
arrive as Pub/Sub push notifications through isaac.google's HTTP door,
resolved to one organization, and handed to this module's contributed
`:isaac.google/handler` entries for the message and membership event
types. Read `isaac.google`'s own chapter for the push door, OAuth, and
per-organization project/topic setup — this module adds only the handler
functions and one subscription request: a single subscription on the
special target `spaces/-`, meaning "every space this account belongs to."
As the account is invited to more spaces, that one subscription keeps
delivering them, so `gchat/spaces` entries are never subscriptions, only
overrides on a space already heard. isaac.google's shared registration
timer creates and renews it per organization, using that organization's
token and topic.

**How to change it.** There's no gchat-specific config for the
subscription itself — an organization's `google.<id>.renew-within-hours`
(isaac.google) governs renewal timing for every registration on that
organization, Chat's included.

**How to verify.** `isaac logs server` shows `:google/registered` with key
`spaces/-` when a subscription is created or renewed for an organization.
A missing or expired subscription means no Chat events arrive at all for
that organization, regardless of `gchat/allow-from` or `gchat/spaces` —
check the registration log before assuming a gate or routing problem.

### Troubleshooting

- **No Chat messages are arriving for an organization at all.** Check
  `isaac logs server` for `:google/registered spaces/-` on that
  organization — a missing registration (bad project/topic, expired
  auth) means nothing about the gate or spaces config matters yet.
- **A newly-invited space's messages don't show up.** The `spaces/-`
  subscription already covers it automatically — there is nothing to
  register per space. If messages still don't arrive, suspect the
  underlying subscription or the account's Chat membership itself, not
  gchat config.

## Outbound: replies and sends

**What it is.** A turn's reply posts back into the thread and space it
came from, as the Google account. A message initiated by the agent (not a
reply) goes through the shared `comm__send` tool (`isaac.agent`'s), which
this comm accepts fields for:

| `send-schema` field | Type | Purpose |
|---|---|---|
| `gchat/space` | string | A configured space's `name`, or its raw resource name (`spaces/…`). |
| `gchat/to` | string | A person's email — resolves (or creates) a direct-message space with them. |
| `gchat/thread` | string | Optional thread resource (`spaces/…/threads/…`) to reply into. |

A reply longer than `gchat/message-cap` characters (4096 default, Chat's
own limit) is split at newline boundaries into several messages, posted
in order. `send-attachments?` is on for this comm: a file path passed to
`comm__send` is uploaded to Chat first, and the message references the
upload.

**How to change it.** These are call-time fields on `comm__send`, not
persistent config — nothing to set with `handbook__configure` here beyond
the comm entry's own fields above (`gchat/message-cap`, `gchat/account`).

**How to verify.** `isaac logs server` shows `:gchat/delivery-failed` with
the space, thread, and Chat's own status/reason for a reply that Chat
refused to post (see Errors and diverted DMs, below, for the special case
of an unaccepted DM invite).

### Troubleshooting

- **`comm__send` with `gchat/to` doesn't reach the person.** Confirm the
  email is a real Chat user Google can resolve to a direct-message space —
  a DM that doesn't exist yet is created automatically, but an unresolvable
  address fails the send outright.
- **A long reply arrives as several separate messages.** That's
  `gchat/message-cap` splitting, not a bug — raise the cap (up to Chat's
  own 4096 ceiling) if the split itself is the problem.
- **An attachment doesn't show up on the sent message.** Only a path
  already inside the turn's allowed directories is eligible for upload
  (`isaac.agent`'s tool/directory grants) — a path outside those grants
  is refused before `comm__send` ever reaches this comm.

## Progress reactions

**What it is.** `gchat/reactions` puts emoji on the triggering Chat
message to narrate a turn's progress instead of posting status text: 👀
while working, ✅ once answered, ⚠️ on a hard error, ⏳ while parked on
provider weather (cleared to ✅ once the resumed turn actually answers);
🧠 / 🔧 / 💬 each accumulate once per turn, on the first reasoning chunk,
tool call, and `comm__send` aside respectively, and are never removed.
Reactions never notify the sender by themselves — they're a silent,
glanceable status.

**How to change it.** `gchat/reactions false` turns the whole lifecycle
off; a map overrides individual keys (`:working`, `:done`, `:failed`,
`:parked`, `:thinking`, `:tool`, `:aside`) — a key set to `false` skips
just that one kind:

```
config set comms.gchat.gchat/reactions false
config set comms.gchat.gchat/reactions.tool false
```

**How to verify.** Send a mention and watch the triggering message in
Chat directly — there's no log line per reaction add/remove (failures log
once at `:debug` and are never retried, by design, so a reaction glitch
never becomes a turn error).

### Troubleshooting

- **Reactions never appear at all.** Confirm `gchat/reactions` isn't set
  to `false`, and that the Chat account actually has permission to react
  in that space — a reaction failure is swallowed at `:debug` and never
  surfaces as a turn error, so check the debug stream, not the reply
  itself, for `:gchat.reaction/failed`.
- **The working (👀) reaction seems "stuck" instead of turning into ✅.**
  If the turn parked on provider weather, ⏳ is deliberately kept until the
  turn that eventually answers — that's not a bug; see Errors and diverted
  DMs, below.

## Errors, weather, and diverted DMs

**What it is.** A turn's outcome always gets an in-thread signal, never a
silent failure: a hard error posts a short notice naming the failure's
coarse class (provider error, tool failure, or delivery failure — never a
raw exception message or provider payload); provider weather (a wall, an
auth wait, a stalled stream) posts one notice with the reason and, when
known, a local-clock retry time, and stays quiet on repeated weather for
the same still-parked turn. Neither notice is sent if the reply itself
cannot be posted — there's no thread left to notify when posting the
reply is exactly what just failed.

A **direct message the account is only invited to but has never accepted**
is Chat's own "pending request" state. **gchat does not auto-accept Chat
invitations** — no Chat API accepts one programmatically (a prior attempt
using the membership-write scope to do this was dropped after Chat
answered it with a 400 on a human DM). Chat refuses both reading and
posting to that space with a 403. The turn still runs — the account can
already see the DM's messages via the same Pub/Sub delivery — but the
reply cannot go back into that DM, so it is diverted instead: prefixed
with a line naming the DM and the sender, and enqueued to the operator's
attention comm (`attention.notify.comm` / `attention.notify.target`,
`isaac.foundation`'s config path). With no attention comm configured, the
reply is logged and dropped rather than lost silently — but never posted.
The warning that a DM is pending fires once per space, not once per
message, so a chatty pending DM doesn't flood the log.

**How to change it.** There's no gchat-specific config for error/weather
notice text. Point diverted replies somewhere by setting the shared
attention target:

```
config set attention.notify.comm logbook
config set attention.notify.target ops-room
```

**How to verify.** `isaac logs server` shows `:gchat.dm/invited` (warn,
once per space) for a pending DM, `:gchat/delivery-failed` for any other
reply Chat refuses, and `:gchat/turn-notice` once the turn ends, naming
the failure class. An operator invited-but-not-accepted DM is fixed by
opening Chat as that account and accepting the request directly (or, on a
Workspace, turning on the admin setting that lets Chat invitations
auto-accept at the Google side — outside Isaac entirely).

### Troubleshooting

- **A DM's replies never arrive, and the log shows `:gchat.dm/invited`.**
  This is Chat's own pending-invite state, not an Isaac bug — accept the
  Chat request as the account (the log's `uri` links straight to it), or
  enable Workspace-side Chat-invitation auto-accept; gchat itself has no
  way to accept it.
- **A diverted DM reply never shows up anywhere.** Check
  `attention.notify.comm`/`attention.notify.target` are actually set — an
  unconfigured attention target logs the divert and drops the content
  rather than queuing it.
- **A turn ends with no in-thread notice at all, and it isn't a diverted
  DM.** A reply that fails to post has nowhere to carry a notice — check
  `:gchat/delivery-failed` for what Chat actually refused, since the
  in-thread notice path assumes posting itself still works.

## Chat tools

**What it is.** Two read-only tools, gated by the crew's own tool
allow/deny list (`isaac.agent`), let a crew look beyond what a mention
already carries in its framed context: `gchat__spaces` lists every space
and DM the account belongs to; `gchat__history` reads what was said in
one space (optionally one thread, or only messages after a given time).
Both run on Isaac's own Chat token, the same one the comm sends with —
never a crew's separate credential. There is no send tool here:
`comm__send` (isaac.agent's) is the one way a crew sends a Chat message
of its own.

**How to change it.** Nothing to configure beyond the crew's own tool
allow/deny (`isaac.agent`'s `tools.allow`/`tools.deny`, family `gchat__*`).

**How to verify.** Ask a crew with the tools allowed to list its Chat
spaces or read a space's history and check the result against `gchat__spaces`'/
`gchat__history`'s tool output.

### Troubleshooting

- **A crew can't call `gchat__spaces`/`gchat__history` at all.** Check the
  crew's `tools.allow`/`tools.deny` for the `gchat__*` family —
  `isaac.agent`'s chapter covers the allow/deny cascade.
- **`gchat__history` returns nothing for a space the crew names.** Confirm
  `space` is the full `spaces/<id>` resource name, not a friendly
  `gchat/spaces` name — unlike `comm__send`'s `gchat/space`, this tool
  takes only Chat's own resource name.
