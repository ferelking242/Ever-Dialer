---
name: Runtime state validation
description: Durable rule for trusting the embedded privileged runtime.
---

User-visible runtime status and privileged recording actions must use the central validated runtime state, not a raw binder ping.

**Why:** A binder can survive an app update, reinstall, wireless-debugging reset, stale payload, or permission change. Treating it as proof of readiness creates false green badges and can start recording through an invalid runtime.

**How to apply:** Revalidate pairing, Wireless debugging, current payload ownership, binder availability, and Shizuku permission before reporting RUNNING or bypassing startup. Keep raw binder checks inside the runtime coordinator only.