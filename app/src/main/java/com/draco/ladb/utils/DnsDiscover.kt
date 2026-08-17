package com.draco.ladb.utils

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.draco.ladb.utils.DnsDiscover.Companion.adbPort
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "DNS"

class DnsDiscover private constructor(
    private val connectivityManager: ConnectivityManager,
    private val nsdManager: NsdManager
) {
    private var started = false
    private var bestExpirationTime: Long? = null
    private var bestServiceName: String? = null

    private var pendingServices: MutableList<NsdServiceInfo> = Collections.synchronizedList(ArrayList())

    private val resolveExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    companion object {
        private var instance: DnsDiscover? = null
        var adbPort: Int? = null
        var pendingResolves = AtomicBoolean(false)
        var aliveTime: Long? = null

        fun getInstance(connectivityManager: ConnectivityManager, nsdManager: NsdManager): DnsDiscover {
            return instance ?: DnsDiscover(connectivityManager, nsdManager).also { instance = it }
        }
    }

    /**
     * Start the scan for the best ADB port to connect to. Only needs to be started once.
     */
    fun scanAdbPorts() {
        if (started) {
            Log.w(TAG, "Already started")
            return
        }
        started = true
        aliveTime = System.currentTimeMillis()
        nsdManager.discoverServices(
            "_adb-tls-connect._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }

    /**
     * Returns the device's local IP address, or null if an error occurred.
     */
    fun getLocalIpAddress(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        // Ensure it's a valid Wi-Fi connection
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    val addresses = networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val inetAddress = addresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }

    /**
     * Determines if the service is the most recent to broadcast, and if so, sets it as the [adbPort].
     */
    private fun updateIfNewest(serviceInfo: NsdServiceInfo) {
        val port = serviceInfo.port
        val expirationTime = parseExpirationTime(serviceInfo.toString())
        val serviceName = serviceInfo.serviceName

        Log.d("EXPTIME", "$expirationTime")

        fun getHighestNumberedString(strings: List<String>): String {
            return strings.maxByOrNull {
                """\((\d+)\)""".toRegex().find(it)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            } ?: strings.first() // Fallback to first if all are unnumbered
        }

        fun update() {
            adbPort = port
            bestExpirationTime = expirationTime
            bestServiceName = serviceName
            Log.d(TAG, "Updated best match: $adbPort, $bestServiceName, $bestExpirationTime")
        }

        // If nothing set yet, be the first.
        if (adbPort == null) {
            Log.d(TAG, "ADB port not yet set, updating best match...")
            update()
            return
        }

        // If something already set, but we have new expiration time data...
        if (expirationTime != null) {
            // And if best expiration time is not set yet, update.
            if (bestExpirationTime == null) {
                Log.d(TAG, "Expiration time not yet set, updating best match...")
                update()
                return
            }

            // And if expiration time data is better than the best, update.
            if (expirationTime > bestExpirationTime!!) {
                Log.d(TAG, "Expiration time is better, updating best match...")
                update()
                return
            } else {
                // If worse, don't set.
                return
            }
        }

        // If something already set, but we have new service name data, try to see if
        // the service name is newer.
        // Ex. ADB, ADB (2), ADB (3)
        if (serviceName == getHighestNumberedString(listOf(bestServiceName ?: "", serviceName))) {
            Log.d(TAG, "Service name is newer, updating best match...")
            update()
            return
        }
    }

    /**
     * If expiration time is included in the txtRecord, extract it and convert it to epoch time.
     */
    private fun parseExpirationTime(rawString: String): Long? {
        val regex = """expirationTime: (\S+)""".toRegex()
        val expirationTimeStr = regex.find(rawString)?.groupValues?.get(1)

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")

        return try {
            dateFormat.parse(expirationTimeStr ?: "")?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * When a service is resolved, handle it.
     */
    private fun handleResolvedService(serviceInfo: NsdServiceInfo) {
        Log.d(TAG, "Resolve successful: $serviceInfo")
        Log.d(TAG, "Port: ${serviceInfo.port}")

        val ipAddress = getLocalIpAddress()
        Log.d("IP ADDRESS", ipAddress ?: "N/A")

        val discoveredAddress = getHostAddress(serviceInfo)
        if (ipAddress != null && discoveredAddress != ipAddress) {
            Log.d(TAG, "IP does not match device, but not skipping...")
            //return
        }

        if (serviceInfo.port == 0) {
            Log.d(TAG, "Port is zero, skipping...")
            return
        }

        updateIfNewest(serviceInfo)
    }

    /**
     * Returns the resolved host address of a service, or null if it has none.
     */
    private fun getHostAddress(serviceInfo: NsdServiceInfo): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            serviceInfo.host?.hostAddress
        }
    }

    /**
     * When service gets discovered, attempt to resolve it.
     */
    private fun resolveService(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerServiceInfoCallback(service)
        } else {
            resolveServiceLegacy(service)
        }
    }

    /**
     * Mark a service as no longer pending, and report when nothing is left to resolve.
     */
    private fun finishResolve(serviceInfo: NsdServiceInfo) {
        pendingServices.removeAll { it.serviceName == serviceInfo.serviceName }

        if (pendingServices.isEmpty()) {
            pendingResolves.set(false)
        }

        Log.d(TAG, "Service resolved, pending: ${pendingServices.size}")
    }

    /**
     * Resolve a service on API 34 and up, where a callback replaces the one-shot resolve
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerServiceInfoCallback(service: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            private val handled = AtomicBoolean(false)

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (!handled.compareAndSet(false, true)) {
                    return
                }

                handleResolvedService(serviceInfo)
                nsdManager.unregisterServiceInfoCallback(this)
            }

            override fun onServiceLost() {
                if (!handled.compareAndSet(false, true)) {
                    return
                }

                Log.d(TAG, "Service lost before it resolved: ${service.serviceName}")
                nsdManager.unregisterServiceInfoCallback(this)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode: ${service.serviceName}")
                resolveServiceLegacy(service)
            }

            override fun onServiceInfoCallbackUnregistered() {
                finishResolve(service)
            }
        }

        try {
            nsdManager.registerServiceInfoCallback(service, resolveExecutor, callback)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not register callback: ${service.serviceName}", e)
            resolveServiceLegacy(service)
        }
    }

    /**
     * Resolve a service on API 33 and below, and as a fallback when the callback fails
     */
    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: $errorCode: $serviceInfo")

                when (errorCode) {
                    // Re-run the resolve until it resolves.
                    NsdManager.FAILURE_ALREADY_ACTIVE -> resolveServiceLegacy(serviceInfo)
                    else -> finishResolve(serviceInfo)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolvedService(serviceInfo)
                finishResolve(serviceInfo)
            }
        }

        nsdManager.resolveService(service, resolveListener)
    }

    val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service discovery: $service")
            Log.d(TAG, "Port: ${service.port}")

            pendingServices.add(service)
            pendingResolves.set(true)
            Log.d(TAG, "Service found, pending: ${pendingServices.size}")

            resolveService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e(TAG, "Service lost: $service")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

}