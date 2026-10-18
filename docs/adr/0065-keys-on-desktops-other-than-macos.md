# 65. Keys on desktops other than macOS

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted | Miguel (Linux tested in CI to work as the Mac does), Claude (how: the moved keys, the terminal's keys, the names in text, the tests) |

## 1. Context

comando's keys were designed on macOS: ⌘ for its commands, ⌃ for the lower tabs (⌃1–⌃4), the line to the terminal
(⌃⏎) and the shell. Elsewhere the toolkit's menu modifier is Ctrl, so ⌘'s keys became Ctrl's and met ⌃'s: Ctrl+⏎ was
Run and the line to the terminal at once (Run won), Ctrl+1–4 the panels and the lower tabs (the tabs won, the first four
panels had no key), and in the terminal every always-enabled command took its Ctrl key from the shell: Ctrl+W closed the
panel instead of deleting a word, Ctrl+E moved the keyboard to the editor, Ctrl+B hid the sidebar, Ctrl+A, Ctrl+F,
Ctrl+J, Ctrl+T and Ctrl+\ did comando's work. Texts named keys as macOS writes them ("⌘⇧T reopens it", "⌃2"). CI ran
the tests on Linux, but none drove the keyboard there.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| commands | each key is macOS's with Ctrl for ⌘ (⌘⇧P is Ctrl+Shift+P) | one key map to learn and to document |
| keys that would meet | the lower tabs take Alt (Alt+1–4); the line to the terminal takes Ctrl+Alt+⏎ (Alt+⏎ is Run as SQL) | Ctrl with a digit is a panel and Ctrl+⏎ is Run, as ⌘ is on macOS |
| the terminal | Ctrl alone with a letter, `[`, `\`, `]` or space goes to the shell, never to a command (`TerminalWidget.keepsKey`, honoured by `KeyPriority`); every other chord stays a command's | on macOS ⌃ reaches the shell and ⌘ comando; there Ctrl is both, and a shell needs its control characters |
| texts | every text that names a key goes through `KeyText.of`: elsewhere ⌘ reads Ctrl, ⌥ Alt, ⇧ Shift, a moved key where it moved, the key by its name ("Ctrl+Shift+Enter"); the palette and the shortcuts table name keys the same way; core's notices are translated where the status bar shows them | a key named in text is one the user presses |
| tests | either set of keys is chosen with `-Dcomando.keys=mac` or `linux` on any desktop; `UiKeysTest` (in `:app:uiTest`, so on Linux CI under `xvfb-run`) drives a real window with both: the key map against macOS's, no key shared, Ctrl letters reaching the shell in the terminal, Run and the terminal keys, every text in the window; `KeyTextUseTest` fails on a string literal naming a key that does not reach the user through `KeyText` | CI on Linux keeps the Linux keyboard working as the Mac's does |

## 3. Consequences

On desktops other than macOS a command whose key is Ctrl with a letter is not reached from the terminal by that key;
the palette (Ctrl+Shift+P) and every chord with Shift, Alt or a digit still are. `Platform.macKeys()` decides the keys,
`Platform.isMac()` the rest (the Dock, the application menu's ⌘Q, file choosers). The docs keep writing keys as macOS
does, with this record and the table in [workbench §2.3](../workbench.md#23-keys) for the other desktops.
