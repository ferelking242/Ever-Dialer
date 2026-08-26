package com.coolappstore.everdialer.by.svhp

import com.coolappstore.evercallrecorder.by.svhp.ShizuApplication

/**
 * Ever Émetteur — simplified app that bundles only:
 * - Call recording (ShizuCallRecorder)
 * - P2P sync to phone B (synccore)
 *
 * No dialer, contacts, notes, or other Ever Dialer+ features.
 */
class RivoApp : ShizuApplication() {
    override fun onCreate() {
        super.onCreate()

        // P2P sync engine: phone A acts as SENDER
        com.coolappstore.everdialer.by.svhp.sync.SyncSource.install(
            collectFn = { ctx ->
                val snapshot = com.coolappstore.everdialer.by.svhp.sync.CallLogCollector.collect(ctx)
                snapshot.files to snapshot.calls
            },
            openFileFn = { ctx, name ->
                com.coolappstore.everdialer.by.svhp.sync.CallLogCollector.openRecording(ctx, name)
            }
        )
        runCatching { com.coolappstore.everdialer.by.svhp.sync.SyncManager.init(this) }

        // Start monitoring for wireless-debugging pairing availability.
        // When detected, shows a notification prompting the user to pair —
        // same behaviour as the real Shizuku manager.
        runCatching {
            com.coolappstore.evercallrecorder.by.svhp.privileged.PairingNotifier.startWatching(this)
        }
    }
}
