---
name: Embedded runtime package identity
description: Constraints imposed by Shizuku's native starter when an APK is pushed instead of installed.
---

When the embedded Shizuku APK is launched directly from `/data/local/tmp`, its parent directory must be named after the host application's package id, and the sibling `lib/{arm64,arm}/libshizuku.so` path must exist.

**Why:** The native starter derives the manager package from the APK path and passes the sibling native-library directory to the server. An arbitrary directory name makes the server query a nonexistent `.shizuku` provider; omitting the sibling library path breaks the server's Rish setup.

**How to apply:** Keep the remote APK directory aligned with the app id and populate the ABI-specific native library before launching the starter. Do not wrap the starter in an external background command; it forks and detaches itself.