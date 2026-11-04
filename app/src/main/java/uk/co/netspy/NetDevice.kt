package uk.co.netspy

data class NetDevice(
    val ip: String,
    val hostname: String = "",
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val openPorts: List<Int> = emptyList(),
    val mac: String? = null,
    val vendor: String? = null,
    val deviceType: String = "Unknown device",
    val services: List<String> = emptyList(),
    val firstSeenMillis: Long = 0L,
    val lastSeenMillis: Long = 0L,
    val isNew: Boolean = false
)
