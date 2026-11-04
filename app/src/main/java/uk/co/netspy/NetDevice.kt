package uk.co.netspy

data class NetDevice(
    val ip: String,
    val hostname: String = "",
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val openPorts: List<Int> = emptyList(),
    val mac: String? = null,
    val vendor: String? = null
)
