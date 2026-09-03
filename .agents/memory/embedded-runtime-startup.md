---
name: Embedded runtime startup
description: Reliable lifecycle for the embedded Shizuku pairing and server startup flow.
---

Long-running wireless-debugging pairing and server startup must run in a foreground service, not in a BroadcastReceiver callback or a Compose effect. The notification is the user-facing progress surface; the header should reflect the shared runtime state.

**Why:** Android may stop receiver work after `goAsync()` and Compose work when its composition is cancelled, leaving the UI stuck at “Starting…” without a durable result.

**How to apply:** Keep notification actions thin, hand work to the foreground service, bound every wait, and report both local failures and remote starter output.

**File transfer:** Never stream file bytes through `shell:cat > path` (commandWithStdin). On the Samsung test device adbd tears that stdin pipe down mid-write (observed deterministic A_CLSE after ~8 KiB, plus silent truncations / missing files on retries). Use the adb `sync:` service (SEND/DATA/DONE/QUIT, i.e. what `adb push` itself does) — AdbClient.syncSend(remotePath, mode, input).

**Reference architecture (thedjchi/Shizuku upstream):** the real manager never pushes ANY file for a wireless-debugging start. It connects to 127.0.0.1:<port> and runs one shell command: `<nativeLibraryDir>/libshizuku.so --apk=<app sourceDir>` (Starter.internalCommand), because the server dex ships inside the manager's own installed APK. Ever-Dialer diverges by pushing a pinned fork APK + native starter to /data/local/tmp; that divergence is the source of the extra failure surface. If binder still never appears after the transfer is fixed, the next step is to embed the server dex in the app's own APK and drop the /data/local/tmp push entirely.