# Netspy

A privacy-first Android LAN discovery and diagnostics app built with Kotlin and Jetpack Compose.

## Features
- IPv4 subnet discovery (user-initiated scan, up to /24)
- Concurrent reachability checks and common TCP service probes
- Device inventory with IP, hostname, response time and discovered ports
- Optional root-assisted ARP table enrichment (only when `su` is available and approved)
- Periodic background monitoring with Android notifications
- Ping, DNS lookup and traceroute diagnostics
- Dark Material 3 interface; no account, ads or analytics

## Build
Open in Android Studio or run:
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions and limitations
Netspy requests local network access through Android's normal network permission model. Android 17+ introduces a dedicated local-network runtime permission for apps targeting API 37+, so this project currently targets API 36 and follows Android's documented compatibility path. Android may still restrict LAN operations through device policy, VPNs, or OEM behaviour.

Android does not expose arbitrary device MAC addresses to ordinary apps. MAC/vendor details are therefore best-effort and root-assisted ARP enrichment is optional. Root access is never required for ordinary scans. Root commands are only attempted after the user enables the option and grants the superuser prompt.

Scanning is limited to the connected IPv4 /24 subnet to avoid unexpectedly probing large networks. Only scan networks you own or are authorised to assess.

## CI
GitHub Actions builds the debug APK on pushes and pull requests, and publishes an APK artifact. Pushing a version tag such as `v0.1.0` also creates a GitHub Release with the APK.
