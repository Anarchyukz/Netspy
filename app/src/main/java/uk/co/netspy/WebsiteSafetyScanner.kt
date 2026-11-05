package uk.co.netspy

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Passive website checks only. This is a warning-sign analyser, not a definitive
 * reputation verdict or malware sandbox. It never submits forms or follows HTTP downgrades.
 */
object WebsiteSafetyScanner {
    fun scan(input: String): String {
        val raw = input.trim()
        if (raw.isBlank()) return "Enter a website address, for example https://example.com"
        val candidate = if (raw.contains("://")) raw else "https://$raw"
        val initial = try { URI(candidate).normalize() } catch (_: Exception) {
            return "The address could not be parsed. Check the URL and try again."
        }
        val scheme = initial.scheme?.lowercase()
        if (scheme !in listOf("http", "https")) return "Only http:// and https:// website addresses are supported."
        if (initial.rawUserInfo != null) return "Blocked: this URL contains embedded username/password information."
        val hostInput = initial.host ?: return "The URL has no valid hostname."
        val host = try { IDN.toASCII(hostInput, IDN.USE_STD3_ASCII_RULES).lowercase() } catch (_: Exception) {
            return "The hostname contains invalid characters."
        }
        if (host == "localhost" || host.endsWith(".local")) return "Blocked: local network hostnames are not scanned by the website checker."
        val flags = mutableListOf<Pair<Int, String>>()
        var score = 0
        fun flag(points: Int, message: String) { score += points; flags += points to message }

        if (scheme != "https") flag(25, "The address starts with HTTP, not encrypted HTTPS.")
        if (host.startsWith("xn--") || host.split(".").any { it.startsWith("xn--") }) flag(20, "The hostname uses internationalised/punycode characters; check carefully for lookalike letters.")
        if (host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) flag(20, "The website uses a raw IPv4 address instead of a normal domain.")
        if (host.split(".").size > 4) flag(8, "The hostname has an unusually large number of subdomains.")
        if (host.count { it == '-' } >= 3) flag(8, "The hostname contains several hyphens.")
        if (raw.length > 180) flag(8, "The URL is unusually long.")
        if (Regex("(?i)(login|verify|account|secure|wallet|password|gift|claim|urgent|payment)").containsMatchIn(raw)) flag(5, "The URL contains words commonly used in credential or payment lures; this is only a weak signal.")

        val resolved = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            flag(20, "The domain did not resolve in DNS from this device.")
            emptyList()
        }
        if (resolved.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
            return "Blocked: the hostname resolves to a private, local or reserved network address. Netspy will not fetch it."
        }

        if (scheme == "http") {
            return report(host, score, flags, null, null, "Page content was not fetched because the site uses unencrypted HTTP.")
        }

        var current = URI("https", null, host, initial.port, initial.path?.ifBlank { "/" } ?: "/", initial.query, null)
        var connection: HttpsURLConnection? = null
        var finalUrl = current.toString()
        var pageText = ""
        var pageTitle = ""
        var responseCode: Int? = null
        var certSummary: String? = null
        var headers = emptyMap<String, String>()
        try {
            for (hop in 0..5) {
                val conn = URL(current.toString()).openConnection() as HttpsURLConnection
                connection = conn
                conn.connectTimeout = 6500
                conn.readTimeout = 6500
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Netspy-Website-Inspector/1.0")
                conn.setRequestProperty("Range", "bytes=0-262143")
                conn.connect()
                val code = conn.responseCode
                responseCode = code
                val cert = conn.serverCertificates.firstOrNull() as? java.security.cert.X509Certificate
                certSummary = cert?.let {
                    "Certificate issued to: ${it.subjectX500Principal.name}\nCertificate issuer: ${it.issuerX500Principal.name}\nCertificate expires: ${it.notAfter}\nCertificate currently valid: " +
                        runCatching { it.checkValidity(); true }.getOrDefault(false)
                } ?: "Certificate details unavailable"
                headers = listOf("Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy").associateWith { conn.getHeaderField(it) ?: "Not present" }
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    if (hop == 5) flag(10, "The site uses too many redirects.")
                    val next = current.resolve(location)
                    if (next.scheme?.lowercase() != "https") {
                        flag(25, "A redirect attempts to downgrade the connection away from HTTPS.")
                        finalUrl = next.toString()
                        break
                    }
                    if (next.host != null && !next.host.equals(host, true)) {
                        flag(5, "The site redirects to a different hostname: ${next.host}")
                        val redirectAddresses = runCatching { InetAddress.getAllByName(next.host).toList() }.getOrDefault(emptyList())
                        if (redirectAddresses.isEmpty() || redirectAddresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
                            flag(20, "A redirect points to a private, local or unresolvable host; further inspection was stopped.")
                            break
                        }
                    }
                    current = next
                    finalUrl = current.toString()
                    conn.disconnect()
                    connection = null
                } else {
                    finalUrl = current.toString()
                    val stream = runCatching { conn.inputStream }.getOrNull()
                    if (stream != null) {
                        pageText = stream.use { input ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                            var remaining = 262144
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                            output.toString(Charsets.UTF_8.name())
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            flag(10, "The HTTPS page could not be fully inspected: ${e.message ?: "connection error"}.")
        } finally { connection?.disconnect() }

        pageTitle = Regex("(?is)<title[^>]*>(.*?)</title>").find(pageText)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(160) ?: ""
        val plain = pageText.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").lowercase()
        val lurePhrases = listOf("account suspended", "verify your account", "confirm your password", "unusual activity", "act immediately", "within 24 hours", "claim your prize", "you have won", "gift card", "pay a small fee", "delivery fee")
        val foundLures = lurePhrases.filter { plain.contains(it) }
        if (foundLures.size >= 2) flag(15, "The page text contains multiple urgency, prize or account-verification phrases: ${foundLures.take(4).joinToString()}.")
        else if (foundLures.isNotEmpty()) flag(5, "The page contains a potentially manipulative phrase: ${foundLures.first()}.")

        val formTags = Regex("(?is)<form\\b([^>]*)>").findAll(pageText).map { it.groupValues[1] }.toList()
        val externalForm = formTags.any { attrs ->
            val action = Regex("(?i)action\\s*=\\s*['\"]?([^'\"\\s>]+)").find(attrs)?.groupValues?.get(1)
            if (action.isNullOrBlank()) false else runCatching {
                val actionUri = current.resolve(action)
                actionUri.host != null && !actionUri.host.equals(current.host, true)
            }.getOrDefault(false)
        }
        val hasPassword = Regex("(?is)<input[^>]+type\\s*=\\s*['\"]?password").containsMatchIn(pageText)
        if (externalForm) flag(20, "A form appears to submit information to a different hostname.")
        if (hasPassword && !externalForm) flag(3, "The page contains a password field; verify the organisation and address before entering credentials.")

        val risk = when {
            score >= 50 -> "HIGH RISK"
            score >= 25 -> "SUSPICIOUS"
            score >= 10 -> "CAUTION"
            else -> "NO MAJOR WARNING SIGNS FOUND"
        }
        val extra = buildString {
            appendLine("Final address: $finalUrl")
            appendLine("HTTP response: ${responseCode ?: "Unavailable"}")
            if (pageTitle.isNotBlank()) appendLine("Page title: $pageTitle")
            if (certSummary != null) appendLine(certSummary)
            if (headers.isNotEmpty()) {
                appendLine("Security headers:")
                headers.forEach { (name, value) -> appendLine("  $name: $value") }
            }
            appendLine("DNS addresses: " + if (resolved.isEmpty()) "Unavailable" else resolved.joinToString { it.hostAddress })
        }
        return report(host, score, flags, extra, risk, null)
    }

    private fun report(host: String, score: Int, flags: List<Pair<Int, String>>, details: String?, riskOverride: String?, note: String?): String {
        val risk = riskOverride ?: when {
            score >= 50 -> "HIGH RISK"
            score >= 25 -> "SUSPICIOUS"
            score >= 10 -> "CAUTION"
            else -> "NO MAJOR WARNING SIGNS FOUND"
        }
        return buildString {
            appendLine("NETSPY WEBSITE SAFETY REPORT")
            appendLine("Domain: $host")
            appendLine("Risk score: $score/100 · $risk")
            appendLine()
            if (flags.isEmpty()) appendLine("No major warning signs were detected by the checks available on this device.")
            else flags.forEach { (points, message) -> appendLine("• +$points  $message") }
            if (!details.isNullOrBlank()) { appendLine(); append(details) }
            if (!note.isNullOrBlank()) { appendLine(); append(note) }
            appendLine()
            append("IMPORTANT: This is a heuristic check, not proof that a site is legitimate or fraudulent. New scam domains may have valid HTTPS certificates. Domain registration age and live threat-intelligence databases are not yet checked.")
        }
    }
}
