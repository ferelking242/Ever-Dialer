/*
 * Vendored from thedjchi/Shizuku (fork of RikkaApps/Shizuku), manager module.
 * License: GPL-3.0 — same license as Ever Dialer. Provenance: master branch, 2026-08.
 *
 * Local adaptation: androidx.lifecycle.Observer replaced with a plain function type.
 */
package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: (Pair<String, Int>) -> Unit
) {
    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        try {
            if (!registered) {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (error: Throwable) {
            running = false
            registered = false
            throw error
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (registered) {
            nsdManager.stopServiceDiscovery(listener)
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer("" to -1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress ?: return
        // NSD already resolved this host from the explicitly requested ADB
        // service. Do not compare it against NetworkInterface: some Android
        // builds expose the Wi-Fi address through NSD before the interface list
        // is refreshed, which used to make discovery fail silently.
        if (running && resolvedService.port > 0) {
            serviceName = resolvedService.serviceName
            observer(host to resolvedService.port)
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.running = false
            adbMdns.registered = false
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            Log.v(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, $i")
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
