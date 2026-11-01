# 20. Passwords in the macOS keychain through `security`

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended 2026-11-01 (see Amendments) | Claude |

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

## Amendments

2026-11-01, Miguel's decision D2 of the first-release review: a stored password belongs to the host, port, database and
user it was given for. A save that changes one of them, from the connection dialog or an agent's `connection_save`,
drops the password unless the save gives it again, and a notice says to enter it again; so no test or connect sends it
to a target the user did not name with it. `connection_save` keeps every field the call leaves out, the production flag
and the TLS mode included. Tested in `ReducerTest` and `ActionToolsTest`.

2026-11-01, the first-release review's last round: the keychain account names the connection and its target,
`name user@host:port/database`, each part URL-encoded (`Store.account`), so a connection pointed elsewhere has another
account and never reads its old target's item, even when deleting that item failed (a locked keychain). An item under
the bare name, written by an earlier comando, is never read and is deleted at the next save, so each password stored
before this version is entered once more. Tested in `StoreTest`.

2026-11-01, the first-release review's last round: in the connection dialog only typing in the password field gives
the password again, since the field is filled with the stored password; a connection pointed elsewhere keeps a password
retyped in the field and drops one left as the form filled it, together with its keychain item. An agent's
`connection_save` gives it again with a `password` argument; without one, a call that changes the target drops it.
Tested in `ResourceDialogTest`.
