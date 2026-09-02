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

# Step 0: Detect Operating System
detect_os() {
    OS="$(uname -s)"
    case "$OS" in
        Linux*)
            OS_TYPE="Linux"
            log_deploy "Detected Linux operating system."
            ;;
        Darwin*)
            OS_TYPE="macOS"
            log_deploy "Detected macOS operating system."
            ;;
        CYGWIN*|MINGW*|MSYS*)
            OS_TYPE="Windows"
            log_deploy "Detected Windows environment."
            ;;
        *)
            OS_TYPE="Unknown"
            log_deploy "Detected system: $OS"
            ;;
    esac
}

# Step 1: Check Java Installation (Verify Java 11+)
check_java() {
    if ! command -v java >/dev/null 2>&1; then
        log_deploy "Java not found. Please install Java (JRE/JDK 17+)."
        exit 1
    fi
    
    local java_ver
    java_ver=$(java -version 2>&1 | head -n 1)
    log_deploy "Java runtime detected: $java_ver"
}

# Step 1.5: Check and Manage Blender Installation across OSes
manage_blender() {
    echo "[BLENDER] Checking system availability..."
    
    if command -v blender >/dev/null 2>&1; then
        local blender_ver
        blender_ver=$(blender --version 2>&1 | head -n 1 | sed -E 's/^Blender[[:space:]]+([^[:space:]]+).*/\1/')
        echo "[BLENDER] Installed (Version: $blender_ver)"
        echo "[BLENDER] Ready"
    elif [ -f "/Applications/Blender.app/Contents/MacOS/Blender" ]; then
        echo "[BLENDER] Installed (/Applications/Blender.app)"
        echo "[BLENDER] Ready"
    else
        log_deploy "Blender not found on system PATH. Attempting automated OS package installation..."
        
        if [ "$OS_TYPE" = "macOS" ]; then
            if command -v brew >/dev/null 2>&1; then
                brew install --cask blender || true
            fi
        elif [ "$OS_TYPE" = "Linux" ]; then
            if command -v snap >/dev/null 2>&1; then
                sudo snap install blender --classic || true
            elif command -v apt-get >/dev/null 2>&1; then
                sudo apt-get update && sudo apt-get install -y blender || true
            elif command -v dnf >/dev/null 2>&1; then
                sudo dnf install -y blender || true
            elif command -v pacman >/dev/null 2>&1; then
                sudo pacman -S --noconfirm blender || true
            fi
        fi
        
        if command -v blender >/dev/null 2>&1 || [ -f "/Applications/Blender.app/Contents/MacOS/Blender" ]; then
            echo "[BLENDER] Installed and verified successfully."
        else
            echo "[BLENDER] Note: Native Blender binary not in PATH. Agent will use built-in automated installer or software fallback."
        fi
    fi
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
    
    # Launch in background with auto-discovery fallback, redirecting output streams to log file
    nohup java -jar "$JAR_NAME" "${MASTER_IP:-auto}" > "$LOG_FILE" 2>&1 &
    
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
detect_ubuntu
check_java
manage_blender
setup_directory
download_agent
stop_existing_agent
launch_agent

log_deploy "Deployment complete."
