# 196. MP4 videos play in the side pane through JavaFX's media player

| Date | Status | Decided by |
|---|---|---|
| 2026-11-24 | accepted; built in task 250; uses the `javafx-media` module that [record 190](0190-html-pages-render-in-a-panel-through-javafx-webview.md) already ships | Miguel and Claude |

## 1. Context

Miguel, 2026-11-24: "mp4 archives produced by comando should be able to be reproduced by comando". The videos
sessions make (release and briefing videos, piped from frames to ffmpeg) are MP4 files holding H.264 video, High
profile, yuv420p, and AAC audio, as ffprobe reads the six on hand. Before this record such a file opened in the side
pane as "a binary file of N bytes", or not at all over 4 MB.

JavaFX was already on the class path for the HTML pages, `javafx-media` among its modules, built for each platform.
Its `MediaPlayer` plays MP4 with H.264 and AAC: through AVFoundation on macOS, through the system's libavcodec on
Linux.

## 2. Decision

| Question | Rule |
|---|---|
| which files | a name ending in `.mp4` or `.m4v`, in any case (`FileView.isVideo`); the container is not read to decide |
| where it plays | the side pane, as a `video` view (`ViewSpec.Video`, the path only), wherever a file opens: the Files tab, a link, `ui_open_file`; a line asked for is ignored |
| size | no limit: the player reads the file, the view does not |
| the player | `ui.kit.media.VideoPlayer`: a `MediaView` in a `JFXPanel`, play/pause, a seek bar and the time under the picture; a click on the picture or the space bar plays or pauses |
| a hidden view | pauses: another tab or a closed pane plays no sound, also when hidden before the player said it plays |
| its end | paused back at its start, where the seek bar still moves it (a stopped player ignores a seek) |
| shown again | the file loads again from its start: a session may have made it anew |
| a file it cannot play | the view says why under the controls: the player's error, or, when the platform reports nothing (macOS on a file it cannot parse), that it did not open within 10 s |
| leaving | the widget's unmount disposes of the player, its view first let go of it |
| JavaFX | started once per process by `ui.kit.fx.FxToolkit`, shared with the pages |

Rejected: an HTML `<video>` element in the page panel. It plays through the same media stack, but its controls are
WebView's and the view would be a page, with the page tools, for a file that is no page.

## 3. Measured

`VideoPlayerUiTest`, on a MacBook Pro, 2026-11-24: the 2.0 s fixture (H.264 baseline, yuv420p, AAC, 17.9 KB) gets
ready with its length, plays, pauses, at its end pauses back at its start and seeks to its middle, in 7.3 s; a view
hidden right after play stays paused; a text file named `.mp4` fails at the 10 s deadline in 10.2 s; the first load of
a process took 6 s, which sets the deadline. A 5 min 32 s High-profile video made by a session played in a one-off
run of the same test. On Linux the
test requires a refused video to say why and is skipped there, as the CI machine may lack an H.264 decoder.
