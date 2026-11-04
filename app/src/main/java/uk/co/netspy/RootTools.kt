package uk.co.netspy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootTools {
    private val macPattern = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && output.contains("uid=0")
        } catch (_: Exception) { false }
    }

    suspend fun readArpTable(useRoot: Boolean = false): Map<String, String> = withContext(Dispatchers.IO) {
        val entries = linkedMapOf<String, String>()

        fun parseProc(lines: List<String>) {
            lines.drop(1).forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 4 && macPattern.matches(fields[3]) &&
                    fields[3] != "00:00:00:00:00:00") {
                    entries[fields[0]] = fields[3].uppercase()
                }
            }
        }

        fun parseNeighbours(lines: List<String>) {
            lines.forEach { line ->
                val ip = line.trim().split(Regex("\\s+")).firstOrNull() ?: return@forEach
                val mac = Regex("(?i)lladdr\\s+((?:[0-9a-f]{2}:){5}[0-9a-f]{2})")
                    .find(line)?.groupValues?.getOrNull(1)
                if (mac != null && mac != "00:00:00:00:00:00") entries[ip] = mac.uppercase()
            }
        }

        try { parseProc(java.io.File("/proc/net/arp").readLines()) } catch (_: Exception) { }
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            parseNeighbours(process.inputStream.bufferedReader().readLines())
            process.waitFor()
        } catch (_: Exception) { }

        if (useRoot) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/arp; ip neigh show"))
                val lines = process.inputStream.bufferedReader().readLines()
                parseProc(lines.filter { it.contains("Device") || it.trim().split(Regex("\\s+")).size >= 4 })
                parseNeighbours(lines)
                process.waitFor()
            } catch (_: Exception) { }
        }
        entries
    }
}
