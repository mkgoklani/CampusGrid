#!/bin/bash
# ===================================================
#   CampusGrid - Worker Agent Launcher (Linux/macOS)
# ===================================================

MASTER_IP="$1"

if [ -z "$MASTER_IP" ]; then
    read -rp "Enter the Master Node IP address (e.g. 192.168.1.15): " MASTER_IP
fi

if [ -z "$MASTER_IP" ]; then
    echo "[ERROR] No IP address provided. Exiting."
    exit 1
fi

echo "[AGENT] Connecting to Master Node at ${MASTER_IP}:8080 ..."
echo "[AGENT] Press Ctrl+C at any time to stop this worker."

if [ ! -f "Agent.jar" ]; then
    echo "[ERROR] Agent.jar not found in $(pwd)!"
    exit 1
fi

java -jar Agent.jar "$MASTER_IP"
