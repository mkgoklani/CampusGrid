# CampusGrid - Project Changelog & Contributions

This document details the features, components, and architectural improvements implemented across the **CampusGrid** repository, highlighting individual and team contributions.

---

## 🛠️ 1. Master Node Orchestrator & Control Plane
*Primary Contributor: Mohit*

* **Phase 1: Socket Foundation**: Implemented the primary TCP network server listening on port `8080` to manage incoming worker connections.
* **Phase 2: Multi-Threaded Task Dispatcher**: Replaced single-threaded blocking connections with a thread pool (`ExecutorService`), allowing parallel task routing to multiple concurrent agents.
* **Phase 3: Connection Registry & Fail-Fast Re-Queueing**:
  * Built a registry tracking live agents.
  * Added error-resilience: if an agent disconnects mid-computation, its socket is severed, and its current task slice is automatically pushed back to the head of the task queue for another worker to process.
* **Phase 4: Unique ID Mapping**: Updated the registry to map agent sockets using `IP:Port` keys, allowing multiple agents to run concurrently from the same host machine (localhost loopback) without key collisions.
* **Interactive CLI**: Added a daemon thread console dashboard supporting `START` (workload selector), `STATUS` (active telemetry overview), and `ABORT` (emergency connection kill switch) console commands.

---

## 🤖 2. Agent Node Client & Runtime
*Primary Contributors: Nilesh & Piyush*

* **Agent Entry Point**: Created the `Agent` daemon process that takes the Master Node IP address as a command line argument, establishes a TCP socket handshake, and starts services.
* **Heartbeat & Telemetry Daemon**: Implemented a recurring background service that polls the host system's hardware state and sends periodic `HEARTBEAT | TEMP: XX°C` telemetry packets to the master to update the scheduler.
* **Task Listener & Executor**:
  * Implemented `PayloadListener` to run on a dedicated thread, reading incoming serialized task objects.
  * Performs dynamically loaded computations by invoking the task's `.execute()` method, and returns the serialized result block over the socket back to the master.

---

## 📦 3. Shared Library (common-lib)
*Collaborative Work*

* **Distributed Task Contract**: Defined the `GridTask<T>` interface in **[GridTask.java](file:///Users/mohitkumar/CampusGrid/common-lib/src/com/campusgrid/core/GridTask.java)** to unify execution models:
  * `List<GridTask<T>> split(int n)`: Deconstructs a massive computational load into `n` smaller parallel strips.
  * `T execute()`: Computes the math on the worker agent.
  * `T merge(List<T> results)`: Re-assembles out-of-order sub-results back into a single output at the Master.
* **Mandelbrot Calculation**: Added **[MandelbrotTask.java](file:///Users/mohitkumar/CampusGrid/common-lib/src/com/campusgrid/core/MandelbrotTask.java)** to handle row-slice mathematical iterations.
* **Integrity Check Suite**: Created verification audits (**`SerializationAudit`**, **`MathAudit`**, and **`SecurityAudit`**) to run verification checks on serialization contracts and cryptographic gateway integrity.

---

## ⚡ 4. High-Performance Benchmarks & Stream Optimization
*Primary Contributor: Mohit (Current Integration Phase)*

* **Object Stream Optimization & Deadlock Fixes**:
  * Fixed socket deadlocks by forcing the Master to initialize and flush the `ObjectOutputStream` *before* constructing the `ObjectInputStream`.
  * Replaced unstable text writer/readers with a synchronized binary `sendObject(Object obj)` method in **[MasterConnection.java](file:///Users/mohitkumar/CampusGrid/agent-node/src/com/campusgrid/agent/network/MasterConnection.java)** to prevent packet collision and serialization stream corruption.
* **Heavy 8K Mandelbrot Workload**:
  * Configured Mandelbrot task dimensions to **8K ($7680 \times 4320$)** at **1,500 max iterations** to weigh down execution.
  * Added a downsampled character binning algorithm inside the console ASCII renderer to project a beautiful $80 \times 40$ visual preview of the calculated fractal.
* **Massive Matrix Multiplication Workload**:
  * Created **[MatrixMultiplicationTask.java](file:///Users/mohitkumar/CampusGrid/common-lib/src/com/campusgrid/core/MatrixMultiplicationTask.java)** to execute a $2,000 \times 2,000$ double matrix multiplication.
  * *Bandwidth Optimization*: Generated matrices A and B deterministically using algebraic indices inside the worker thread. This reduced the network load from 128 MB down to a 32 MB row-subset result payload, preventing network buffer overflows.
* **Multi-Platform Thermal Telemetry**:
  * Rewrote **[LinuxTelemetry.java](file:///Users/mohitkumar/CampusGrid/agent-node/src/com/campusgrid/agent/os/LinuxTelemetry.java)**.
  * On Linux/Ubuntu, it directly reads kernel `sysfs` files (`/sys/class/thermal/thermal_zone*/temp`) without external package dependencies.
  * On macOS M1, it simulates dynamic thermal activity (idle ~39°C, rising to ~58°C during calculation) to enable realistic load balancer testing.
