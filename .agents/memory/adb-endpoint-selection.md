---
name: ADB endpoint selection
description: Wireless debugging exposes separate pairing and connect services; endpoint validation and key invalidation must stay distinct.
---

The `_adb-tls-pairing` port is only for SPAKE2+ pairing. After pairing, discover `_adb-tls-connect` and validate it with a real shell command before transferring or launching anything. Prefer the advertised device address; use `127.0.0.1` only as a same-device fallback. A refused shell stream is a transport/session failure, not by itself proof that the ADB key is invalid.

**Why:** A previous runtime paired successfully on `192.168.1.154` but reused the pairing/loopback endpoint for later work, then deleted a valid key when one shell stream was refused.

**How to apply:** Keep endpoint discovery, shell validation, session reconnect, and TLS/authentication invalidation as separate decisions in future ADB changes.