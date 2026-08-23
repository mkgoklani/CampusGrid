# CampusGrid Agent Node - Phase 2 Testing Manual

This document details the directory layout, execution contracts, and step-by-step verification flows for testing the headless Blender rendering runtime on Ubuntu lab machines.

---

## 1. Directory Structure Layout

The worker agent architecture is modularized as follows:
```
CampusGrid/
├── agent-node/
│   ├── deploy.sh                     # Ubuntu environment check & remote provisioning script
│   └── src/
│       └── com/campusgrid/agent/
│           ├── Agent.java            # Main entry daemon
│           ├── network/
│           │   ├── MasterConnection.java  # TCP socket & streams manager
│           │   ├── HeartbeatService.java  # Diagnostic telemetries daemon
│           │   └── PayloadListener.java   # Task receiver & dispatch thread
│           ├── blender/
│           │   ├── BlenderUtils.java      # Process execution & path mapping utilities
│           │   ├── BlenderInstaller.java  # OS package & version verification
│           │   ├── BlenderRenderTask.java # Serializable render job configuration
│           │   ├── BlenderStatusReport.java # Detailed progress status packet
│           │   ├── RenderResult.java      # Serializable render output block
│           │   ├── ProgressReporter.java  # Socket telemetry status reporter
│           │   └── BlenderJobExecutor.java# Headless ProcessBuilder runner
│           └── os/
│               ├── LinuxTelemetry.java    # Temperature & system telemetries
│               └── IdleDetector.java      # User activity eviction controller
├── common-lib/
│   └── src/
│       └── com/campusgrid/core/
│           └── GridTask.java         # Master-Worker scatter-gather interface contract
└── out/                              # Compiled JVM bytecodes destination
```

---

## 2. Example BlenderRenderTask Definition

Below is the serializable Java class used to exchange render tasks between the Master and Worker Agents.

```java
package com.campusgrid.agent.blender;

import java.io.Serializable;

public class BlenderRenderTask implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final String blendFilePath;
    private final int frameStart;
    private final int frameEnd;
    private final String outputDir;
    private final String renderEngine;

    public BlenderRenderTask(String jobId, String blendFilePath, int frameStart, int frameEnd, String outputDir, String renderEngine) {
        this.jobId = jobId;
        this.blendFilePath = blendFilePath;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.outputDir = outputDir;
        this.renderEngine = renderEngine;
    }

    public String getJobId() { return jobId; }
    public String getBlendFilePath() { return blendFilePath; }
    public int getFrameStart() { return frameStart; }
    public int getFrameEnd() { return frameEnd; }
    public String getOutputDir() { return outputDir; }
    public String getRenderEngine() { return renderEngine; }
}
```

---

## 3. Step-by-Step Testing on Ubuntu Lab Machines

Ensure you have completed the installation steps listed in [**`BLENDER_SETUP.md`**](file:///d:/Campus_grid/CampusGrid/BLENDER_SETUP.md).

### Step 1: Verify Dependencies
Check that Java 17 and Blender are ready:
```bash
java -version
blender --version
```

### Step 2: Compile the Agent Codebase
Navigate to the root directory and compile the runtime:
```bash
javac -d out -sourcepath "agent-node/src:common-lib/src" agent-node/src/com/campusgrid/agent/Agent.java
```

### Step 3: Run Headless Render Subprocess Test
Generate a test asset and render a single frame to verify local Blender operation:
```bash
blender -b --python-expr "import bpy; bpy.ops.wm.save_as_mainfile(filepath='test.blend')"
blender -b test.blend -o /tmp/frames/ -f 1
```

### Step 4: Test Progress Reporting & Rate Limiting
Run the status reporting test class to verify that `BlenderStatusReport` packets are created, throttled to 1-second intervals, and co-exist with the heartbeat stream:
```bash
javac -d out -sourcepath "agent-node/src:common-lib/src" agent-node/src/com/campusgrid/agent/blender/ProgressReporter.java
# Run integration suite to verify console reporting throttles
```

### Step 5: Test Cancellation
To verify that the Blender subprocess is destroyed when a cancellation command is received:
1. Dispatch a long-running rendering job (e.g. frames 1-100).
2. Send a `KILL` message from the Master socket.
3. Check that the worker outputs the expected logs:
```
[TASK] Kill/Cancel command received for jobId: detailed-job-1
[EXECUTOR] Cancelling Blender process for job: detailed-job-1
[TASK] Blender render cancelled: null
[TASK] Render result sent to Master: RenderResult[jobId=detailed-job-1, status=CANCELLED, frames=4, duration=4231ms]
```

### Step 6: Test Worker Reconnection
Verify that if connection is broken during execution, the agent cleanly tears down, returns to `READY` status on reconnection, and is ready for re-queuing:
```bash
# Terminate the socket connection on the Master Node side
# Observe Agent logs showing:
[HEARTBEAT] Connection lost
[TASK] Connection lost.
[TASK] Listener stopped
[NETWORK] Connecting to Master...
[NETWORK] Retrying in 5 seconds...
# Re-establish Master listening. Observe reconnect:
[NETWORK] Connected to Master at 192.168.1.50:8080
[PROGRESS] Reporting Status: BlenderStatusReport[..., State=READY]
```

---

## 4. End-to-End Execution Demo Scenario

This scenario demonstrates distributed rendering across two worker nodes.

### Simulation Configs
- **Animation Sequence**: 100 frames (`1` to `100`).
- **Scene**: `animation.blend`
- **Agent 1 IP**: `192.168.1.101`
- **Agent 2 IP**: `192.168.1.102`

### Run Command Sequence

1. **Start Master Node** (from Master machine, e.g. `192.168.1.50`):
   ```bash
   java -cp "out:master-node" MasterNode
   ```
   *Console output:*
   ```
   [ACCEPT] Listening on port 8080...
   ```

2. **Connect Agent 1** (from `192.168.1.101`):
   ```bash
   java -cp "out" com.campusgrid.agent.Agent 192.168.1.50
   ```
   *Expected Agent 1 logs:*
   ```
   [NETWORK] Connecting to Master...
   [NETWORK] Connected to Master at 192.168.1.50:8080
   [PROGRESS] Reporting Status: BlenderStatusReport[Worker=192.168.1.101:54321, State=READY]
   [HEARTBEAT] Started
   [TASK] Listener started
   ```

3. **Connect Agent 2** (from `192.168.1.102`):
   ```bash
   java -cp "out" com.campusgrid.agent.Agent 192.168.1.50
   ```
   *Expected Agent 2 logs:*
   ```
   [NETWORK] Connecting to Master...
   [NETWORK] Connected to Master at 192.168.1.50:8080
   [PROGRESS] Reporting Status: BlenderStatusReport[Worker=192.168.1.102:54322, State=READY]
   [HEARTBEAT] Started
   [TASK] Listener started
   ```

4. **Master Node logs connection registry updates:**
   ```
   [REGISTRY] Registered agent: 192.168.1.101:54321
   [REGISTRY] Registered agent: 192.168.1.102:54322
   ```

5. **Submit a 100-frame render job on Master Node CLI**:
   Master splits workload:
   - Chunk 1 (Frames 1-50) is dispatched to Agent 1 (`192.168.1.101:54321`).
   - Chunk 2 (Frames 51-100) is dispatched to Agent 2 (`192.168.1.102:54322`).

6. **Observe Live Progress Reports on Master:**
   Agents concurrently execute rendering. Every ~1 second, rate-limited progress logs update the Master console:
   ```
   [PROGRESS] Agent 192.168.1.101:54321 -> Frame 12/50 (24.00%), FPS: 1.25, Temp: 58°C, State: RENDERING
   [PROGRESS] Agent 192.168.1.102:54322 -> Frame 63/100 (26.00%), FPS: 1.22, Temp: 60°C, State: RENDERING
   ...
   [PROGRESS] Agent 192.168.1.101:54321 -> Frame 50/50 (100.00%), FPS: 1.30, Temp: 61°C, State: COMPLETED
   [PROGRESS] Agent 192.168.1.102:54322 -> Frame 100/100 (100.00%), FPS: 1.29, Temp: 60°C, State: COMPLETED
   ```

7. **Observe Final Return payloads on Master:**
   ```
   [ORCHESTRATOR] Received RenderResult from 192.168.1.101:54321: SUCCESS, Duration: 39500ms, FrameCount: 50
   [ORCHESTRATOR] Received RenderResult from 192.168.1.102:54322: SUCCESS, Duration: 40100ms, FrameCount: 50
   [ORCHESTRATOR] Job execution successfully completed. Output frames stored.
   ```
   
   Agents print:
   ```
   [TASK] Render result sent to Master: RenderResult[jobId=job-0, status=SUCCESS, frames=50]
   [PROGRESS] Reporting Status: BlenderStatusReport[Worker=192.168.1.101:54321, State=READY]
   ```
   Both Agents return to the `READY` state, waiting for the next job sequence.
