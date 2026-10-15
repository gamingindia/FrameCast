package com.framecast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Finds the frame on the local network via mDNS (the firmware advertises "photoframe" as _http._tcp). */
object Discovery {
    @Suppress("DEPRECATION")
    fun find(ctx: Context, timeoutMs: Long = 5000): String? {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val latch = CountDownLatch(1)
        val resolving = AtomicBoolean(false)
        var result: String? = null

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolving.set(false) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host
                if (host is Inet4Address) {
                    result = host.hostAddress
                    latch.countDown()
                } else {
                    resolving.set(false)
                }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.startsWith("photoframe", ignoreCase = true) &&
                    resolving.compareAndSet(false, true)
                ) {
                    try { nsd.resolveService(info, resolveListener) } catch (e: Exception) { resolving.set(false) }
                }
            }
        }
        try {
            nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // ignore, result stays null
        } finally {
            try { nsd.stopServiceDiscovery(listener) } catch (e: Exception) {}
        }
        return result
    }
}
