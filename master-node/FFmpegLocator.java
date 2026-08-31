import java.io.File;
import java.util.regex.Pattern;

/**
 * Utility to locate the FFmpeg executable dynamically across different operating systems.
 */
public class FFmpegLocator {

    /**
     * Searches for the FFmpeg executable in the following order:
     * 1. Environment PATH
     * 2. CAMPUSGRID_FFMPEG environment variable
     * 3. ./tools/ffmpeg/bin/ffmpeg(.exe)
     * 4. ./ffmpeg/bin/ffmpeg(.exe)
     * 5. Windows common path: C:\ffmpeg\bin\ffmpeg.exe
     *
     * @return The absolute path to the FFmpeg executable, or null if not found.
     */
    public static String findExecutable() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String exeName = isWindows ? "ffmpeg.exe" : "ffmpeg";

        // 1. Environment PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String delimiter = isWindows ? ";" : ":";
            String[] dirs = pathEnv.split(Pattern.quote(delimiter));
            for (String dir : dirs) {
                File f = new File(dir, exeName);
                if (f.exists() && f.isFile() && (isWindows || f.canExecute())) {
                    String absPath = f.getAbsolutePath();
                    System.out.println("[VIDEO] FFmpeg found at: " + absPath);
                    return absPath;
                }
            }
        }

        // 2. CAMPUSGRID_FFMPEG environment variable
        String customEnv = System.getenv("CAMPUSGRID_FFMPEG");
        if (customEnv != null && !customEnv.trim().isEmpty()) {
            File f = new File(customEnv);
            if (f.exists()) {
                if (f.isDirectory()) {
                    File sub = new File(f, exeName);
                    if (sub.exists() && sub.isFile() && (isWindows || sub.canExecute())) {
                        String absPath = sub.getAbsolutePath();
                        System.out.println("[VIDEO] FFmpeg found at: " + absPath);
                        return absPath;
                    }
                } else if (f.isFile() && (isWindows || f.canExecute())) {
                    String absPath = f.getAbsolutePath();
                    System.out.println("[VIDEO] FFmpeg found at: " + absPath);
                    return absPath;
                }
            }
        }

        // 3. ./tools/ffmpeg/bin/ffmpeg(.exe)
        File f3 = new File("./tools/ffmpeg/bin/" + exeName);
        if (f3.exists() && f3.isFile() && (isWindows || f3.canExecute())) {
            String absPath = f3.getAbsolutePath();
            System.out.println("[VIDEO] FFmpeg found at: " + absPath);
            return absPath;
        }

        // 4. ./ffmpeg/bin/ffmpeg(.exe)
        File f4 = new File("./ffmpeg/bin/" + exeName);
        if (f4.exists() && f4.isFile() && (isWindows || f4.canExecute())) {
            String absPath = f4.getAbsolutePath();
            System.out.println("[VIDEO] FFmpeg found at: " + absPath);
            return absPath;
        }

        // 5. Windows common path
        if (isWindows) {
            File f5 = new File("C:\\ffmpeg\\bin\\ffmpeg.exe");
            if (f5.exists() && f5.isFile()) {
                String absPath = f5.getAbsolutePath();
                System.out.println("[VIDEO] FFmpeg found at: " + absPath);
                return absPath;
            }
        }

        return null;
    }
}
