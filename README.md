# CampusGrid
A Zero-Cost Distributed Computing Prototype built on Core Java Sockets.
<p align="center">
  <img src="assets/CampusGridMain.png" alt="CampusGrid Logo" width="200">
</p>

<h1 align="center">CampusGrid</h1>
Status: Phase 1 Core Infrastructure Under Active Stress-Testing & Deployment.

## Overview
CampusGrid is a localized distributed compute network designed to scavenge idle CPU cycles from institutional lab networks without disrupting active users. By linking existing lab computers into a single virtual supercomputer, the system allows for parallel processing of heavy research tasks utilizing zero external internet bandwidth.

## System Architecture
The system follows a strict Star Topology (Master-Worker model) engineered for high-throughput localized data clustering.

```text
[ FACULTY / ADMIN ]
       | (Secure CLI Login)
       v
+=================================================+
|              MASTER NODE (Your Laptop)          |
|                                                 |
|  1. [Auth Manager] --> [CLI Telemetry Dashboard]|
|             |                                   |
|  2. [Task Slicer] ---> [Pending Chunk Queue]    |
|             |                                   |
|  3. [Node Registry] <-> [Thread Pool Manager]   |
|   (Tracks Active IPs)   (Assigns Chunks to IPs) |
|             |                                   |
|  4. [Result Aggregator] <-----+                 |
+===============================|=================+
                                |
                                | (TCP Port 8080 / Object Streams)
                                v
+=================================================+
|                      THE NETWORK                |
+=================================================+
      |                 |                 |
      v                 v                 v
+===========+     +===========+     +===========+
|  AGENT 1  |     |  AGENT 2  |     |  AGENT N  |
|(Ubuntu PC)|     |(Ubuntu PC)|     |(Ubuntu PC)|
|           |     |           |     |           |
| [Socket]  |     | [Socket]  |     | [Socket]  |
| [Executor]|     | [Executor]|     | [Executor]|
| [OS Tele] |     | [OS Tele] |     | [OS Tele] |
| [Eviction]|     | [Eviction]|     | [Eviction]|
+===========+     +===========+     +===========+

@The 4 Architectural Pillars:
->The Control Plane (Master Node): Utilizes an ExecutorService Dispatcher to maintain dedicated threads for every connected Agent. Workloads are sliced and placed into a thread-safe ConcurrentLinkedQueue. Access is secured via java.security.MessageDigest.  
->The Data Plane: Runs entirely on raw TCP (java.net.Socket). Data is serialized into binary (java.io.ObjectOutputStream) and pushed across the wire for maximum throughput.  
->The Execution Plane (Agent Node): A lightweight .jar daemon running on Ubuntu machines that executes mathematical logic via a while(true) loop.
->The Contract: A shared GridTask.java Interface guarantees the Master knows how to split data and the Agent knows how to process it without tight coupling.

@Core Features:
->Automated Workload Distribution (Scatter-Gather): Slices heavy research data into equal chunks and distributes them across active nodes for parallel processing.
->Zero-Disruption Smart Eviction: A background Runnable polls the Ubuntu xprintidle command. If a student moves the mouse (idle time drops below 2 seconds), the node instantly triggers future.cancel(), vacates the PC, and sends an EVICTED packet to the Master.
->Auto-Recovery & Fault Tolerance: If a node is accidentally turned off or forcefully evicted, the Master Node catches the unfinished data chunk and re-inserts it into the ConcurrentLinkedQueue to be reassigned to another active Agent.
->Live Hardware Telemetry: A bridge utilizing ProcessBuilder runs the Ubuntu sensors command every 5 seconds to scrape CPU thermals, ensuring physical hardware safety.
->Global Kill Switch: An instant "Abort" command broadcasts a Poison Pill byte-string (e.g., <ABORT_ALL>) across all active sockets to instantly halt computation and clear memory in milliseconds.

@Tech Stack & Primitives:
Language: Core Java (JDK 17+)
Concurrency: java.util.concurrent (ExecutorService, ConcurrentLinkedQueue)
Networking: Raw TCP / IP Sockets (java.net)
Serialization: ObjectOutputStream / ObjectInputStream
OS Interfacing: ProcessBuilder (Linux Bash commands integration)

@Benchmark & Dashboard:
The system features a live CLI Control Dashboard running continuously on the main Java thread utilizing ANSI escape codes to render active grid maps, real-time node temperatures, and task progress bars[cite: 5]. A built-in stress test allows for physical demonstration of high-speed data processing capabilities for institutional accreditation visits
