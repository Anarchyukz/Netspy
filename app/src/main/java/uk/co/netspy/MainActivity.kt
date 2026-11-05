package uk.co.netspy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

private val Bg = Color(0xFF090D16)
private val Panel = Color(0xFF141C2B)
private val Accent = Color(0xFF35D0BA)
private val Muted = Color(0xFF8B9AB0)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
        setContent { NetspyApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetspyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(DeviceHistory.load(context)) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Ready to scan your Wi-Fi network") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var rootEnabled by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var monitoring by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<NetDevice?>(null) }
    var diagnostic by remember { mutableStateOf("Run a diagnostic to see results.") }
    val prefix = remember { NetworkScanner.localPrefix(context) ?: "Not connected" }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, secondary = Color(0xFF829BFF), background = Bg, surface = Panel,
        onPrimary = Bg, onBackground = Color(0xFFEAF0FA), onSurface = Color(0xFFEAF0FA)
    )) {
        Scaffold(containerColor = Bg, topBar = {
            TopAppBar(
                title = { Column { Text("NETSPY", fontWeight = FontWeight.Black); Text("NETWORK INTELLIGENCE", style = MaterialTheme.typography.labelSmall, color = Muted) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                actions = { Text("● LIVE", color = Accent, modifier = Modifier.padding(end = 18.dp), style = MaterialTheme.typography.labelMedium) }
            )
        }, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Overview", "Devices", "Tools", "Security", "Settings").forEachIndexed { index, label ->
                    val icon = when(index) { 0 -> Icons.Default.Home; 1 -> Icons.Default.PhoneAndroid; 2 -> Icons.Default.Build; 3 -> Icons.Default.Security; else -> Icons.Default.Settings }
                    NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(10.dp))
                when (selectedTab) {
                    0 -> OverviewScreen(prefix, devices.count { it.reachable }, devices.count { it.reachable && it.isNew }, devices.count { it.reachable && (it.vendor.isNullOrBlank() || it.deviceType.equals("Unknown device", true)) }, scanning, progress, status, monitoring,
                        onScan = {
                            if (!scanning) scope.launch {
                                scanning = true; progress = 0; status = "Scanning $prefix.0/24…"
                                try {
                                    devices = NetworkScanner.scan(context) { done, total -> progress = done * 100 / total }
                                    // Scanning creates neighbour-cache entries; read them in both modes,
                                    // with extra kernel access when the user has enabled root enrichment.
                                    val arp = RootTools.readArpTable(useRoot = rootEnabled)
                                    devices = devices.map { device ->
                                        val mac = arp[device.ip] ?: device.mac
                                        device.copy(mac = mac, vendor = DeviceHistory.manufacturerFor(mac) ?: device.vendor)
                                    }
                                    val scanned = DeviceHistory.record(context, devices)
                                    val onlineByIp = scanned.associateBy { it.ip }
                                    devices = DeviceHistory.load(context).map { old -> onlineByIp[old.ip] ?: old }
                                    status = "Scan complete · ${scanned.size} devices found"
                                } catch (e: Exception) { status = e.message ?: "Scan failed" }
                                scanning = false
                            }
                        },
                        onMonitor = {
                            monitoring = !monitoring
                            val intent = Intent(context, MonitorService::class.java)
                            if (monitoring) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
                        })
                    1 -> DevicesScreen(devices, onSelect = { selectedDevice = it })
                    2 -> ToolsScreen(diagnostic, onPing = { ip ->
                        scope.launch { diagnostic = try {
                            val start = System.currentTimeMillis()
                            val ok = java.net.InetAddress.getByName(ip).isReachable(2000)
                            if (ok) "Ping response from $ip · ${System.currentTimeMillis()-start} ms" else "No ICMP response from $ip (some devices block ping)."
                        } catch (e: Exception) { e.message ?: "Ping failed" } }
                    }, onDns = { host ->
                        scope.launch { diagnostic = try { "${host}: " + java.net.InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "" } } catch(e: Exception) { e.message ?: "DNS lookup failed" } }
                    })
                    3 -> SecurityScreen(onPortAudit = { ip -> scope.launch { diagnostic = withContext(kotlinx.coroutines.Dispatchers.IO) { SecurityAudit.checkPorts(ip) } } }, onFingerprint = { ip -> scope.launch { diagnostic = withContext(kotlinx.coroutines.Dispatchers.IO) { SecurityAudit.fingerprintServices(ip) } } }, onWebAudit = { host -> scope.launch { diagnostic = withContext(kotlinx.coroutines.Dispatchers.IO) { SecurityAudit.inspectHttps(host) } } }, onWebsiteScan = { url -> scope.launch { diagnostic = withContext(kotlinx.coroutines.Dispatchers.IO) { WebsiteSafetyScanner.scan(url) } } }, result = diagnostic)
                    else -> SettingsScreen(rootAvailable, rootEnabled, onRootCheck = {
                        scope.launch { rootAvailable = RootTools.isRootAvailable(); status = if(rootAvailable) "Root access available" else "Root access not detected" }
                    }, onRootToggle = { rootEnabled = it })
                }
            }
        }
        selectedDevice?.let { device ->
            AlertDialog(onDismissRequest = { selectedDevice = null }, title = { Text(device.hostname.ifBlank { device.ip }) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("IP address: ${device.ip}")
                    Text("Status: ${if (device.reachable) "Online" else "Offline"}")
                    Text("Device type: ${device.deviceType}")
                    Text("Manufacturer: ${device.vendor ?: "Unknown"}")
                    Text("Last seen: ${if (device.lastSeenMillis > 0) android.text.format.DateFormat.format("dd MMM, HH:mm", Date(device.lastSeenMillis)).toString() else "Not recorded"}")
                    Text("Latency: ${device.latencyMs?.let { "$it ms" } ?: "Unknown"}")
                    Text("Open TCP ports: ${device.openPorts.joinToString().ifBlank { "None detected" }}")
                    if (device.services.isNotEmpty()) Text("Advertised services: " + device.services.joinToString("\n"))
                    Text("MAC: ${device.mac ?: "Unavailable without root/ARP cache"}")
                } }, confirmButton = { TextButton(onClick = { selectedDevice = null }) { Text("Close") } })
        }
    }
}

@Composable
private fun OverviewScreen(prefix: String, count: Int, newCount: Int, unknownCount: Int, scanning: Boolean, progress: Int, status: String, monitoring: Boolean, onScan: () -> Unit, onMonitor: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("YOUR NETWORK", color = Muted, style = MaterialTheme.typography.labelMedium); Text(prefix, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Surface(color = Accent.copy(alpha=.12f), shape = RoundedCornerShape(20.dp)) { Text(" PRIVATE LAN ", color = Accent, modifier = Modifier.padding(9.dp), style = MaterialTheme.typography.labelSmall) }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("CONNECTED", count.toString(), Modifier.weight(1f))
            MetricCard("NEW", newCount.toString(), Modifier.weight(1f))
            MetricCard("UNKNOWN", unknownCount.toString(), Modifier.weight(1f))
        } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NETWORK DISCOVERY", fontWeight = FontWeight.Bold)
                Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
                if(scanning) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Button(onClick = onScan, enabled = !scanning, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text(if(scanning) "SCANNING $progress%" else "SCAN MY NETWORK", fontWeight = FontWeight.Bold)
                }
            }
        } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("CONTINUOUS MONITOR", fontWeight = FontWeight.Bold); Text("Keep a foreground monitor active", color = Muted, style = MaterialTheme.typography.bodySmall) }
                Switch(checked = monitoring, onCheckedChange = { onMonitor() })
            }
        } }
        item { Text("QUICK INTEL", color = Muted, style = MaterialTheme.typography.labelMedium) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Security, tint = Accent, contentDescription = null); Spacer(Modifier.width(10.dp)); Text("Only scan networks you own or are authorised to assess.", color = Muted, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) { Text(label, color = Muted, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun DevicesScreen(devices: List<NetDevice>, onSelect: (NetDevice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DISCOVERED DEVICES", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${devices.count { it.reachable }} online · ${devices.size} known devices in history", color = Muted, style = MaterialTheme.typography.bodySmall)
        if(devices.isEmpty()) Text("No scan results yet. Run a network scan from Overview.", color = Muted, modifier = Modifier.padding(top=20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.ip }) { device ->
                Card(Modifier.fillMaxWidth().clickable { onSelect(device) }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, tint = Accent, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.hostname.ifBlank { "Unknown device" }, fontWeight = FontWeight.SemiBold)
                            Text("${device.vendor ?: "Manufacturer unknown"} · ${device.deviceType}", color = Accent, style = MaterialTheme.typography.labelSmall)
                            Text("IP  ${device.ip}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text("MAC  ${device.mac ?: "Unavailable on this Android/network"}", color = if (device.mac != null) Accent else Muted, style = MaterialTheme.typography.labelSmall)
                            Text("Last seen " + if (device.lastSeenMillis > 0) Date(device.lastSeenMillis).toString() else "just now", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) { Text(if (device.reachable) "ONLINE" else "OFFLINE", color = if (device.reachable) Accent else Muted, style = MaterialTheme.typography.labelSmall); Text("${device.openPorts.size} ports", color = Muted, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsScreen(result: String, onPing: (String) -> Unit, onDns: (String) -> Unit) {
    var target by remember { mutableStateOf("192.168.0.1") }
    var host by remember { mutableStateOf("example.com") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("NETWORK TOOLS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("IP address") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onPing(target.trim()) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("PING HOST") }
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Hostname") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onDns(host.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("DNS LOOKUP") }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Text(result, modifier = Modifier.padding(16.dp), color = Muted) }
        Text("Traceroute is planned for a later release. Service identification is collected during network scans.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(rootAvailable: Boolean, rootEnabled: Boolean, onRootCheck: () -> Unit, onRootToggle: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OPTIONAL ROOT ENHANCEMENTS", fontWeight = FontWeight.Bold)
                Text("Root can expose the kernel ARP cache for extra MAC-address information. Standard scanning does not need root.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(if(rootAvailable) "Root access detected" else "Root not checked / unavailable", color = if(rootAvailable) Accent else Muted)
                OutlinedButton(onClick = onRootCheck, modifier = Modifier.fillMaxWidth()) { Text("CHECK ROOT ACCESS") }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Use root ARP enrichment", modifier = Modifier.weight(1f)); Switch(checked = rootEnabled, onCheckedChange = onRootToggle, enabled = rootAvailable) }
            }
        }
        Text("Netspy 0.1.0 · Local-first · No ads · No account", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
private fun SecurityScreen(onPortAudit: (String) -> Unit, onFingerprint: (String) -> Unit, onWebAudit: (String) -> Unit, onWebsiteScan: (String) -> Unit, result: String) {
    var target by remember { mutableStateOf("192.168.0.1") }
    var host by remember { mutableStateOf("example.com") }
    var authorised by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("SECURITY TOOLKIT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        item { Text("Read-only checks for networks and services you own or have permission to test.", color = Muted, style = MaterialTheme.typography.bodySmall) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.VerifiedUser, tint = Accent, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("WEBSITE SAFETY SCANNER", fontWeight = FontWeight.Bold) }
            Text("Check a website for phishing warning signs, suspicious redirects, certificate problems, risky forms and misleading page content.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = websiteUrl, onValueChange = { websiteUrl = it }, label = { Text("Website URL") }, placeholder = { Text("https://example.com") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onWebsiteScan(websiteUrl.trim()) }, enabled = websiteUrl.trim().isNotBlank() && websiteUrl.trim() != "https://", modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
                Icon(Icons.Default.Shield, null); Spacer(Modifier.width(8.dp)); Text("CHECK WEBSITE FOR SCAMS", fontWeight = FontWeight.Bold)
            }
            Text("Passive checks only. Netspy will not log in, submit forms or download files.", color = Muted, style = MaterialTheme.typography.labelSmall)
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AUTHORISED TESTING", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = authorised, onCheckedChange = { authorised = it }); Text("I own this system or have permission to test it.", color = Muted, style = MaterialTheme.typography.bodySmall) }
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TCP EXPOSURE AUDIT", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Single IP or hostname") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onPortAudit(target.trim()) }, enabled = authorised && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("CHECK COMMON PORTS") }
            Text("Bounded connection checks only; no exploit attempts or password guessing.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SERVICE FINGERPRINTING", fontWeight = FontWeight.Bold)
            Text("Read service greetings and basic protocol identifiers on one target.", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onFingerprint(target.trim()) }, enabled = authorised && target.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("IDENTIFY SERVICES") }
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("HTTPS CONFIGURATION", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Hostname") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = { onWebAudit(host.trim()) }, enabled = authorised && host.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("INSPECT TLS & SECURITY HEADERS") }
        } } }
        item { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Text(result, modifier = Modifier.padding(16.dp), color = Muted) } }
        item { Text("Advanced root/Shizuku integrations, traceroute and controlled training-lab modules can follow. Netspy does not attempt exploitation.", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}
