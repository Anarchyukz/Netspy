#!/usr/bin/env python3
"""Netspy Linux Discovery Client — LAN inventory bridge for Netspy Android."""
import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import secrets
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import tkinter as tk
from tkinter import ttk, messagebox

APP_DIR = Path.home() / ".config" / "netspy"
TOKEN_FILE = APP_DIR / "client-token"
PORT = 8765
MAC_RE = re.compile(r"(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
lock = threading.RLock()
devices = []
last_scan = None
scan_busy = False

def token_value():
    APP_DIR.mkdir(parents=True, exist_ok=True)
    try:
        value = TOKEN_FILE.read_text().strip()
        if len(value) >= 32:
            return value
    except OSError:
        pass
    value = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(value)
    TOKEN_FILE.chmod(0o600)
    return value

TOKEN = token_value()

def network_details():
    try:
        out = subprocess.check_output(["ip", "-o", "route", "get", "1.1.1.1"], text=True, timeout=3)
        fields = out.split()
        iface = fields[fields.index("dev") + 1]
        local_ip = fields[fields.index("src") + 1]
        return iface, local_ip
    except Exception:
        return None, None

def parse_arp_scan(output):
    found = {}
    for line in output.splitlines():
        match = re.match(r"^\s*(\d{1,3}(?:\.\d{1,3}){3})\s+(" + MAC_RE.pattern + r")\s*(.*)$", line)
        if not match:
            continue
        ip, mac, vendor = match.groups()
        found[ip] = {
            "ip": ip, "mac": mac.upper(), "vendor": vendor.strip() or None,
            "hostname": "", "reachable": True, "source": "arp-scan"
        }
    return found

def scan_network():
    global devices, last_scan, scan_busy
    with lock:
        if scan_busy:
            return devices, "A scan is already running."
        scan_busy = True
    try:
        iface, local_ip = network_details()
        result = {}
        if shutil.which("arp-scan") and iface:
            command = ["arp-scan", "--interface", iface, "--localnet", "--retry=2", "--timeout=500"]
            if os.geteuid() != 0:
                if shutil.which("pkexec"):
                    command = ["pkexec"] + command
                else:
                    command = ["sudo", "-n"] + command
            try:
                proc = subprocess.run(command, capture_output=True, text=True, timeout=90)
                if proc.returncode == 0:
                    result.update(parse_arp_scan(proc.stdout))
            except Exception:
                pass

        # Keep a useful fallback for systems where arp-scan is unavailable or elevation is denied.
        try:
            neigh = subprocess.check_output(["ip", "neigh", "show"], text=True, timeout=5)
            for line in neigh.splitlines():
                fields = line.split()
                if not fields:
                    continue
                ip = fields[0]
                mac_match = re.search(r"(?i)\blladdr\s+(" + MAC_RE.pattern + r")", line)
                if mac_match:
                    mac = mac_match.group(1).upper()
                    result.setdefault(ip, {
                        "ip": ip, "mac": mac, "vendor": None, "hostname": "",
                        "reachable": "FAILED" not in line and "INCOMPLETE" not in line,
                        "source": "Linux neighbour table"
                    })
        except Exception:
            pass

        # Resolve names without making discovery depend on DNS.
        for item in result.values():
            try:
                item["hostname"] = socket.gethostbyaddr(item["ip"])[0]
            except Exception:
                pass
        snapshot = sorted(result.values(), key=lambda x: tuple(int(p) for p in x["ip"].split(".")) if re.fullmatch(r"\d{1,3}(?:\.\d{1,3}){3}", x["ip"]) else (999, 999, 999, 999))
        with lock:
            devices = snapshot
            last_scan = int(time.time())
        return snapshot, f"Found {len(snapshot)} devices with MAC addresses."
    finally:
        with lock:
            scan_busy = False

class APIHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        return

    def authorized(self):
        return secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + TOKEN)

    def send_json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/api/status":
            if not self.authorized():
                return self.send_json(401, {"error": "Pairing token required"})
            iface, local_ip = network_details()
            with lock:
                return self.send_json(200, {"name": "Netspy Linux Client", "version": 1,
                    "ip": local_ip, "interface": iface, "port": PORT,
                    "last_scan": last_scan, "scanning": scan_busy, "count": len(devices)})
        if self.path == "/api/devices":
            if not self.authorized():
                return self.send_json(401, {"error": "Pairing token required"})
            with lock:
                return self.send_json(200, {"version": 1, "scanned_at": last_scan, "devices": devices})
        self.send_json(404, {"error": "Not found"})

    def do_POST(self):
        if self.path != "/api/scan":
            return self.send_json(404, {"error": "Not found"})
        if not self.authorized():
            return self.send_json(401, {"error": "Pairing token required"})
        threading.Thread(target=scan_network, daemon=True).start()
        self.send_json(202, {"status": "scan_started"})

def start_api():
    try:
        server = ThreadingHTTPServer(("0.0.0.0", PORT), APIHandler)
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
        return True
    except OSError:
        return False

class ClientApp:
    def __init__(self, root):
        self.root = root
        root.title("Netspy Linux Discovery Client")
        root.geometry("760x540")
        root.minsize(620, 420)
        root.configure(bg="#090D16")
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Treeview", background="#141C2B", foreground="#EAF0FA",
                        fieldbackground="#141C2B", rowheight=28, borderwidth=0)
        style.configure("Treeview.Heading", background="#202B3D", foreground="#35D0BA")
        style.configure("TButton", padding=9)
        header = tk.Frame(root, bg="#090D16")
        header.pack(fill="x", padx=20, pady=(18, 8))
        tk.Label(header, text="NETSPY", bg="#090D16", fg="#35D0BA",
                 font=("Sans", 22, "bold")).pack(anchor="w")
        tk.Label(header, text="LINUX DISCOVERY CLIENT", bg="#090D16", fg="#8B9AB0",
                 font=("Sans", 9, "bold")).pack(anchor="w")
        bar = tk.Frame(root, bg="#090D16")
        bar.pack(fill="x", padx=20, pady=8)
        self.scan_btn = tk.Button(bar, text="SCAN LOCAL NETWORK", command=self.scan,
            bg="#35D0BA", fg="#090D16", activebackground="#55E6D0",
            relief="flat", font=("Sans", 10, "bold"), padx=18, pady=10)
        self.scan_btn.pack(side="left")
        self.status = tk.Label(bar, text="Ready", bg="#090D16", fg="#8B9AB0")
        self.status.pack(side="left", padx=14)
        columns = ("ip", "mac", "vendor", "hostname")
        self.tree = ttk.Treeview(root, columns=columns, show="headings")
        for col, label, width in [("ip","IP ADDRESS",130),("mac","MAC ADDRESS",180),
                                  ("vendor","MANUFACTURER",180),("hostname","HOSTNAME",200)]:
            self.tree.heading(col, text=label)
            self.tree.column(col, width=width, minwidth=90)
        self.tree.pack(fill="both", expand=True, padx=20, pady=10)
        iface, local_ip = network_details()
        self.url = f"http://{local_ip}:{PORT}" if local_ip else f"http://<linux-ip>:{PORT}"
        pairing = tk.Frame(root, bg="#141C2B")
        pairing.pack(fill="x", padx=20, pady=(0, 18))
        tk.Label(pairing, text="ANDROID PAIRING", bg="#141C2B", fg="#35D0BA",
                 font=("Sans", 9, "bold")).pack(anchor="w", padx=12, pady=(10, 2))
        tk.Label(pairing, text=f"Server: {self.url}    ·    Interface: {iface or 'unknown'}",
                 bg="#141C2B", fg="#EAF0FA").pack(anchor="w", padx=12)
        tk.Label(pairing, text=f"Pairing token: {TOKEN}",
                 bg="#141C2B", fg="#8B9AB0", wraplength=700, justify="left").pack(anchor="w", padx=12, pady=(3, 10))
        if not start_api():
            messagebox.showerror("Netspy", f"Could not start API on port {PORT}. Is another client already running?")
        root.after(1500, self.refresh_status)

    def refresh_status(self):
        with lock:
            count = len(devices)
            active = scan_busy
        self.status.configure(text="Scanning…" if active else f"{count} devices cached · API ready")
        self.root.after(1000, self.refresh_status)

    def scan(self):
        self.scan_btn.configure(state="disabled")
        self.status.configure(text="Scanning LAN and collecting MAC addresses…")
        def worker():
            _, message = scan_network()
            self.root.after(0, lambda: self.finished(message))
        threading.Thread(target=worker, daemon=True).start()

    def finished(self, message):
        for row in self.tree.get_children():
            self.tree.delete(row)
        with lock:
            snapshot = list(devices)
        for item in snapshot:
            self.tree.insert("", "end", values=(item["ip"], item["mac"], item.get("vendor") or "Unknown", item.get("hostname") or ""))
        self.status.configure(text=message)
        self.scan_btn.configure(state="normal")

if __name__ == "__main__":
    root = tk.Tk()
    ClientApp(root)
    root.mainloop()
