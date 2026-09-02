---
name: Embedded runtime startup
description: Reliable lifecycle for the embedded Shizuku pairing and server startup flow.
---

Long-running wireless-debugging pairing and server startup must run in a foreground service, not in a BroadcastReceiver callback or a Compose effect. The notification is the user-facing progress surface; the header should reflect the shared runtime state.

**Why:** Android may stop receiver work after `goAsync()` and Compose work when its composition is cancelled, leaving the UI stuck at “Starting…” without a durable result.

**How to apply:** Keep notification actions thin, hand work to the foreground service, bound every wait, and report both local failures and remote starter output.