# CampusGrid — Multi-Computer LAN / Wi-Fi Setup Guide

This guide explains how to run **CampusGrid** across multiple computers connected to the same Wi-Fi network or Local Area Network (LAN).

---

## Architecture Overview

```
 ┌────────────────────────────────────────────────────────┐
 │                    Same Wi-Fi / LAN                    │
 └───────────────────────────┬────────────────────────────┘
                             │
            ┌────────────────┴────────────────┐
            │                                 │
   [ MASTER PC ]                        [ AGENT PC(s) ]
   IP: e.g. 192.168.1.15                IP: 192.168.1.20, etc.
   ├─ Runs: Master.jar                  ├─ Runs: Agent.jar 192.168.1.15
   ├─ TCP Port 8080 (Agents)            ├─ Has: Java 17+
   ├─ HTTP Port 8081 (Dashboard)        └─ Has: Blender (for 3D rendering)
   └─ WS Port 8082 (Telemetry)
```

---

## Step 1: Build the Executable JARs (on Master PC)

Run the build script to create standalone `Agent.jar` and `Master.jar`:

- **Windows**: Double-click `build.bat` or run:
  ```powershell
  .\build.bat
  ```
- **PowerShell**:
  ```powershell
  .\build.ps1
  ```

This generates:
- **`Agent.jar`** (~4.3 MB fat JAR, includes hardware telemetry & networking)
- **`Master.jar`** (~140 KB, includes coordinator & embedded web dashboard)

---

## Step 2: Find the Master PC's IP Address

On the **Master PC**, open PowerShell / Command Prompt and run:

```powershell
ipconfig
```

Look for **IPv4 Address** under your active Wi-Fi or Ethernet adapter (e.g. `192.168.1.15` or `10.0.0.5`).

> **Tip:** You can also just run `start_master.bat` on the Master PC, which will auto-detect and print your local IP!

---

## Step 3: Allow Ports in Windows Firewall (on Master PC)

By default, Windows Firewall may block incoming connections from other computers on your Wi-Fi.

Run this command once in **PowerShell as Administrator** on the Master PC to allow CampusGrid ports:

```powershell
New-NetFirewallRule -DisplayName "CampusGrid Master" -Direction Inbound -LocalPort 8080,8081,8082 -Protocol TCP -Action Allow
```

*(Alternatively, when Windows Firewall pops up a dialog asking to allow Java, make sure to check **Private networks** and click **Allow access**).*

---

## Step 4: Start the Master Node

On the **Master PC**:

- Double-click **`start_master.bat`** (or run `java -jar Master.jar`).
- Open your browser to:
  - Local: `http://localhost:8081`
  - From any device on LAN: `http://<MASTER_IP>:8081`

---

## Step 5: Run the Agent on Other Computer(s)

1. **Copy Files to Agent PC**:
   - Copy **`Agent.jar`** and **`start_agent.bat`** (or `start_agent.sh` if Linux/Mac) to the other computer via USB drive, local network share, or download.

2. **Requirements on Agent PC**:
   - **Java 17 or newer** installed (`java -version`).
   - **Blender**: If already installed, CampusGrid auto-detects standard paths like `C:\Program Files\Blender Foundation\Blender 5.1`. If not installed, you can simply click **Install Blender** in the dashboard; the agent will download Blender 5.1 and extract it directly onto the C: drive (`C:\Program Files\Blender Foundation\Blender 5.1` or `C:\Blender\Blender 5.1`) in just a few minutes.

3. **Start the Agent**:
   - **Windows**: Double-click `start_agent.bat` and enter your Master PC's IP address (e.g. `192.168.1.15`), or run:
     ```cmd
     java -jar Agent.jar 192.168.1.15
     ```
   - **Linux / macOS**:
     ```bash
     chmod +x start_agent.sh
     ./start_agent.sh 192.168.1.15
     ```

4. **Verify Connection**:
   - Watch the Agent terminal:
     ```
     [NETWORK] Connecting to Master...
     [NETWORK] Successfully connected to Master at 192.168.1.15:8080
     [HEARTBEAT] Started.
     ```
   - Refresh or view the Dashboard at `http://localhost:8081` on your Master PC.
   - You will see the new computer appear live in the **Worker Nodes Telemetry** table with its CPU, RAM, temperature, and Blender version!

---

## Step 6: Submitting Distributed Workloads

1. Go to `http://localhost:8081` (or `http://<MASTER_IP>:8081` from any laptop/tablet/phone on the network).
2. Click **+ New Workload**.
3. Select your `.blend` file, choose resolution, render engine (Cycles / EEVEE / Workbench), and frame count.
4. Click **🚀 Launch Grid Job**.
5. The Master will automatically slice the animation across all connected worker computers on your network.
6. When complete, click **▶ Preview Video** to play the stitched `preview.mp4` or download the rendered frames!

---

## Troubleshooting

### 1. Agent says `Connection refused` or `Connection timed out`
- Check that Master is running.
- Ensure both computers are on the **exact same Wi-Fi network**.
- Check that your Wi-Fi does not have "AP Isolation" or "Guest Mode" enabled (which prevents devices from talking to each other).
- Ensure Windows Firewall on the Master PC is allowing port 8080 (see Step 3).
- Try pinging the Master from the Agent PC: `ping <MASTER_IP>`.

### 2. Agent connects but says `Blender: Unknown`
- Ensure Blender is installed on the Agent PC and added to PATH, or installed in standard locations (`C:\Program Files\Blender Foundation\Blender 4.x\blender.exe` or `/usr/bin/blender`).
- Or use the **Install Blender** button in the dashboard to trigger automatic download.

### 3. Running multiple agents on the same PC (Testing)
You can run as many agents as you want on the same computer or multiple computers:
```cmd
java -jar Agent.jar 127.0.0.1
```
Each instance registers as an independent compute node.
