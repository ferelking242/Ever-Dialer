/*
 * Vendored from thedjchi/Shizuku (fork of RikkaApps/Shizuku), manager module.
 * License: GPL-3.0 — same license as Ever Dialer. Provenance: master branch, 2026-08.
 */
package moe.shizuku.manager.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {
    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
