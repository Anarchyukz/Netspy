# Netspy Linux Discovery Client

A one-click Linux Mint/Ubuntu companion for Netspy Android. It performs an authorised local-LAN inventory using `arp-scan` (including MAC and vendor/OUI data), then exposes the latest inventory through a small authenticated HTTP API for the Android app to import.

## Install (Linux Mint)

1. Download/clone this repository.
2. Open the `linux-client` folder.
3. Right-click `install-linux.sh` → Properties → Permissions → allow executing as a program.
4. Double-click it and choose **Run in Terminal**. It installs `arp-scan`, Python Tk support and creates a Mint menu launcher.
5. Open the Mint menu and launch **Netspy Linux Discovery Client**.
6. Press **SCAN LOCAL NETWORK**. `pkexec` may ask for your Linux password to run arp-scan with the privileges it needs.

## Android pairing API

The desktop window displays the Linux host URL and a random pairing token. Both devices must be on the same trusted LAN.

- `GET /api/status` — authenticated client status.
- `GET /api/devices` — authenticated inventory JSON.
- `POST /api/scan` — starts a new scan.

Send the token as `Authorization: Bearer <token>`. The token is generated once and stored in `~/.config/netspy/client-token` with user-only file permissions. The API binds to all interfaces on port 8765 and rejects requests without the token. Do not forward this port to the internet; use only on a trusted home network.

## Notes

- `arp-scan` can discover MAC addresses for devices on the same Layer-2 broadcast network. It cannot discover peer MAC addresses across routers/VLANs.
- If privileged scanning is unavailable, the client falls back to Linux's neighbour cache (`ip neigh`), which may contain fewer devices.
- Vendor identification depends on the OUI/vendor string returned by `arp-scan`.
- This tool is for networks you own or are authorised to assess.
