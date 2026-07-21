#!/bin/bash
set -euo pipefail
# ==============================================================================
# Configuration Variables
# ==============================================================================
# The IP address of the Master Node.
MASTER_IP="192.168.1.50"

# The descriptive name of the agent.
AGENT_NAME="CampusGridAgent"

# Target directory where the agent will be installed and run.
INSTALL_DIR="$HOME/campusgrid"

# Name of the executable Agent JAR.
JAR_NAME="Agent.jar"

# The file where stdout and stderr of the agent will be written.
LOG_FILE="agent.log"

# Placeholder download URL for the Agent.jar executable.
# Modify this URL to point to your actual storage server or download server.
DOWNLOAD_URL="http://example.com/downloads/Agent.jar"

# ==============================================================================
# Helper Functions
# ==============================================================================

# Function to print structured deployment logs
log_deploy() {
    echo "[DEPLOY] $1"
}

# Step 1: Check Java Installation
check_java() {
    # Check if java binary is present in the system path
    if ! command -v java >/dev/null 2>&1; then
        log_deploy "Java not found."
        exit 1
    fi
    log_deploy "Java detected."
}

# Step 2: Create Installation Directory
setup_directory() {
    # Create target directory recursively if it doesn't exist
    if [ ! -d "$INSTALL_DIR" ]; then
        mkdir -p "$INSTALL_DIR"
    fi
}

# Step 3: Download Agent JAR
download_agent() {
    local target_path="$INSTALL_DIR/$JAR_NAME"
    
    # Support both wget and curl, prioritizing wget
    if command -v wget >/dev/null 2>&1; then
        wget -q -O "$target_path" "$DOWNLOAD_URL"
    elif command -v curl >/dev/null 2>&1; then
        curl -s -o "$target_path" "$DOWNLOAD_URL"
    else
        log_deploy "No download tool available."
        exit 1
    fi
    
    # Check if download succeeded and file is non-empty
    if [ ! -s "$target_path" ]; then
        log_deploy "Download failed: File is missing or empty."
        exit 1
    fi
    log_deploy "Download successful."
}

# Step 4: Stop Existing Agent Process
stop_existing_agent() {
    # Check and terminate any running Java processes running our JAR name
    # Uses pattern matching to restrict termination only to the agent JAR process.
    if pgrep -f "java.*$JAR_NAME" >/dev/null 2>&1; then
        pkill -f "java.*$JAR_NAME"
        # Allow process to terminate and release socket resources
        sleep 2
    fi
    log_deploy "Existing Agent stopped."
}

# Step 5: Launch the Agent in Detached Mode
launch_agent() {
    cd "$INSTALL_DIR" || exit 1
    
    # Launch in background, redirecting output streams to log file
    nohup java -jar "$JAR_NAME" "$MASTER_IP" > "$LOG_FILE" 2>&1 &
    
    # Verify execution by checking if the process started
    if pgrep -f "java.*$JAR_NAME" >/dev/null 2>&1; then
        log_deploy "Agent started."
    else
        log_deploy "Failed to start Agent."
        exit 1
    fi
}

# ==============================================================================
# Execution Flow
# ==============================================================================
check_java
setup_directory
download_agent
stop_existing_agent
launch_agent

log_deploy "Deployment complete."
