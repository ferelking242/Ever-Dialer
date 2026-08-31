---
name: Android pairing TLS export
description: Wireless ADB pairing needs TLS exporter access that survives hidden-API restrictions.
---

Use the **platform** `com.android.org.conscrypt.Conscrypt` (via reflection) for both SSLContext creation and keying-material export, matching upstream Shizuku's approach. Fall back to the bundled `org.conscrypt:conscrypt-android` library only when the platform class is unavailable.

**Why:** The bundled Conscrypt provider can fail to create proper Conscrypt sockets on some devices (e.g. Tecno/HiOS), causing all export fallbacks to fail with "No TLS keying-material export API is available". Shizuku upstream uses the platform class directly as a compile-time import — we mirror this via `Class.forName()` reflection.

**How to apply:** Always try the platform Conscrypt first (available on API 30+ / Android 11+). The export method signature is `static byte[] exportKeyingMaterial(SSLSocket, String, byte[], int)`. The SSLContext should also be created with the platform provider to ensure the socket is a platform Conscrypt socket.
