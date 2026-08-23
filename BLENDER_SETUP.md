# Blender and environment setup for CampusGrid Agents on Ubuntu

This guide outlines the system requirements, installation instructions, and verification checks required to set up the CampusGrid worker runtime environment on Ubuntu lab machines.

---

## 1. System Requirements

- **Operating System**: Ubuntu 20.04 LTS or newer
- **Java Platform**: Java SE 17 (OpenJDK 17)
- **Render Engine**: Blender 4.0 LTS or newer (configured for headless execution)

---

## 2. Environment Installation

### Step A: Update Package Registry
Ensure all host system packages and snap channels are updated:
```bash
sudo apt update && sudo apt upgrade -y
```

### Step B: Install OpenJDK 17
Install the Java Development Kit (JDK 17) on the Ubuntu node:
```bash
sudo apt install -y openjdk-17-jdk
```

Verify the Java compiler and virtual machine:
```bash
java -version
javac -version
```
*Expected Java output:*
```
openjdk version "17.0.x" ...
OpenJDK Runtime Environment ...
OpenJDK 64-Bit Server VM ...
```

### Step C: Install Blender 4.x
To ensure the correct `4.x` version of Blender is installed, Snap packages with classic confinement must be used:
```bash
sudo snap install blender --classic
```

Verify that the `blender` command is globally linked and can run in headless mode:
```bash
blender --version
```
*Expected Blender output:*
```
Blender 4.x.y
    build date: ...
    build platform: Linux
    ...
```

---

## 3. Subprocess Headless Rendering Verification

Before launching the Agent daemon, run a manual headless render verification check on the Ubuntu node.

### Step A: Generate a default test scene
Run a short python expression in Blender to save a standard default scene containing a camera, light source, and cube:
```bash
blender -b --python-expr "import bpy; bpy.ops.wm.save_as_mainfile(filepath='test.blend')"
```
*Expected Log output:*
```
Blender 4.x.y (hash ...)
Info: Saved as "test.blend"
Blender quit
```

### Step B: Verify rendering a single frame
Render frame `1` of the generated scene into a temporary folder:
```bash
mkdir -p /tmp/render_out
blender -b test.blend -o /tmp/render_out/ -f 1
```
*Expected Log output:*
```
Read blend: "test.blend"
Rendering frame 1
Saved: '/tmp/render_out/0001.png'
Time: 00:00.xx
Blender quit
```

### Step C: Verify rendering a frame range
Render frames `1` to `3` as an animation sequence:
```bash
blender -b test.blend -o /tmp/render_out/ -s 1 -e 3 -a
```
*Expected Log output:*
```
Read blend: "test.blend"
Rendering animation (frames 1..3)
Rendering frame 1
Saved: '/tmp/render_out/0001.png'
Rendering frame 2
Saved: '/tmp/render_out/0002.png'
Rendering frame 3
Saved: '/tmp/render_out/0003.png'
Blender quit
```

Clean up verification artifacts:
```bash
rm -rf test.blend /tmp/render_out
```

---

## 4. Automatic Provisioning via deploy.sh

The `deploy.sh` script is configured to perform these verification checks automatically when deploying to remote lab machines.

To run the provisioning and setup flow via `deploy.sh`:
```bash
chmod +x deploy.sh
./deploy.sh
```
*Expected deployment output:*
```
[DEPLOY] Ubuntu OS detected.
[DEPLOY] Java 17 detected.
[BLENDER] Checking...
[BLENDER] Installed
[BLENDER] Version: 4.x.y
[BLENDER] Ready
...
[DEPLOY] Deployment complete.
```
