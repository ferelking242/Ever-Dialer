---
name: Android pairing TLS export
description: Wireless ADB pairing needs TLS exporter access that survives hidden-API restrictions.
---

Prefer the public `SSLSession.exportKeyingMaterial` API for wireless ADB pairing, with platform Conscrypt only as a fallback. Reflection against `com.android.org.conscrypt` can fail at runtime even when TLS itself succeeds.

**Why:** On a real Android device, the TCP and TLS handshake succeeded but hidden Conscrypt lookup failed before SPAKE2 could start.

**How to apply:** If pairing reaches TLS and reports Conscrypt unavailable, test the public SSLSession exporter first; do not treat a successful compile or TLS handshake as proof that the hidden Conscrypt API is callable.