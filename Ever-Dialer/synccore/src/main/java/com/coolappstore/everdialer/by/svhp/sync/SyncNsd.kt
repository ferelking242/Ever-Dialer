/*
 * Ever Dialer+ — NSD/mDNS plumbing so the two phones find each other on the
 * same WiFi network without any configuration (_everdial._tcp.local.).
 */
package com.coolappstore.everdialer.by.svhp.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SyncNsd {

    const val SERVICE_TYPE = "_everdial._tcp."

    /** Registers this device's listener service on the local network. Returns a disposer. */
    fun register(context: Context, serviceName: String, port: Int): () -> Unit {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("id", SyncStore.identity(context).first)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        return { runCatching { nsd.unregisterService(listener) } }
    }

    /**
     * Blocking discovery of the first peer advertising [SERVICE_TYPE] (excluding our own id).
     * Returns (host, port, serviceName) or null after [timeoutMs].
     */
    fun discoverOnce(context: Context, timeoutMs: Long): Triple<String, Int, String>? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val myId = SyncStore.identity(context).first
        val found = AtomicReference<Triple<String, Int, String>?>(null)
        val done = CountDownLatch(1)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { done.countDown() }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val advertisedId = info.attributes["id"]
                            ?.let { String(it, Charsets.US_ASCII) }
                        if (advertisedId == myId) return // our own registration
                        if (found.compareAndSet(null, Triple(host, info.port, info.serviceName))) {
                            done.countDown()
                        }
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return found.get()
    }
}
