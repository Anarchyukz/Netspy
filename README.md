# Netspy

A privacy-first Android LAN discovery and diagnostics app built with Kotlin and Jetpack Compose.

## Included in v0.1.0
- IPv4 /24 subnet discovery with concurrent host checks
- Common TCP port probes and reverse-hostname lookup
- Device inventory with IP, response time, discovered ports and device detail view
- Ping and DNS lookup tools
- Optional root-assisted ARP table enrichment when `su` is available and approved
- Foreground network monitor: scans every 15 minutes and notifies when new IPs appear
- Dark Material 3 interface; no account, ads or analytics

## Build
Open the project in Android Studio or run `./gradlew assembleDebug`.
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions and limitations
Netspy uses Android's network permissions. Android 17 introduces a dedicated local-network runtime permission for apps targeting API 37+, so this release targets API 36 and follows Android's documented compatibility path. VPNs, device policies and OEM restrictions can still affect LAN discovery.

Android does not expose arbitrary device MAC addresses to ordinary apps. MAC details are best-effort and root-assisted ARP enrichment is optional. Root access is never required for ordinary scans. Root commands are attempted only after the user enables the option and approves the superuser prompt.

The monitor runs as a foreground service and performs a /24 scan every 15 minutes while enabled. Android may stop foreground work under battery, policy or service time limits. The first scan seeds the known-device list; subsequent newly seen IP addresses trigger alerts. A changed DHCP lease can look like a new device.

Scanning is limited to the connected IPv4 /24 subnet. Only scan networks you own or are authorised to assess.

## CI
GitHub Actions builds a debug APK on pushes and pull requests and uploads it as a 30-day workflow artifact. Pushing a version tag such as `v0.1.0` also creates a GitHub Release with the APK.
