import java.io.*;
import java.nio.file.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class TestVideoAssembler {

    public static void main(String[] args) throws Exception {
        System.out.println("=== STARTING VIDEO ASSEMBLER TEST ===");

        String jobId = "test-job-assembler";
        File jobDir = new File("./output/" + jobId);
        if (!jobDir.exists()) {
            jobDir.mkdirs();
        }

        // Generate 5 mock frame PNG files
        for (int i = 1; i <= 5; i++) {
            BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color((i * 45) % 256, (i * 90) % 256, 120));
            g.fillRect(0, 0, 320, 240);
            g.setColor(Color.WHITE);
            g.drawString("Frame: " + i, 50, 50);
            g.dispose();

            File outFile = new File(jobDir, String.format("frame_%04d.png", i));
            ImageIO.write(img, "PNG", outFile);
            System.out.println("Generated mock frame: " + outFile.getAbsolutePath());
        }

        System.out.println("Triggering VideoAssembler...");
        Path videoPath = VideoAssembler.assembleVideo(jobId, 5, 24);

        if (videoPath != null && Files.exists(videoPath)) {
            System.out.println("✔ SUCCESS: video compiled successfully at " + videoPath.toAbsolutePath());
        } else {
            System.out.println("❌ FAILED: video compilation returned null (or file missing).");
        }
        
        // Clean up
        File[] files = jobDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        jobDir.delete();
    }
}
