/*
 * Ever Dialer+ — pluggable content source for the sync engine.
 *
 * The core engine (this library) stays independent from the dialer's
 * call-log/recording storage, because the same engine is embedded in the
 * receiver app (phone B) which never pushes anything.
 *
 * Phone A (main app) registers its collectors at startup via [install];
 * phone B leaves them null — the push path is never used on a receiver.
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import java.io.InputStream

object SyncSource {

    /** Returns everything phone A can offer: recording files + call metadata. */
    @Volatile
    var collect: ((Context) -> Pair<List<FileEntry>, List<CallMeta>>)? = null

    /** Streams one recording by its manifest name, or null when missing. */
    @Volatile
    var openFile: ((Context, String) -> InputStream?)? = null

    fun install(
        collectFn: (Context) -> Pair<List<FileEntry>, List<CallMeta>>,
        openFileFn: (Context, String) -> InputStream?
    ) {
        collect = collectFn
        openFile = openFileFn
    }
}
