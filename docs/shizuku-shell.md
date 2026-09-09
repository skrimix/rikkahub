# Device shell through Shizuku

Enable **Device shell (Shizuku)** in an assistant's local tools settings. Install and start
[Shizuku](https://shizuku.rikka.app/guide/setup/), then use **Grant permission** in RikkaHub.
The page shows whether commands will run as ADB shell or root. Sui is also supported.
Backend API v12 or newer is required.

Commands operate on the device with the backend's privileges. They can read or change device
data allowed by that identity. Each assistant requires command approval by default; turn off
**Require command approval** to let that assistant run commands automatically. Shizuku's
application permission is shared by all assistants, but tool enablement and command approval
are configured separately for each assistant.

## Tools

- `shizuku_status()` reports availability, permission, and the current UID/privilege. It does
  not request permission or execute a command.
- `shizuku_shell(command, cwd?, timeout_seconds?)` runs `/system/bin/sh -c`. The default
  directory is `/`; the default timeout is 30 seconds, with a range of 1–600 seconds.

Commands are limited to 32 KiB of UTF-8 text. Each call starts a fresh process with stdin
closed. The result includes `stdout`, `stderr`, `exitCode`, `timedOut`, `truncated`, and `uid`.
At most 32 KiB of each output stream is retained; excess output is drained and discarded.
Connection and execution failures include `error` and `message` fields.

There are no interactive sessions, PTYs, or supported background jobs. Cancellation and
timeout stop the command and attempt to stop its descendants. Deliberately detached
processes may survive. A lost connection is reported without replaying the command, because
it may already have changed phone state. Subsequent calls can reconnect.

## Development checks

Run JVM tests and build the APKs:

```sh
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.shizuku.*'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

`ShizukuShellDeviceTest` checks identity, output, timeout, cancellation, and reconnection.
It requires Shizuku running and permission granted to RikkaHub Debug; execution tests skip
when permission is unavailable. Add `-e requestShizukuPermission true` to show the permission
prompt and wait briefly for a response before running tests. The reconnection test deliberately stops only RikkaHub's
UserService. Run it on a test device without other RikkaHub shell commands in progress:

```sh
adb -s DEVICE install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w \
  -e class me.rerere.rikkahub.data.shizuku.ShizukuShellDeviceTest \
  me.rerere.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Also check permission grant, denial, revocation, backend restart, and both ADB and root
backends. Verify a release build can bind the UserService, since its constructor is loaded
by Shizuku and must remain available after shrinking.
