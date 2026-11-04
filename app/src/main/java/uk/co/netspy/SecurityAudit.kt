package uk.co.netspy

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Read-only checks for systems the user owns or is authorised to assess. */
object SecurityAudit {
    private val ports = listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 554, 631, 8080, 8443)

    fun checkPorts(target: String): String {
        if (target.isBlank() || target.any { it.isWhitespace() } || target.contains('/') || target.contains('@')) return "Enter one IP address or hostname; ranges and URL paths are not accepted."
        val open = mutableListOf<Int>()
        ports.forEach { port ->
            try { Socket().use { it.connect(InetSocketAddress(target, port), 450) }; open += port } catch (_: Exception) { }
        }
        return buildString {
            appendLine("TCP exposure audit: " + target)
            appendLine("Checked " + ports.size + " common TCP ports.")
            if (open.isEmpty()) appendLine("No checked TCP ports accepted a connection.") else {
                appendLine("Open ports: " + open.joinToString())
                if (21 in open) appendLine("FTP detected: prefer encrypted file transfer.")
                if (23 in open) appendLine("Telnet detected: replace with SSH.")
                if (80 in open) appendLine("HTTP detected: check whether HTTPS is available.")
                if (445 in open) appendLine("SMB exposed: restrict access to trusted devices.")
            }
            append("A listening port is not proof of a vulnerability; verify the service and configuration.")
        }
    }

    /** Reads protocol greeting banners from a single authorised host. */
    fun fingerprintServices(target: String): String {
        if (target.isBlank() || target.any { it.isWhitespace() } || target.contains('/') || target.contains('@')) return "Enter one IP address or hostname."
        val services = listOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 110 to "POP3", 143 to "IMAP", 80 to "HTTP", 8080 to "HTTP-alt")
        val findings = mutableListOf<String>()
        services.forEach { (port, label) ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target, port), 500)
                    socket.soTimeout = 900
                    val banner = runCatching {
                        if (port == 80 || port == 8080) {
                            socket.getOutputStream().write("HEAD / HTTP/1.0\\r\\nHost: $target\\r\\nConnection: close\\r\\n\\r\\n".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        val bytes = ByteArray(512)
                        val count = socket.getInputStream().read(bytes)
                        if (count > 0) String(bytes, 0, count).replace(Regex("[\\r\\n\\t]+"), " ").trim() else ""
                    }.getOrDefault("")
                    findings += "$port/$label" + if (banner.isNotBlank()) " · $banner" else " · connected; no banner"
                }
            } catch (_: Exception) { }
        }
        return buildString {
            appendLine("Service fingerprint: $target")
            if (findings.isEmpty()) appendLine("No recognised services responded on the selected ports.")
            else findings.forEach { appendLine(it.take(220)) }
            appendLine()
            appendLine("Review: Telnet (23) and clear-text FTP (21) expose credentials or data without encryption.")
            append("Banners can be hidden or misleading; version strings alone do not establish a known vulnerability.")
        }
    }

    fun inspectHttps(host: String): String {
        val clean = host.trim().removePrefix("https://").substringBefore('/').substringBefore(':')
        if (clean.isBlank() || clean.any { it.isWhitespace() } || clean.contains('@')) return "Enter a hostname only, for example example.com."
        var connection: HttpsURLConnection? = null
        return try {
            val conn = URL("https://" + clean).openConnection() as HttpsURLConnection
            connection = conn
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.connect()
            val cert = conn.serverCertificates.firstOrNull() as? java.security.cert.X509Certificate
            buildString {
                appendLine("HTTPS inspection: " + clean)
                appendLine("HTTP response: " + conn.responseCode)
                if (cert != null) {
                    appendLine("Certificate subject: " + cert.subjectX500Principal.name)
                    appendLine("Issuer: " + cert.issuerX500Principal.name)
                    appendLine("Valid until: " + cert.notAfter)
                    appendLine("Certificate valid now: " + runCatching { cert.checkValidity(); true }.getOrDefault(false))
                }
                listOf("Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy").forEach { header ->
                    appendLine(header + ": " + (conn.getHeaderField(header) ?: "Not present"))
                }
                append("Read-only inspection; missing headers are context-dependent, not automatically vulnerabilities.")
            }
        } catch (e: Exception) { "HTTPS inspection failed: " + (e.message ?: "Connection or certificate error") }
        finally { connection?.disconnect() }
    }
}
