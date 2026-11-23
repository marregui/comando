# 194. a phone answers cards and prompts sessions through a home-screen page comando serves over Tailscale

| Date | Status | Decided by |
|---|---|---|
| 2026-11-23 | proposed | Miguel and Claude |

## 1. Context

Miguel, 2026-11-23: a simplified app to "receive notifications and send commands and approvals … when I do not have
access to the computer, because I am taking a walk". A native iOS app needs the Apple Developer Program (a yearly
fee) for push notifications, and he would pay it only against sales. iOS 16.4 and later give a page added to the home
screen web push of its own, with no developer account; that is the route this record takes.

His choices (2026-11-23): the phone answers cards and prompts sessions, and does not start or stop them; Face ID before
a destructive answer; main's sessions only; the record goes straight into this folder.

Facts at `0c7f8aad` (verified in source):

| # | Fact | Where |
|---|---|---|
| F1 | comando already serves HTTP: the MCP server, the JDK's `HttpServer` on `127.0.0.1`, which refuses a request whose `Origin` names another site | `McpServer` |
| F2 | a card is an `Approval` of one of six kinds: `TOOL`, `QUESTION`, `PLAN`, `SQL_WRITE`, `CONNECTION`, `TERMINAL` | `ApprovalKind`, `Approval` |
| F3 | every answer, the window's and `approval_answer`'s, is one `Intent.ApprovalAnswered(id, decision)`; a decision is `Allow(scope)`, `Deny(message)` or `Answer(answers)` | `ActionTools.approvalAnswer` |
| F4 | a card waits 24 h for its answer, so a walk of any length finds it still pending | `App.APPROVAL_TIMEOUT` |
| F5 | in a session in bypass, comando answers Bash, Edit, Write, MultiEdit and NotebookEdit cards itself; a removal (`rm`, `rmdir` as a word), an MCP tool, a question, a plan and comando's own cards still reach the user | [record 164](0164-comando-allows-claude-codes-own-cards-in-bypass.md), `McpTools.REMOVES` |
| F6 | no agent answers a `CONNECTION` or `TERMINAL` card, nor a card of its own creation family: those are the user's | [record 121](0121-an-agent-has-every-action-the-user-has.md) rule 1, [record 23](0023-sql-from-claude-lexical-policy-production-refused.md), [record 148](0148-agents-drive-a-panel-terminal.md) |
| F7 | frontear has no WebSocket listener, so a browser cannot reach it | [frontear](../frontear.md) |
| F8 | machines pair over mutual TLS ([record 189](0189-frontear-across-machines.md)); a machine is a comando instance, which a phone is not | record 189 |
| F9 | this Mac runs Tailscale, its tailnet has MagicDNS and HTTPS certificates (`tail251ad.ts.net`) | `tailscale status`, 2026-11-23 |

## 2. Decision

### 2.1 Shape

| Question | Rule | Why |
|---|---|---|
| the app | a page comando serves, which the phone adds to its home screen: HTML, one script, one style sheet, a web app manifest and a service worker, from comando's resources; no framework | no developer account, no Swift, no store review; the page is versioned with comando |
| which instance | main only; the page is off in every other instance | his choice; other instances are reached through main's sessions, as today |
| off by default | the page server starts only when he turns on **Phone** in the settings | a fresh install serves nothing new |

### 2.2 Network

| Question | Rule | Why |
|---|---|---|
| the server | its own `HttpServer` on `127.0.0.1`, on a port kept in the settings (default 18899), separate from the MCP server | the MCP server's callers are local sessions; the phone is the user. Separate ports keep the two trusts apart, and expose only the page |
| reaching it | `tailscale serve --bg --https=443 http://127.0.0.1:18899`, which comando prints when Phone is turned on; comando never binds a non-loopback address | Tailscale gives the page a trusted certificate for `<mac>.<tailnet>.ts.net`, which iOS requires for a service worker and push; only his tailnet's devices reach it |
| origin | a request must carry `Origin` equal to the configured `https://<mac>.<tailnet>.ts.net`, else 403 | the F1 rule, with the one origin the page has |

### 2.3 Who may act

| Layer | Rule |
|---|---|
| device token | pairing: the settings show a one-time code, valid 10 minutes and good for one use; the page sends it and receives a 256-bit device token, kept in a `Secure`, `HttpOnly`, `SameSite=Strict` cookie. comando keeps the token's SHA-256 with the device's name in `Secrets`. The settings list paired devices and revoke one. No valid token: 401. The token is the gate: any local process can reach a loopback port |
| tailnet user | a request through `tailscale serve` carries `Tailscale-User-Login`; one that names another login than the configured one is refused 403. This is a second check, not the gate (§4, U1) |
| passkey | pairing also registers a passkey (WebAuthn, ES256, attestation `none`, the `ts.net` name as relying party). A destructive answer (§2.4) carries an assertion, made with Face ID, over a challenge comando issued for that card and that decision; comando checks the signature over `authenticatorData ‖ SHA-256(clientDataJSON)` and the challenge, origin and sign count before it sends the intent |
| authority | an answer from the phone is the user's: the F6 refusals for agents do not apply to it, and the audit log records it as `phone <device>` |

### 2.4 What the phone does

| Card or action | From the phone | Face ID |
|---|---|---|
| `TOOL`, not a removal, not an MCP tool | allow once, deny with a message | no |
| `TOOL`, a removal (`McpTools.REMOVES`) or an MCP tool (name `mcp__…`) | allow once, deny | **yes**, for allow |
| `QUESTION` | each question's options, and Other as text | no |
| `PLAN` | approve, or deny with a message | no |
| `SQL_WRITE`, `CONNECTION`, `TERMINAL` | allow once, deny | **yes**, for allow |
| allow for the session or always | not offered: only in the window | — |
| send a prompt to one of main's sessions | as the user's prompt in that session's panel, the window's path | no |
| read a session | its name, state (working, idle, waiting on a card), last reply as text, the cards it waits on | no |
| start, stop, clear, rename sessions; files, SQL, other instances | no | — |

A deny never needs Face ID. The server decides which answers need it, from the card's kind, tool name and input; the
page only shows it.

### 2.5 Live updates and push

| Question | Rule | Why |
|---|---|---|
| page open | Server-Sent Events on `/phone/events`: a card raised or settled, a session's state; `Last-Event-ID` resumes after a reconnect. At most 4 streams, each on a named virtual thread | one stream a device, few devices |
| page closed | Web Push (RFC 8030) to the endpoint the page's subscription names, signed with comando's own VAPID key (RFC 8292, ES256 JWT) and encrypted `aes128gcm` (RFC 8291) | the free route on iOS |
| what is pushed | a card still pending 60 s after it was raised (setting `phonePushDelay`); a session's turn ends when the phone sent that turn's prompt | a card answered at the desk never buzzes the phone |
| what a push says | the session's name and the card's kind ("Comando G: a removal is waiting"); never the command, tool input or reply | the lock screen shows it; the page fetches the content over the tailnet |
| tap | opens the page on that card or session | |
| code | comando's own, on the JDK (P-256 key agreement, HMAC-SHA256 HKDF, AES-GCM, ECDSA), as [record 157](0157-comando-owns-its-json-mqtt-diff-completion-and-icons.md) asks; the HTTP POST on `java.net.http.HttpClient` | no library for a few hundred lines; RFC 8291 Appendix A and RFC 8292 give test vectors |

### 2.6 In the window

An answer from the phone settles the card in the window as any answer does, and the status bar shows a notice
"answered from phone <device>". The audit log has the device, the decision and whether Face ID was used.

## 3. Rejected

| Option | Why not |
|---|---|
| a native iOS app | the developer fee and a second language stack for a personal tool |
| the page speaking MQTT to frontear over WebSocket | F7, and it would expose an instance's inbox, where a prompt runs in a session in bypass |
| the phone as a paired machine (record 189) | a phone is not a comando instance; Safari presents a client certificate only from an installed profile |
| the routes on the MCP server | mixes the local sessions' trust with the user's (§2.2) |
| comando binding the Tailscale address with its own certificate | `tailscale serve` already terminates TLS with a trusted certificate and keeps comando on loopback |
| a push relay (ntfy, Pushover) | a third party sees each notification, and the reply path still needs this page |
| allow for the session or always from the phone | a wider grant is made at the desk |

## 4. Unchecked

| # | Question | What settles it |
|---|---|---|
| U1 | whether `tailscale serve` replaces a `Tailscale-User-Login` header the client sent, or passes it | `curl -H 'Tailscale-User-Login: x' https://<mac>.<tailnet>.ts.net/phone/echo` from the phone's network, against a debug echo route |
| U2 | that iOS delivers web push to a home-screen page on a `ts.net` origin, phone on mobile data | the increment-4 live check below |
| U3 | that a passkey's relying party may be the `ts.net` host (a public-suffix name) | registering one in increment 2 |

## 5. Increments

Each ends with its tests green, `ThreadRulesTest` and `FileReadRulesTest` lines for the new thread and file opener,
and a live check on Miguel's iPhone.

| # | Delivers | Acceptance |
|---|---|---|
| 1 | page server, pairing and device token, card list, non-destructive answers, the page's cards screen | HTTP tests: no token 401, wrong `Origin` 403, a spent pairing code refused, an allow produces `Intent.ApprovalAnswered` with `Allow(ONCE)`; live: a question card answered from the phone |
| 2 | passkey registration and assertion; destructive answers | a recorded assertion verifies; a tampered one, a reused challenge and a challenge for another card are refused; a removal allow without an assertion 403; live: a removal allowed with Face ID |
| 3 | Server-Sent Events | a raised card reaches an open stream within 1 s; a reconnect with `Last-Event-ID` misses nothing |
| 4 | Web Push | RFC 8291 Appendix A vector encrypts byte for byte; a VAPID JWT verifies with the public key; live: a card pending 60 s buzzes the locked phone on mobile data |
| 5 | prompts and session reads | a prompt from the phone reaches the session's panel as the user's; the read returns the last reply; live: a prompt sent and its reply read on a walk |
