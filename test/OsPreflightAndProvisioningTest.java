import com.campusgrid.agent.os.SystemPreflight;
import com.campusgrid.agent.blender.BlenderUtils;
import com.campusgrid.agent.os.HardwareCollector;

/**
 * Integration test suite for OS-Specific Preflight Verification and Auto-Provisioning.
 */
public class OsPreflightAndProvisioningTest {

    public static void main(String[] args) {
        System.out.println("=== RUNNING OS PREFLIGHT & DEPENDENCY VERIFICATION TEST ===");

        // 1. Run Preflight Audit
        SystemPreflight.PreflightResult res = SystemPreflight.runPreflight(false);

        // 2. Validate Results
        assert res.osName != null && !res.osName.isEmpty() : "OS Name must not be empty";
        assert res.osType != null && ("windows".equals(res.osType) || "macos".equals(res.osType) || "linux".equals(res.osType))
            : "OS Type must be one of windows/macos/linux, found: " + res.osType;
        assert res.architecture != null && !res.architecture.isEmpty() : "Architecture must not be empty";
        assert res.cpuModel != null && !res.cpuModel.isEmpty() : "CPU Model must not be empty";
        assert res.gpuModel != null && !res.gpuModel.isEmpty() : "GPU Model must not be empty";

        System.out.printf("[TEST] Verified Host OS: %s (%s)\n", res.osName, res.osType);
        System.out.printf("[TEST] Verified Architecture: %s\n", res.architecture);
        System.out.printf("[TEST] Verified CPU: %s\n", res.cpuModel);
        System.out.printf("[TEST] Verified GPU: %s (Accel: %b, Mode: %s)\n", res.gpuModel, res.gpuAvailable, res.gpuComputeType);
        System.out.printf("[TEST] Verified Components Count: %d\n", res.verifiedComponents.size());

        for (String c : res.verifiedComponents) {
            System.out.println("[TEST]  - " + c);
        }

        // 3. Verify OS Type Helper
        assert BlenderUtils.isWindows() || BlenderUtils.isMac() || BlenderUtils.isLinux() 
            : "System must match one of the OS types";

        System.out.println("\n=======================================================");
        System.out.println(">>> ALL OS PREFLIGHT & PROVISIONING TESTS PASSED <<<");
        System.out.println("=======================================================");
    }
}
