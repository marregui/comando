# 157. comando owns its JSON, MQTT, diff, completion and icons

| Date | Status | Decided by |
|---|---|---|
| 2026-11-14 | accepted; built | Miguel and Claude |

## 1. Context

Five libraries did work comando used a small part of:

| Library | Used for | Size |
|---|---|---|
| the HiveMQ MQTT client 1.4.1, with Netty (7 jars), RxJava, reactive-streams, JCTools, Dagger and javax.inject | `MqttPeerBroker`'s one connection to the comando Edge on loopback: connect, subscribe, publish at QoS 0 and 1, a last will, reconnects ([record 123](0123-instances-consult-each-other.md)) | 6.6 MB in 13 jars |
| Jackson 3.2.4 (databind, core, annotations) | every JSON comando reads and writes: Claude Code's stream-JSON, the MCP server's JSON-RPC, the store files, the intent log, the QuestDB and Edge registries, views; 72 files, nearly all on the tree model, a few record types bound by reflection | 2.6 MB |
| java-diff-utils 4.18 | the Myers diff behind `SplitDiff` and the unified diff of `Diffs` | 78 KB |
| AutoComplete 4.0.0 (fifesoft) | the SQL completion list of the command editor (`SqlCompletion`) | 155 KB |
| FlatLaf Extras 3.7.2 | `FlatSVGIcon` and its colour filter, which drew the icon set on jsvg | 117 KB |

Two more jars came in through other libraries: checker-qual 3.55.1 (236 KB), a runtime dependency of the PostgreSQL
JDBC driver that holds only annotations, and jna-platform, pty4j's. Each library is a version to follow, a set of
threads of its own (the MQTT client's Netty event loop, a platform thread, and its RxJava scheduler) and code on the
classpath comando does not call. The distribution's `lib` was 58.3 MB in 44 jars; it is 49.3 MB in 23 jars after this
record, measured on `app/build/install`.

Miguel, 2026-11-14: comando's own code in place of these libraries, with tests and consistent with the rest of the
code.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| MQTT | `app.mqtt`, the MQTT 5 subset `MqttPeerBroker` uses: `MqttClient` over a blocking socket (QoS 0 and 1 both ways, subscriptions, keep alive, a last will, a reconnect when its handler asks for one), `Codec` and `Wire` for the packets and their data, `Packet` (sealed records, CONNECT to DISCONNECT, checked when made), `Topics` (names, filters and matching, §4.7), `MqttException` (an MQTT reason code); its threads are two strands, `mqtt-<client id>` (reads, keeps alive, reconnects) and `mqtt-writer-<client id>` (writes in order) | one client on loopback to one broker, the comando Edge; the subset is tested against the specification's examples and a fake broker |
| JSON | `core.json`: `JsonNode` and its kinds, `ObjectNode`, `ArrayNode`, `Json` (read strictly or the first value, build, write compact and pretty), `JsonException`; the names and behaviour of Jackson 3.2.4's tree model, so a file moved by changing its imports | comando works on trees; `core` keeps no dependency |
| binding | `core.json.bind.Binder`: records, enums by name, lists, sets, maps, `Optional`, arrays and `Path` to trees and back by reflection; an `"@type"` class name for the interfaces that need one, allowed only under `dev.marregui.comando.core.`; unknown properties ignored | the store files, the intent log and the registries hold records, enums and collections, nothing else; a file written before reads the same |
| diff | `ui.kit.text`'s `Myers` (Myers' greedy algorithm, 1986, with a cancel check each round) and `UnifiedDiff` (hunks grouped and numbered as `UnifiedDiffUtils` did) behind `Diffs` and `SplitDiff` | the same differences and text as the library |
| completion | `ui.widgets.editor`'s `CompletionPopup` (the list in a window under the caret, its keys, its pause), `Completions` (the sorted words, a prefix match ignoring case), `Completion` (a word and its description) | the behaviour of AutoComplete 4.0.0 that `SqlCompletion` used, in 3 classes |
| icons | `ui.kit.icon.SvgIcon` on jsvg: each colour through a filter at paint, a picture per scale, a disabled twin greyed as FlatLaf greys one | jsvg is already a direct dependency ([record 58](0058-markdown-shows-the-pictures-beside-it.md)); one class of the library was used |
| checker-qual | excluded from the runtime classpath | annotations no class reads at run time |
| jna-platform | kept | pty4j's Unix `CLibrary` links `com.sun.jna.platform.unix.LibCAPI` |

Miguel decided the replacement of the five libraries and that it carries tests and keeps the code consistent. Claude
decided the subsets, that on-disk JSON keeps Jackson's bytes, the exclusion of checker-qual and keeping jna-platform.

## 3. Consequences

- `LibraryRulesTest` (ArchUnit) fails the build when a main class of any module depends on `tools.jackson`,
  `com.fasterxml.jackson`, `com.hivemq.client`, `io.netty`, `io.reactivex`, `com.github.difflib`,
  `org.fife.ui.autocomplete` or `com.formdev.flatlaf.extras`.
- On-disk JSON is unchanged. `core`'s `JsonGoldenTest` holds documents with Jackson's compact and pretty output;
  `app`'s `JsonGoldenTest` holds every file comando keeps (connections, sessions, folders, panels, the intent log, the
  QuestDB and Edge registries) and what the old mappers made of other inputs; each reads back equal and writes back
  byte for byte. `ui`'s `DiffGoldenTest` holds 139 diffs as java-diff-utils made them.
- Behaviour that changed:
  - the completion list closes when the caret leaves the word; the library re-filtered on the word the caret moved to
    on the same line. Left, Right and ⌃C are no longer taken while the list shows. The list takes the colours of
    comando's other popups (`Theme.CARD`, `Theme.CARD_BORDER`);
  - the MQTT client announces no Maximum Packet Size, as the client before it did, so a long question to another
    instance still arrives; a QoS 1 message in is acknowledged after its handler has it; a filter starting with a wildcard does not match a topic
    starting with `$`;
  - the binder reads an enum by its name only; Jackson also took its index. An empty QuestDB registry reads as no
    server; Jackson's mapping threw;
  - an icon has no `_dark` variant and filters no gradient; the set has neither.
- While this instance is connected to the comando Edge, its MQTT threads are two virtual strands of comando's, in place
  of the library's platform thread and scheduler.
