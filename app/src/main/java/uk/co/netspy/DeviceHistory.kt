package uk.co.netspy

import android.content.Context

/**
 * Local-only inventory timestamps. IP is the fallback identity because Android
 * may not expose a peer MAC address on modern Wi-Fi networks.
 */
object DeviceHistory {
    private const val PREFS = "netspy_device_history"

    fun record(context: Context, devices: List<NetDevice>): List<NetDevice> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        val enriched = devices.map { device ->
            val key = device.ip.replace('.', '_')
            val storedFirst = prefs.getLong("first_$key", 0L)
            val first = if (storedFirst == 0L) now else storedFirst
            editor.putLong("first_$key", first)
            editor.putLong("last_$key", now)
            editor.putString("vendor_$key", device.vendor ?: manufacturerFor(device.mac))
            // Keep the last genuine MAC when Android temporarily hides the neighbour entry.
            val knownMac = device.mac ?: prefs.getString("mac_$key", null)
            editor.putString("mac_$key", knownMac)
            editor.putString("type_$key", device.deviceType)
            editor.putString("host_$key", device.hostname)
            val vendor = device.vendor ?: manufacturerFor(device.mac)
            device.copy(
                mac = device.mac ?: prefs.getString("mac_$key", null),
                vendor = vendor ?: manufacturerFor(prefs.getString("mac_$key", null)),
                firstSeenMillis = first,
                lastSeenMillis = now,
                isNew = storedFirst == 0L
            )
        }
        editor.putStringSet("known_ips", (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).toMutableSet().apply { addAll(devices.map { it.ip }) })
        editor.apply()
        return enriched
    }

    fun load(context: Context): List<NetDevice> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (prefs.getStringSet("known_ips", emptySet()) ?: emptySet()).mapNotNull { ip ->
            val key = ip.replace('.', '_')
            val last = prefs.getLong("last_$key", 0L)
            if (last == 0L) return@mapNotNull null
            NetDevice(
                ip = ip,
                hostname = prefs.getString("host_$key", "") ?: "",
                reachable = false,
                mac = prefs.getString("mac_$key", null),
                vendor = prefs.getString("vendor_$key", null),
                deviceType = prefs.getString("type_$key", "Unknown device") ?: "Unknown device",
                firstSeenMillis = prefs.getLong("first_$key", last),
                lastSeenMillis = last
            )
        }.sortedBy { it.ip }
    }

    fun manufacturerFor(mac: String?): String? {
        val oui = mac?.uppercase()?.replace('-', ':')?.split(':')?.take(3)?.joinToString(":") ?: return null
        return when (oui) {
            "3C:5A:B4", "F4:F5:D8", "AC:BC:32" -> "Google"
            "00:1A:11", "F0:27:2D", "A8:66:7F" -> "Google"
            "00:03:93", "00:17:F2", "3C:22:FB", "A4:83:E7" -> "Apple"
            "00:12:FB", "8C:F5:A3", "CC:3A:61", "E4:7D:BD" -> "Samsung"
            "00:FC:8B", "F0:9F:C2", "A0:02:DC" -> "Amazon"
            "00:1D:D8", "00:24:E8", "B4:2E:99" -> "Sony"
            "00:1B:FC", "00:26:18", "DC:A6:32" -> "Raspberry Pi / Broadcom"
            "00:1E:58", "00:24:01", "00:26:5A" -> "TP-Link"
            "00:1F:33", "00:22:6B", "00:26:BB" -> "Netgear"
            else -> null
        }
    }
}
