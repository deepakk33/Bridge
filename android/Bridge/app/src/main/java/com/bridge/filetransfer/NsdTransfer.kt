package com.bridge.filetransfer

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.bridge.ble.BridgeConstants

/**
 * Tier 3 mDNS (`_bridge._tcp`) advertisement + discovery for Wi-Fi LAN transfer.
 *
 * The sender registers the service with its TCP port; the receiver discovers and
 * resolves it to an IP + port, then connects via [PayloadClient]. Service is
 * deregistered after the transfer.
 *
 * Device/LAN-only — exercised on a real shared Wi-Fi network.
 */
class NsdTransfer(private val nsdManager: NsdManager) {

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Advertise a transfer endpoint on [port]. */
    fun register(port: Int, onRegistered: (NsdServiceInfo) -> Unit = {}) {
        val info = NsdServiceInfo().apply {
            serviceName = BridgeConstants.FILE_TRANSFER_SERVICE_NAME
            serviceType = BridgeConstants.FILE_TRANSFER_SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onRegistered(info)
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun unregister() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    /** Discover the peer's transfer service, resolving to host + port. */
    @Suppress("DEPRECATION")
    fun discover(onResolved: (host: String, port: Int) -> Unit, onFailed: () -> Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains("_bridge._tcp")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = onFailed()
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return onFailed()
                            onResolved(host, info.port)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onFailed()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(
            BridgeConstants.FILE_TRANSFER_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
    }
}
