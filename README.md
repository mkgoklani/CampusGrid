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
