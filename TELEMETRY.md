# ARMSX3 Telemetry

A fork of ARMSX3 1.6.7 (`b8eea42f52755f0841afc04a23b06125e5274bab`) that
automatically writes performance files while a game runs. The app installs as
**ARMSX3 Telemetry**, package `com.armsx3.telemetry`, alongside the Play app.
The original GPLv2 license and third-party notices remain applicable.

## First use

1. Install the telemetry APK from this fork's Android workflow artifact.
2. Set up firmware and game-folder access in the new app. Its settings, saves,
   and caches are separate from your existing installation. Use upstream's
   backup/import facilities if you want to transfer data; do not uninstall the
   existing app to make room for this one.
3. In **Performance**, open **Performance logging** and select
   `Download/ps3-performance` in Android's folder picker.
4. Enable **Automatically record games** once.
5. Play. The status line confirms recording, frame count, and dropped events.

Subsequent games create `telemetry-v1-<UTC>-<unique id>/` automatically.
Files are flushed once per second. No manual log export or Android system-trace
capture is required for these metrics. The destination permission persists.
No telemetry is uploaded and no local network server is started.

## Files and metric definitions (schema version 1)

- `metadata.json`: recorder identity, source/build revision, game, renderer/driver
  information, hardware sensor paths, timestamp mapping, and metric definitions.
- `settings.json`: latest core and UI settings. Initial and changed snapshots are
  also retained in events. Settings are checked every ten recording samples.
- `frames.csv`: native monotonic timestamp and guest frame interval in milliseconds.
  The first frame and frames after a pause have an empty interval. Only non-forced
  guest frame-end hooks count; generated and Android UI frames do not.
- `samples.csv`: approximately one-second windows containing guest frame rate,
  p50/p95/p99/max frame intervals, CPU shares/thread counts, hardware readings,
  thermal status, log backlog, and event-loss count. FPS is blank at state transitions
  and while paused. A window with no frames can correctly report zero FPS.
- `events.jsonl`: native pipeline creation start/duration/result, guest intervals
  of at least 100 ms, lifecycle, settings, errors, and event-loss changes.
- `session.log`: incrementally copied native `ARMSX3.log`. The initial copy includes
  at most its last 1 MB for initialization context; each following tick copies at
  most 1 MB. The sample's backlog field shows uncopied bytes. Upstream's normal
  severity and Silence All Logs settings still apply; missing source logs are
  explicitly reported. This is not merely Java stdout's launcher-only session log.
- `completed.json`: normal close or recording-failure marker. A session without it
  is incomplete, for example after an OS process kill.

Native timestamps use `steady_clock`/CLOCK_MONOTONIC; Kotlin uses System.nanoTime.
UTC and monotonic timestamps are recorded together for correlation. CPU values
are derived from independent process and thread CPU clocks, not `/proc/stat` and
not the overlay's destructive get_cycles counters. CPU percent is normalized by
online core count. PPU/SPU/RSX CPU shares are overlay-compatible *estimates*
apportioned from thread CPU deltas, not independent saturation percentages.
Short-lived threads that exit between samples can be missed. Initial CPU shares
are blank until a baseline exists. Telemetry does not require the overlay visible.

GPU activity is device-wide Qualcomm KGSL busy/total when readable. GPU and CPU
clocks and named millidegree thermal sensors are best-effort; missing values are
blank/null. Android thermal status is the PowerManager status code (0 is none).
Actual GPU execution timings and kernel scheduling traces are **not** measured.
Pipeline durations are worker wall times and may overlap; summing them does not
give total gameplay freeze time. No synchronous GPU queries or heavy RSX profiler
are enabled by recording.

## Reading during play

From a terminal with shared-storage access:

```bash
ls /sdcard/Download/ps3-performance/telemetry-v1-*/
tail -f /sdcard/Download/ps3-performance/telemetry-v1-<session>/samples.csv
tail -f /sdcard/Download/ps3-performance/telemetry-v1-<session>/events.jsonl
```

CSV uses a header and standard quote escaping. Arrays/maps occupy quoted JSON
columns. Event lines are independent JSON objects. Each file rotates at 16 MB;
later segments append `.001`, `.002`, etc. before the extension and repeat CSV
headers. Reopen tail on a new segment when rotation occurs.

At 512 MB total, only old **completed** directories with this recorder's metadata
marker are removed, oldest first. The current session, incomplete sessions,
externally captured traces, copied logs, and other folders are preserved. If no
safe cleanup can make room, recording stops with an error instead of deleting
unrelated data. Incomplete sessions can be removed manually after inspection.

## Build and validation

The Android-only `Telemetry Android` workflow builds both the native core and UI.
The stable base does not contain the newer upstream Android workflow; this fork
adapts its Android recipe, pins NDK/CMake, fetches omitted native dependencies,
and omits the proprietary Discord SDK. It builds a release APK using the Play
source set (frame generation remains disabled) and the fork's identity.
Self-updating is disabled so an upstream update cannot replace the telemetry build.

Signing uses a dedicated PKCS12 key in the `TELEMETRY_KEYSTORE_B64` Actions secret,
with password in `TELEMETRY_KEYSTORE_PASSWORD`. No signing key is committed.
The workflow also retains the unstripped core as a compressed symbol artifact.

Native queue checks:

```bash
c++ -std=c++20 -pthread -I. tests/telemetry/queue_test.cpp -o /tmp/telemetry-queue-test
/tmp/telemetry-queue-test
```

Android unit checks: `:app:testPlayReleaseUnitTest`.

On-device acceptance still requires checking folder permissions, overlay-hidden
recording, pause/resume, restarts, storage errors, and telemetry off/on performance
on the Tab S8 Ultra. Compare repeated runs of the same cached route; target under
3% median frame-time overhead. Never claim that target from a noisy single run.
