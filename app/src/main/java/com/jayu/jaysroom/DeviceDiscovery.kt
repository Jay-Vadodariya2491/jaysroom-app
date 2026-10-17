package com.jayu.jaysroom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One discovered ESP32 device on the local network.
 */
data class EspDevice(
    val name: String,
    val host: String,
    val port: Int,
    // The raw mDNS/NSD service name (e.g. "jaysroom") - stable across a found/lost
    // pair even when the display name above is a custom, unrelated label like
    // "Amrut". Only used for matching, never shown in the UI.
    val nsdServiceName: String
) {
    val url: String get() = "http://$host:$port/"
    val key: String get() = "$host:$port"
}

private const val TAG = "JaysRoomDiscovery"

// Must match MDNS.addService("jaysroom", "tcp", 80) on the ESP32.
// Using a custom service type (instead of generic _http._tcp.) means we only
// ever see devices running this firmware - no other gadgets on the network
// show up by accident.
private const val SERVICE_TYPE = "_jaysroom._tcp."

/**
 * Wraps Android's NsdManager (the OS's built-in Bonjour/mDNS client) to continuously
 * look for ESP32 boards advertising the "_jaysroom._tcp." service - the same mechanism
 * apps like the WLED app use to find devices without the user typing an IP address.
 */
class DeviceDiscovery(
    context: Context,
    private val onDeviceFound: (EspDevice) -> Unit,
    // Called with the lost device's raw nsdServiceName (see EspDevice), not its display name.
    private val onDeviceLost: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    fun start() {
        if (discovering) return
        discovering = true

        // mDNS runs over multicast UDP; without this lock some phones silently
        // drop the packets to save battery and devices never show up.
        multicastLock = wifiManager.createMulticastLock("jaysroom-mdns-lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                resolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${service.serviceName}")
                mainHandler.post { onDeviceLost(service.serviceName) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                discovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(service: NsdServiceInfo) {
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                val friendlyName = readFriendlyName(serviceInfo) ?: serviceInfo.serviceName
                val device = EspDevice(friendlyName, host, port, serviceInfo.serviceName)
                Log.d(TAG, "Resolved ${device.name} -> ${device.url}")
                mainHandler.post { onDeviceFound(device) }
            }
        })
    }

    /** Reads the "name" TXT record the ESP32 advertises, e.g. "Amrut". */
    private fun readFriendlyName(serviceInfo: NsdServiceInfo): String? {
        return try {
            val bytes = serviceInfo.attributes?.get("name") ?: return null
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        if (!discovering) return
        discovering = false
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery failed: ${e.message}")
        }
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            // already released - ignore
        }
        multicastLock = null
    }
}
