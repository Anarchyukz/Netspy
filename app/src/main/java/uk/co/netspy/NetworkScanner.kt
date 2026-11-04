package uk.co.netspy

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object NetworkScanner {
    private val commonPorts = listOf(21, 22, 23, 53, 80, 139, 443, 445, 554, 631, 8080, 8443)

    fun localPrefix(context: Context): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.dhcpInfo?.ipAddress ?: 0
            if (ip == 0) return null
            val a = ip and 0xff
            val b = (ip shr 8) and 0xff
            val c = (ip shr 16) and 0xff
            "$a.$b.$c"
        } catch (_: Exception) { null }
    }

    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit): List<NetDevice> = withContext(Dispatchers.IO) {
        val prefix = localPrefix(context) ?: throw IllegalStateException("Connect to Wi-Fi before scanning.")
        val semaphore = Semaphore(32)
        val completed = AtomicInteger(0)
        val found = coroutineScope {
            (1..254).map { host ->
                async {
                    semaphore.withPermit {
                        val ip = "$prefix.$host"
                        var reachable = false
                        var latency: Long? = null
                        try {
                            val address = InetAddress.getByName(ip)
                            val elapsed = measureTimeMillis { reachable = address.isReachable(650) }
                            if (reachable) latency = elapsed
                        } catch (_: Exception) { }
                        // Many phones, printers and IoT devices ignore ICMP. Probe TCP as a second discovery signal.
                        val ports = probePorts(ip)
                        reachable = reachable || ports.isNotEmpty()
                        val name = if (reachable) try {
                            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: ""
                        } catch (_: Exception) { "" } else ""
                        if (reachable) NetDevice(ip, name, true, latency, ports) else null
                    }
                }
            }.mapIndexed { index, deferred ->
                deferred.invokeOnCompletion { onProgress(completed.incrementAndGet(), 254) }
                deferred
            }.awaitAll().filterNotNull()
        }
        val inventory = found.associateBy { it.ip }.toMutableMap()
        // mDNS/DNS-SD and SSDP can reveal devices that ignore ping and expose useful service names.
        val advertised = ServiceDiscovery.discover(context)
        advertised.filter { it.ip.startsWith("$prefix.") && it.ip.count { ch -> ch == '.' } == 3 }
            .forEach { service ->
                val current = inventory[service.ip] ?: NetDevice(
                    ip = service.ip,
                    hostname = service.name,
                    reachable = true
                )
                val serviceLabel = "${service.name} · ${service.type}".trim()
                inventory[service.ip] = current.copy(
                    hostname = current.hostname.ifBlank { service.name },
                    deviceType = ServiceDiscovery.classify(service),
                    services = (current.services + serviceLabel).distinct()
                )
            }
        inventory.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
    }

    private suspend fun probePorts(ip: String): List<Int> = withContext(Dispatchers.IO) {
        commonPorts.mapNotNull { port ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 180)
                }
                port
            } catch (_: Exception) { null }
        }
    }
}
