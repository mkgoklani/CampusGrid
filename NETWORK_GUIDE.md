# CampusGrid Network Connection Guide

This guide explains how to connect your Agent Nodes to the Master Node when you are working on the same WiFi or across the internet.

## Using the `campusgrid_network.sh` Script

We've provided a simple, interactive terminal script to handle all the networking for you. This script is separate from the Dashboard and only needs to be run when you are setting up the network.

**To run the script:**
```bash
./campusgrid_network.sh
```

### Scenario 1: Same WiFi (Hackathon Mode)
When you run the script, it will automatically detect your machine's local IP address (e.g., `192.168.1.15`). 

If all teammates are on the same WiFi network, they can simply point their agent nodes to that exact IP:
```bash
java -jar agent.jar 192.168.1.15
```

### Scenario 2: Over the Internet (Remote Teammates)
If your teammates are at home (different WiFi networks), they cannot connect to `192.168.1.15`. 

When you run `./campusgrid_network.sh`, it will ask you if you want to start an **Internet Tunnel**. 
- Type `y` and press Enter.
- The script uses your computer's built-in SSH to securely connect to `pinggy.io`, a free tunneling service. 
- **No accounts or installations are required.**

The terminal will generate a screen containing public URLs. You need to give your teammates two things:
1. **The TCP Port Connection (for the Agent):** Look for `tcp.a.pinggy.io:<PORT>` and tell your teammates to run:
   ```bash
   java -jar agent.jar tcp.a.pinggy.io <PORT>
   ```
2. **The Dashboard URL:** Look for the HTTP address (e.g., `https://random-word.a.pinggy.link`). Anyone can open this URL in their browser to see the Master Node dashboard and upload render jobs!

*(Note: The free pinggy.io tunnel expires after 60 minutes. If you need it longer, you simply restart the script and give your teammates the new port/URL.)*

---

## Alternative: Tailscale (Virtual LAN)
If your team is working remotely for an extended period (weeks/months) and you don't want to deal with 60-minute expiring URLs, we highly recommend **Tailscale**.

1. Everyone on the team installs [Tailscale](https://tailscale.com/) and logs in.
2. Tailscale assigns everyone a permanent `100.x.x.x` IP address.
3. Your computer acts like it's on a giant virtual WiFi network. 
4. Your teammates can simply run: `java -jar agent.jar <YOUR_TAILSCALE_IP>`
