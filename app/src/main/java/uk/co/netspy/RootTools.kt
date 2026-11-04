package uk.co.netspy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootTools {
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && output.contains("uid=0")
        } catch (_: Exception) { false }
    }

    suspend fun readArpTable(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/arp"))
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            lines.drop(1).mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 4 && fields[3].matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) {
                    fields[0] to fields[3].uppercase()
                } else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
}
