# Installing comando

The recipe a Claude Code agent follows when a user asks it to "install comando" (from a release link, or with no link
for the newest release). Run each step, report what it printed when it fails, and stop there.

1. [Prerequisites](#1-prerequisites)
2. [Choose the release and the file](#2-choose-the-release-and-the-file)
3. [Install](#3-install)
4. [Check that it starts](#4-check-that-it-starts)
5. [The kit](#5-the-kit)
6. [Start comando](#6-start-comando)
7. [Upgrade](#7-upgrade)
8. [Uninstall](#8-uninstall)

## 1. Prerequisites

| Need | Check | When it is missing |
|---|---|---|
| Claude Code CLI | `claude --version` | the session running this recipe is Claude Code; comando finds `claude` at `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`, else on `PATH` |
| GitHub CLI, signed in | `gh auth status` and `gh repo view marregui/comando --json name` | install `gh` (macOS `brew install gh`; Debian and Ubuntu `sudo apt-get install -y gh`; Windows `winget install --id GitHub.cli -e`), then ask the user to run `gh auth login` |
| a desktop session | macOS; Linux with X11 or Wayland (XWayland); Windows 10 or 11 | comando is a window application |

No Java is needed: each package carries its own runtime.

## 2. Choose the release and the file

The tag is the last part of a release link (`https://github.com/marregui/comando/releases/tag/v2.1.0` is `v2.1.0`).
For a link ending in `/releases/latest`, or no link, take the newest release:

```sh
gh release list -R marregui/comando --exclude-drafts --exclude-pre-releases -L 1 --json tagName --jq '.[0].tagName'
```

An empty answer means there is no release yet: tell the user, and stop.

Each release has one file per system:

| System | Architecture (`uname -m`; on Windows `$env:PROCESSOR_ARCHITECTURE`) | File |
|---|---|---|
| macOS | `arm64` (Apple silicon) | `comando-<version>-macos-arm64.dmg` |
| Linux | `x86_64` | `comando-<version>-linux-x64.tar.gz` |
| Windows | `AMD64` | `comando-<version>-windows-x64.zip` |

Another architecture has no package: tell the user so, and that comando builds from source with JDK 25
(`./gradlew :app:installDist`, [Build and run](docs/build-and-run.md)). Download the file into a new, empty folder
(`TAG` is the tag chosen above):

```sh
DL=$(mktemp -d)
gh release download "$TAG" -R marregui/comando -p 'comando-*-macos-arm64.dmg' -D "$DL"      # macOS
gh release download "$TAG" -R marregui/comando -p 'comando-*-linux-x64.tar.gz' -D "$DL"     # Linux
```

```powershell
$DL = New-Item -ItemType Directory -Path (Join-Path $env:TEMP "comando-$(Get-Random)")
gh release download $TAG -R marregui/comando -p 'comando-*-windows-x64.zip' -D $DL           # Windows
```

## 3. Install

comando is not signed. On macOS the install clears the quarantine flag, so that Gatekeeper does not refuse the app.
When `comando` is already installed, this is an upgrade: follow [§7](#7-upgrade) instead.

**macOS**: the application goes to `/Applications/comando.app`.

```sh
MNT=$(hdiutil attach -nobrowse -readonly "$DL"/comando-*.dmg | awk -F'\t' '/\/Volumes\// {print $NF}')
ditto "$MNT/comando.app" /Applications/comando.app
hdiutil detach "$MNT"
chmod -R u+w /Applications/comando.app
xattr -dr com.apple.quarantine /Applications/comando.app
```

The runtime's licence files are read-only, and `xattr` cannot change a read-only file: `chmod` first, or `xattr` exits
with "Permission denied". Neither changes the app's ad-hoc signature, which jpackage makes (`codesign --verify --deep --strict
/Applications/comando.app` exits 0). Newer macOS versions print that `hdiutil` is deprecated; it still works.

**Linux**: the application goes to `~/.local/share/comando`, its launcher to `~/.local/bin/comando`.

```sh
mkdir -p ~/.local/share ~/.local/bin
tar -xzf "$DL"/comando-*.tar.gz -C ~/.local/share
printf '#!/bin/sh\nexec "$HOME/.local/share/comando/bin/comando" "$@"\n' > ~/.local/bin/comando
chmod +x ~/.local/bin/comando
```

When `~/.local/bin` is not on the user's `PATH` (`command -v comando` prints nothing in a new shell), tell the user to
add it to their shell profile.

**Windows** (PowerShell): the application goes to `%LOCALAPPDATA%\comando`, with a Start-menu shortcut.

```powershell
Expand-Archive -Path (Get-ChildItem $DL -Filter 'comando-*.zip').FullName -DestinationPath $env:LOCALAPPDATA
Get-ChildItem -Recurse "$env:LOCALAPPDATA\comando" | Unblock-File
$link = (New-Object -ComObject WScript.Shell).CreateShortcut("$env:APPDATA\Microsoft\Windows\Start Menu\Programs\comando.lnk")
$link.TargetPath = "$env:LOCALAPPDATA\comando\comando.exe"
$link.Save()
```

Delete the download folder (`$DL`) afterwards.

## 4. Check that it starts

Start comando, wait ten seconds, check that its process runs, and ask the user whether the window opened:

| System | Start | Running |
|---|---|---|
| macOS | `open /Applications/comando.app` | `pgrep -x comando` prints a process id |
| Linux | `nohup ~/.local/bin/comando >/dev/null 2>&1 &` | `pgrep -x comando` prints a process id |
| Windows | `Start-Process "$env:LOCALAPPDATA\comando\comando.exe"` | `Get-Process comando` lists it |

comando keeps its data and logs in `~/.comando` (`%USERPROFILE%\.comando` on Windows); when it does not start, the newest
file in `~/.comando/logs/` says why. On Linux, an error naming a missing `libX…` or `libfreetype` library is fixed by
the desktop libraries: `sudo apt-get install -y libxext6 libxrender1 libxtst6 libxi6 libfreetype6 fontconfig` on Debian
and Ubuntu. A second start while comando runs only says that it is already running.

## 5. The kit

The kit is a Claude Code setup for the way of working comando is built for: instructions, skills, a plugin, and a
brain (memory, knowledge base, scratchpad) kept in its own folder. It installs into `~/.claude/comando-kit` and leaves the
user's own `~/.claude` alone; comando finds it at its next start and runs its sessions on it.

Ask the user: "comando is installed. Install the kit as well?" On no, go to [§6](#6-start-comando). On yes, read the
kit's recipe and follow it; it asks the user for consent before it changes anything:

```sh
gh api -H 'Accept: application/vnd.github.raw' repos/marregui/comando-kit/contents/INSTALL.md
```

(`https://github.com/marregui/comando-kit`, file `INSTALL.md`; the repository is private, so `gh` reads it.) When the
kit is installed while comando runs, quit comando and start it again so its sessions use the kit.

## 6. Start comando

Tell the user how to start it, and that **Actions ▸ Tutorial** inside comando takes ten minutes:

| System | Start |
|---|---|
| macOS | `comando` in Launchpad or Spotlight, or `open -a comando` |
| Linux | `comando` in a terminal |
| Windows | `comando` in the Start menu |

## 7. Upgrade

1. Choose the release and download its file as in [§2](#2-choose-the-release-and-the-file).
2. Quit comando: macOS `osascript -e 'quit app "comando"'`; on Linux and Windows ask the user to close its window.
3. Remove the installed application, then install the new one as in [§3](#3-install):
   macOS `rm -rf /Applications/comando.app`; Linux `rm -rf ~/.local/share/comando` (the launcher stays);
   Windows `Remove-Item -Recurse -Force "$env:LOCALAPPDATA\comando"` (the shortcut stays).
4. Check that it starts, as in [§4](#4-check-that-it-starts).

The data in `~/.comando` is kept and read by the new version. The kit upgrades on its own: comando shows its version
and offers **Upgrade the kit…** when a newer one is released.

## 8. Uninstall

1. Quit comando, as in [§7](#7-upgrade) step 2.
2. Remove the application:

   | System | Remove |
   |---|---|
   | macOS | `rm -rf /Applications/comando.app` |
   | Linux | `rm -rf ~/.local/share/comando ~/.local/bin/comando` |
   | Windows | `Remove-Item -Recurse -Force "$env:LOCALAPPDATA\comando"`, then the shortcut `"$env:APPDATA\Microsoft\Windows\Start Menu\Programs\comando.lnk"` |

3. Ask the user before removing their data: `~/.comando` holds their panels, connections, sessions and logs; on macOS
   the connection passwords are in the login keychain under the service `comando`
   (`security delete-generic-password -s comando`, once per saved password).
4. The kit is removed by its own recipe, section Uninstall, in `~/.claude/comando-kit/INSTALL.md`.
