package uk.co.netspy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredService(
    val ip: String,
    val name: String,
    val type: String,
    val details: String = ""
)

object ServiceDiscovery {
    private val serviceTypes = listOf(
        "_http._tcp.", "_https._tcp.", "_googlecast._tcp.",
        "_airplay._tcp.", "_ipp._tcp.", "_printer._tcp.",
        "_smb._tcp.", "_ssh._tcp.", "_workstation._tcp."
    )

    suspend fun discover(context: Context): List<DiscoveredService> = withContext(Dispatchers.IO) {
        val results = ConcurrentHashMap<String, DiscoveredService>()
        coroutineScope {
            val nsdJob = async(Dispatchers.IO) { discoverMdns(context, results) }
            val ssdpJob = async(Dispatchers.IO) { discoverSsdp(context, results) }
            nsdJob.await()
            ssdpJob.await()
        }
        results.values.toList()
    }

    private suspend fun discoverMdns(
        context: Context,
        results: ConcurrentHashMap<String, DiscoveredService>
    ) {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listeners = mutableListOf<Pair<String, NsdManager.DiscoveryListener>>()

        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    try {
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val ip = info.host?.hostAddress ?: return
                                val serviceTypeLabel = info.serviceType.trim('.')
                                val key = "$ip|$serviceTypeLabel"
                                results[key] = DiscoveredService(
                                    ip = ip,
                                    name = info.serviceName,
                                    type = serviceTypeLabel,
                                    details = if (info.port > 0) "Port ${info.port}" else ""
                                )
                            }
                        })
                    } catch (_: Exception) { }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
                }
                override fun onDiscoveryStopped(serviceType: String) = Unit
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners += type to listener
            } catch (_: Exception) { }
        }

        // Give mDNS advertisements time to arrive and resolve.
        kotlinx.coroutines.delay(5_000)
        listeners.forEach { (_, listener) ->
            try { manager.stopServiceDiscovery(listener) } catch (_: Exception) { }
        }
    }

    private suspend fun discoverSsdp(
        context: Context,
        results: ConcurrentHashMap<String, DiscoveredService>
    ) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try { wifi?.createMulticastLock("netspy-ssdp")?.apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }
        try {
            withTimeoutOrNull(3_000) {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(0))
                    socket.soTimeout = 350
                    val target = InetAddress.getByName("239.255.255.250")
                    val request = listOf(
                        "M-SEARCH * HTTP/1.1",
                        "HOST: 239.255.255.250:1900",
                        "MAN: \"ssdp:discover\"",
                        "MX: 2",
                        "ST: ssdp:all",
                        "",
                        ""
                    ).joinToString("\r\n").toByteArray()
                    socket.send(DatagramPacket(request, request.size, target, 1900))
                    val buffer = ByteArray(8192)
                    while (true) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val response = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                            val headers = response.lineSequence().mapNotNull { line ->
                                val split = line.indexOf(':')
                                if (split > 0) line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim() else null
                            }.toMap()
                            val ip = packet.address.hostAddress ?: continue
                            val server = headers["server"].orEmpty()
                            val st = headers["st"] ?: headers["nt"] ?: "ssdp device"
                            val usn = headers["usn"].orEmpty()
                            val label = listOf(server, st, usn).filter { it.isNotBlank() }.joinToString(" · ")
                            results["$ip|ssdp|$st"] = DiscoveredService(ip, server.ifBlank { "UPnP device" }, "SSDP: $st", label)
                        } catch (_: SocketTimeoutException) { break }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock?.release() } catch (_: Exception) { }
        }
    }
}
