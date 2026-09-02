---
name: Android build environment
description: Local workspace lacks the Android SDK; GitHub Actions is the authoritative Android build check.
---

The workspace may provide Java and Gradle without an Android SDK. Treat a local Android build failure caused only by missing SDK location as an environment limitation, and use the repository's GitHub Actions workflow for the real compile/build validation.

**Why:** The Android project successfully built on GitHub Actions while the local container could not locate an SDK.

**How to apply:** When changing Android code, inspect local tooling briefly, then push a clean commit and use the GitHub Actions result to validate the build; fix only errors reported by that remote build.