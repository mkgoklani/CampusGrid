import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * CAMPUS GRID - FRAME INTEGRITY VALIDATOR
 * 
 * Inspects incoming binary frame buffers received over the network from worker nodes
 * before writing them to the disk filesystem.
 * 
 * Verifies:
 * 1. PNG Magic 8-Byte Signature: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A (\x89PNG\r\n\x1a\n)
 * 2. Standard IHDR Header Chunk: Extracts rendered image dimensions (width x height)
 * 3. Terminal IEND Chunk Signature: Ensures the frame transfer was not cut off or truncated
 * 4. File Size Boundary Check: Rejects empty or sub-minimum byte payloads
 * 
 * Prevents corrupted network streams or broken worker output from entering the final video sequence.
 */
public class FrameIntegrityValidator {

    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47,
        (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A
    };

    private static final byte[] IHDR_CHUNK = new byte[] { (byte) 'I', (byte) 'H', (byte) 'D', (byte) 'R' };
    private static final byte[] IEND_CHUNK = new byte[] { (byte) 'I', (byte) 'E', (byte) 'N', (byte) 'D' };

    public static class ValidationResult {
        public final boolean isValid;
        public final String errorReason;
        public final int fileSizeBytes;
        public final int width;
        public final int height;

        public ValidationResult(boolean isValid, String errorReason, int fileSizeBytes, int width, int height) {
            this.isValid = isValid;
            this.errorReason = errorReason;
            this.fileSizeBytes = fileSizeBytes;
            this.width = width;
            this.height = height;
        }

        public static ValidationResult valid(int size, int width, int height) {
            return new ValidationResult(true, null, size, width, height);
        }

        public static ValidationResult invalid(String reason, int size) {
            return new ValidationResult(false, reason, size, 0, 0);
        }

        @Override
        public String toString() {
            return isValid 
                ? String.format("ValidationResult[VALID, %dx%d px, %d bytes]", width, height, fileSizeBytes)
                : String.format("ValidationResult[INVALID: %s, %d bytes]", errorReason, fileSizeBytes);
        }
    }

    /**
     * Validates a raw PNG byte buffer.
     * 
     * @param data The raw byte array received from a worker node.
     * @param expectedMinBytes Minimum expected file size (e.g. 64 bytes).
     * @return ValidationResult indicating success or specific failure reason.
     */
    public static ValidationResult validatePng(byte[] data, int expectedMinBytes) {
        if (data == null || data.length == 0) {
            return ValidationResult.invalid("Zero-byte or null payload", 0);
        }

        // 1. Verify 8-byte PNG signature
        if (data.length < 8) {
            return ValidationResult.invalid("Truncated header: less than 8 bytes", data.length);
        }

        for (int i = 0; i < 8; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return ValidationResult.invalid("Invalid PNG magic byte signature (corrupted header)", data.length);
            }
        }

        // 2. Minimum sensible size check
        int minBytes = Math.max(32, expectedMinBytes);
        if (data.length < minBytes) {
            return ValidationResult.invalid(
                String.format("File size (%d bytes) below minimum threshold (%d bytes)", data.length, minBytes),
                data.length
            );
        }

        // 3. Verify IHDR Chunk presence and extract dimensions
        int width = 0;
        int height = 0;
        if (data.length >= 24) {
            boolean hasIhdr = (data[12] == IHDR_CHUNK[0] && data[13] == IHDR_CHUNK[1] &&
                               data[14] == IHDR_CHUNK[2] && data[15] == IHDR_CHUNK[3]);
            if (hasIhdr) {
                // IHDR dimensions are at byte offset 16-19 (width) and 20-23 (height) as 32-bit big-endian integers
                try {
                    ByteBuffer buf = ByteBuffer.wrap(data, 16, 8);
                    width = buf.getInt();
                    height = buf.getInt();
                } catch (Exception ignored) {}
            } else {
                return ValidationResult.invalid("Missing mandatory IHDR chunk in PNG header", data.length);
            }
        }

        // 3. Scan for IEND chunk (end of file marker)
        // IEND is located in the last 12-32 bytes of a completed PNG file
        boolean hasIend = false;
        int scanStart = Math.max(8, data.length - 64);
        for (int i = scanStart; i <= data.length - 4; i++) {
            if (data[i] == IEND_CHUNK[0] && data[i + 1] == IEND_CHUNK[1] &&
                data[i + 2] == IEND_CHUNK[2] && data[i + 3] == IEND_CHUNK[3]) {
                hasIend = true;
                break;
            }
        }

        if (!hasIend) {
            return ValidationResult.invalid("Missing terminal IEND chunk (file transmission was truncated mid-stream)", data.length);
        }

        return ValidationResult.valid(data.length, width, height);
    }

    /**
     * Overload with default minimum size threshold (64 bytes).
     */
    public static ValidationResult validatePng(byte[] data) {
        return validatePng(data, 64);
    }
}
