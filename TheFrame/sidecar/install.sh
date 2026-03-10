#!/usr/bin/env bash
# TheFrame Sidecar — Raspberry Pi installer
# Tested on Raspberry Pi OS (Bookworm/Bullseye), Python 3.9+
# Run as root: sudo bash install.sh

set -e

INSTALL_DIR="/opt/theframe"
SERVICE_USER="${SUDO_USER:-pi}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> TheFrame Sidecar Installer"
echo "    Install dir : $INSTALL_DIR"
echo "    Service user: $SERVICE_USER"
echo ""

# --- Require root ---
if [[ $EUID -ne 0 ]]; then
  echo "ERROR: Please run with sudo: sudo bash install.sh"
  exit 1
fi

# --- System dependencies ---
echo "==> Installing system packages..."
apt-get update -qq
apt-get install -y python3 python3-venv python3-pip

# --- Create install directory ---
echo "==> Setting up $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR"/*.py "$INSTALL_DIR/"
cp "$SCRIPT_DIR/requirements.txt" "$INSTALL_DIR/"

# --- Config ---
if [[ -f "$INSTALL_DIR/config.yml" ]]; then
  echo "    Existing config.yml found, skipping copy."
else
  cp "$SCRIPT_DIR/config.example.yml" "$INSTALL_DIR/config.yml"
  echo ""
  echo "  !! ACTION REQUIRED: Edit $INSTALL_DIR/config.yml with your TV's IP and MAC address."
  echo ""
fi

# --- Python venv ---
echo "==> Creating Python virtual environment..."
python3 -m venv "$INSTALL_DIR/venv"
"$INSTALL_DIR/venv/bin/pip" install --upgrade pip -q
"$INSTALL_DIR/venv/bin/pip" install -r "$INSTALL_DIR/requirements.txt" -q

# --- Permissions ---
chown -R "$SERVICE_USER":"$SERVICE_USER" "$INSTALL_DIR"

# --- Systemd service ---
echo "==> Installing systemd service..."
# Patch the service user in case it's not 'pi'
sed "s/User=pi/User=$SERVICE_USER/" "$SCRIPT_DIR/theframe.service" \
  > /etc/systemd/system/theframe.service

systemctl daemon-reload
systemctl enable theframe.service

echo ""
echo "==> Done!"
echo ""
echo "Next steps:"
echo "  1. Edit /opt/theframe/config.yml (TV IP, MAC, inputs)"
echo "  2. Start the service: sudo systemctl start theframe"
echo "  3. Check status:      sudo systemctl status theframe"
echo "  4. View logs:         sudo journalctl -u theframe -f"
echo ""
echo "  On first connection, your TV will show a pairing prompt — accept it."
echo "  The token is saved to /opt/theframe/token.txt for future connections."
echo ""
echo "  API will be available at: http://$(hostname -I | awk '{print $1}'):8088"
