#!/bin/bash

# CampusGrid Network Connect Helper

echo "================================================="
echo "   CampusGrid Master Node - Network Connector"
echo "================================================="
echo ""

# Detect Local IP
if [[ "$OSTYPE" == "darwin"* ]]; then
    LOCAL_IP=$(ipconfig getifaddr en0)
    if [ -z "$LOCAL_IP" ]; then
        LOCAL_IP=$(ipconfig getifaddr en1)
    fi
else
    LOCAL_IP=$(hostname -I | awk '{print $1}')
fi

if [ -z "$LOCAL_IP" ]; then
    LOCAL_IP="127.0.0.1"
fi

echo ">> MODE 1: SAME WIFI / LOCAL LAN (Hackathon Mode)"
echo "If your teammates are connected to the SAME WiFi network,"
echo "they can connect their agent nodes using this command:"
echo ""
echo "    java -jar agent.jar $LOCAL_IP"
echo ""
echo "And they can access the dashboard at:"
echo "    http://$LOCAL_IP:8080"
echo ""
echo "-------------------------------------------------"

echo ">> MODE 2: OVER THE INTERNET (Remote Teammates)"
echo "If your teammates are at home or on a different network,"
echo "we can create a temporary public internet tunnel."
read -p "Do you want to start an internet tunnel now? (y/n): " start_tunnel

if [[ "$start_tunnel" == "y" || "$start_tunnel" == "Y" ]]; then
    echo ""
    echo "Starting secure SSH tunnel via pinggy.io (No installation required)..."
    echo "Please wait a few seconds for the URLs to generate..."
    echo ""
    echo "Press Ctrl+C to stop the tunnel when you are done."
    echo ""
    echo "IMPORTANT: In the output below, look for the TCP port and HTTP URL."
    echo "Agent Command: java -jar agent.jar tcp.a.pinggy.io <TCP_PORT>"
    echo "-------------------------------------------------"
    
    # We expose ONLY the TCP Socket (9000) for agent connections.
    # The dashboard is deliberately NOT exposed to the internet for security.
    ssh -p 443 -R0:localhost:9000 a.pinggy.io
else
    echo "Tunnel cancelled. Use Mode 1 for local connections."
fi
