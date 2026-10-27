# 20. Passwords in the macOS keychain through `security`

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

Connection passwords were stored in `connections.json`. Java has no keychain API.

## 2. Decision

Passwords go to the login keychain through `/usr/bin/security`, service `comando`, account = connection name. The
keychain is used for the default data root only, unless `-Dcomando.keychain=true`.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

The password is a command-line argument of `security` for the milliseconds it runs, so the user's own processes can
see it in the process list. Tests never write the user's keychain; the keychain test is opt-in
(`./gradlew test -PkeychainTest=true`).

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a JNA binding to the keychain | adds a dependency and native code |
