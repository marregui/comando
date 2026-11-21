# 185. frontear is the only fabric, on by default

| Date | Status | Decided by |
|---|---|---|
| 2026-11-21 | accepted; built in task 218; supersedes the broker of [record 122](0122-instances-name-kit-brain-data-root-edge.md) §2.4 and the selection of [record 184](0184-frontear-comandos-own-mqtt-fabric.md) §3 | Miguel and Claude |

## 1. Context

comando instances on one machine meet on one local MQTT 5 broker, the fabric
([record 123](0123-instances-consult-each-other.md)). [Record 122](0122-instances-name-kit-brain-data-root-edge.md)
§2.4 made that broker the open-source HiveMQ Edge, downloaded (359 MB), checked and run as a child process with its
own admin account, WebSocket, admin and JMX ports. [Record 184](0184-frontear-comandos-own-mqtt-fabric.md) §3 added a
frontear node inside comando as an alternative, chosen by a system property, with the HiveMQ broker kept as the
default. Neither started by itself: the instances found each other only after someone pressed the corner button.

Miguel, 2026-11-21: "this is not comando edge, but instead it is comando frontear; we will not allude to edge anymore.
there is no edge, that is a hivemq thing." The HiveMQ broker is removed, frontear is the only fabric, and the name
users and agents read is frontear.

## 2. Decision

| Question | Rule |
|---|---|
| the fabric | a frontear node named `comando`, hosted by `FrontearFabric` inside the JVM of one instance, MQTT 18883 on `127.0.0.1`; no child process, no download, no admin account |
| the name | `frontear` everywhere: the server name of `server_start`, `server_stop` and `ui_get_state`, the commands `frontear.start` and `frontear.stop` ("Start frontear", "Stop frontear"), the notices, the presence key `host` that says an instance hosts it |
| when it runs | at launch, when no live instance is registered as its host; and at any later look of the peer link (every 5 s) that finds no live host, so when the host quits the next instance to look hosts it |
| one host | `~/.comando-fabric.json` names the host (pid, name, data root, port); it is written to a temporary file and claimed by creating its link; every read-modify-write of it runs under an OS lock on `~/.comando-fabric.json.lock` (bounded wait), so of several instances starting at once exactly one hosts and a stale entry is replaced once; an entry whose process is gone is removed and claimed again; the lock file stays in the home |
| who stops it | the host only: its button, an agent's `server_stop frontear`, or its close; after a stop by hand that instance starts it again only when asked, and another instance hosts it at its next look |
| a port held by another process | one notice naming the port; the process is probed, never signalled, and the start is tried again at the next look |
| tests | `-Dcomando.peers=false`, set by every test task of `app` and by `:app:snapshot`: the instance neither starts nor joins a node and never binds 18883 |
| the corner button | its tooltip names frontear, says what it is and gives its state: "Start frontear: comando's fabric between instances, one node per machine on MQTT 18883 (stopped)" or "Stop frontear: comando's fabric between instances; only the comando hosting it stops it (running, hosted here)" |
| what is left behind | an `edge/` folder in a data root and the old registry in the home folder are neither read nor removed |

## 3. Consequences

- The instances meet without a click: the first comando to start hosts frontear, the others connect.
- No 359 MB download, no SHA-256 pin to keep current, no second process, and no WebSocket, admin or JMX port.
- When the host quits, the others are without a broker until the next look, up to 5 s; each instance's peer link
  republishes its retained presence when it connects again, so the Instances tab fills again by itself.
- The parity test that compared a frontear node with the pinned HiveMQ release, field by field, is given up with
  the release; frontear's own tests remain the check of its MQTT 5.
- An older comando that still runs the HiveMQ broker holds 18883: the newer one says so once and waits for the port.
- The system property that chose the fabric and the data root's `edge/` setting of `AppConfig` are gone.
- The help menu loses the broker's log: frontear writes to comando's own log.
