package com.campusgrid.core;

/**
 * Simple runtime audit for AuthGateway hashing and authentication behavior.
 */
public final class SecurityAudit {

    private SecurityAudit() {
    }

    public static void main(String[] args) {
        System.out.println("\u001B[34m[AUDIT] Starting Security Gateway Check...\u001B[0m");

        String defaultPassword = "cs-lab-2026";
        String wrongPassword = "cs-lab-2027";

        String hash = AuthGateway.hashPassword(defaultPassword);
        boolean pass = AuthGateway.authenticate(defaultPassword, hash);
        boolean fail = AuthGateway.authenticate(wrongPassword, hash);

        System.out.println("[INFO] SHA-256 hash: " + hash);

        if (!pass) {
            System.err.println("\u001B[31m[FAILURE] Correct password was rejected.\u001B[0m");
            System.exit(1);
        }
        if (fail) {
            System.err.println("\u001B[31m[FAILURE] Incorrect password was accepted.\u001B[0m");
            System.exit(1);
        }

        System.out.println("\u001B[32m[SUCCESS] AuthGateway validation passed.\u001B[0m");
    }
}
