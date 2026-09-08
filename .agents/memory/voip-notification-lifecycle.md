---
name: VoIP notification lifecycle
description: Durable constraint for detecting third-party app calls through NotificationListenerService.
---

The end of a VoIP notification is not always the end of the call. WhatsApp and OEM notification adapters can remove a ringing notification and post the answered-call notification under a new key.

**Why:** Stopping immediately on the first removal can split one real call into a false stop/start pair, especially during the ringing-to-connected transition.

**How to apply:** Track active notification keys, but defer the stop briefly and cancel it when a new accepted call notification arrives. Keep listener disconnect handling defensive and immediate.