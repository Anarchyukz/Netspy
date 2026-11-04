#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="$HOME/.local/share/netspy-linux-client"
mkdir -p "$DEST" "$HOME/.local/bin" "$HOME/.local/share/applications"
if ! command -v apt-get >/dev/null 2>&1; then
  echo "This installer currently supports Debian/Ubuntu/Linux Mint."
  exit 1
fi
sudo apt-get update
sudo apt-get install -y python3 python3-tk iproute2 arp-scan policykit-1
install -m 755 "$ROOT/netspy_client.py" "$DEST/netspy_client.py"
cat > "$HOME/.local/bin/netspy-linux-client" <<EOF
#!/usr/bin/env bash
exec python3 "$DEST/netspy_client.py"
EOF
chmod 755 "$HOME/.local/bin/netspy-linux-client"
cat > "$HOME/.local/share/applications/netspy-linux-client.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Netspy Linux Discovery Client
Comment=Discover local network devices and MAC addresses for Netspy Android
Exec=$HOME/.local/bin/netspy-linux-client
Icon=network-wired
Terminal=false
Categories=Network;System;
EOF
chmod 644 "$HOME/.local/share/applications/netspy-linux-client.desktop"
echo
echo "Installed. Open the Mint menu and search for 'Netspy Linux Discovery Client'."
