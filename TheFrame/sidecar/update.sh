#!/usr/bin/env bash
# TheFrame Sidecar — quick update script
# Copies Python files to /opt/theframe and restarts the service.
# Run from the sidecar directory: sudo bash update.sh

set -e

INSTALL_DIR="/opt/theframe"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: Please run with sudo: sudo bash update.sh"
  exit 1
fi

echo "==> Copying files to $INSTALL_DIR..."
cp "$SCRIPT_DIR"/*.py "$INSTALL_DIR/"

echo "==> Restarting service..."
systemctl restart theframe

echo "==> Done! Logs:"
journalctl -u theframe -n 20 --no-pager
