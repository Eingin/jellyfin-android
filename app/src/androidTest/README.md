# Picture-in-picture tests

Run with a PiP-capable device or emulator on Android 8 or newer:

```sh
./gradlew connectedLibreDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.jellyfin.mobile.player.ui.PlayerPictureInPictureTest
```

Enable picture-in-picture permission for the debug app. The tests use the normal
downloaded-video playback path, with a temporary local download and account;
they need no Jellyfin server. They remove their own records and restore the
selected account and background-audio preference afterward.

Use gesture navigation for the home-gesture tests. To reproduce the failure this
change addresses, use Android 16 with Kvaesitso as the default launcher. The tests
also exercise returning to fullscreen, paused playback, finishing playback in
PiP, and leaving the player. Run on Android 11 as well to check manual PiP entry
before automatic entry became available.

`assets/pip-video.mp4` is an 18 KiB, silent, 60-second H.264 video of a solid blue
frame generated for these tests. It contains no third-party media. Regenerate it
with FFmpeg:

```sh
ffmpeg -f lavfi -i color=c=blue:s=320x180:r=10 -t 60 -an \
  -c:v libx264 -crf 35 -preset medium -pix_fmt yuv420p \
  -movflags +faststart app/src/androidTest/assets/pip-video.mp4
```
