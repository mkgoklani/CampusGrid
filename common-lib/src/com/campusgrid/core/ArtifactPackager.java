package com.campusgrid.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Standardizes the packaging of render artifacts (like PNG frames) 
 * into a compressed byte array for efficient network transmission.
 */
public class ArtifactPackager {

    /**
     * Compresses all files in a directory matching the given extension into a single byte array.
     * 
     * @param directoryPath The path containing the rendered frames
     * @param extension The file extension to filter by (e.g., ".png")
     * @return A zipped byte array containing the files, ready for the ResultPayload
     * @throws IOException If file reading or zipping fails
     */
    public static byte[] packageArtifacts(String directoryPath, String extension) throws IOException {
        File dir = new File(directoryPath);
        
        // Validate directory exists
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Invalid or missing working directory: " + directoryPath);
        }

        // Find all files matching the target extension (e.g., .png)
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(extension.toLowerCase()));
        
        if (files == null || files.length == 0) {
            return new byte[0]; // Return empty array if no artifacts were generated
        }

        // Compress files into a byte array
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (File file : files) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    ZipEntry zipEntry = new ZipEntry(file.getName());
                    zos.putNextEntry(zipEntry);

                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = fis.read(buffer)) >= 0) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                }
            }
            zos.finish();
            return baos.toByteArray();
        }
    }
}