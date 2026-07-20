package com.campusgrid.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Step 4 audit: validates that {@link MandelbrotTask} is safely serializable for
 * Master-Worker transport and remains executable after a byte-stream round-trip.
 */
public final class SerializationAudit {

    private static final long EXPECTED_SERIAL_UID = 1L;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int MAX_ITERATIONS = 255;

    private SerializationAudit() {
    }

    public static void main(String[] args) {
        System.out.println("\u001B[34m[AUDIT] Starting Serialization Integrity Check...\u001B[0m");

        try {
            verifyHeadlessContract(GridTask.class);
            verifyHeadlessContract(MandelbrotTask.class);

            verifyDeclaredSerialUid(GridTask.class, EXPECTED_SERIAL_UID);
            verifyDeclaredSerialUid(MandelbrotTask.class, EXPECTED_SERIAL_UID);
            verifyRuntimeSerialUid(MandelbrotTask.class, EXPECTED_SERIAL_UID);

            MandelbrotTask originalTask = new MandelbrotTask(
                    -2.0d, 1.0d, -1.0d, 1.0d, WIDTH, HEIGHT, MAX_ITERATIONS
            );

            byte[] taskPayload = serializeToBytes(originalTask);
            MandelbrotTask restoredTask = deserializeFromBytes(taskPayload, MandelbrotTask.class);

            int[][] result = restoredTask.execute();
            validateResultShape(result, WIDTH, HEIGHT);

            byte[] resultPayload = serializeToBytes(result);
            double resultSizeKb = resultPayload.length / 1024.0d;

            System.out.println("[INFO] Serialized MandelbrotTask size: " + taskPayload.length + " bytes");
            System.out.printf("[INFO] Serialized int[][] payload size: %d bytes (%.3f KB)%n",
                    resultPayload.length, resultSizeKb);
            System.out.println("\u001B[32m[SUCCESS] Serialization contract verified. Round-trip task is functional.\u001B[0m");
        } catch (NotSerializableException ex) {
            System.err.println("\u001B[31m[FAILURE] NotSerializableException: " + ex.getMessage() + "\u001B[0m");
            System.err.println("\u001B[31m[FAILURE] Non-serializable field/type detected. Fix contract violation.\u001B[0m");
            System.exit(1);
        } catch (InvalidClassException ex) {
            System.err.println("\u001B[31m[FAILURE] serialVersionUID mismatch: " + ex.getMessage() + "\u001B[0m");
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("\u001B[31m[FAILURE] Serialization audit error: " + ex.getMessage() + "\u001B[0m");
            System.exit(1);
        }
    }

    private static byte[] serializeToBytes(Object value) throws Exception {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(byteStream)) {
            output.writeObject(value);
            output.flush();
            return byteStream.toByteArray();
        }
    }

    private static <T> T deserializeFromBytes(byte[] payload, Class<T> expectedType) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            Object value = input.readObject();
            if (!expectedType.isInstance(value)) {
                throw new IllegalStateException("Unexpected deserialized type: " + value.getClass().getName());
            }
            return expectedType.cast(value);
        }
    }

    private static void verifyDeclaredSerialUid(Class<?> type, long expected) throws Exception {
        Field field = type.getDeclaredField("serialVersionUID");
        field.setAccessible(true);
        long actual = field.getLong(null);
        if (actual != expected) {
            throw new IllegalStateException(type.getSimpleName() + " serialVersionUID mismatch: " + actual);
        }
    }

    private static void verifyRuntimeSerialUid(Class<? extends Serializable> type, long expected) {
        ObjectStreamClass descriptor = ObjectStreamClass.lookup(type);
        if (descriptor == null) {
            throw new IllegalStateException("No ObjectStreamClass descriptor for " + type.getName());
        }
        long actual = descriptor.getSerialVersionUID();
        if (actual != expected) {
            throw new IllegalStateException(type.getSimpleName() + " runtime serialVersionUID mismatch: " + actual);
        }
    }

    private static void validateResultShape(int[][] result, int expectedWidth, int expectedHeight) {
        if (result == null) {
            throw new IllegalStateException("execute() returned null.");
        }
        if (result.length != expectedWidth) {
            throw new IllegalStateException("Unexpected width: " + result.length + ", expected: " + expectedWidth);
        }
        for (int x = 0; x < expectedWidth; x++) {
            if (result[x] == null) {
                throw new IllegalStateException("Null column at index: " + x);
            }
            if (result[x].length != expectedHeight) {
                throw new IllegalStateException(
                        "Unexpected height at column " + x + ": " + result[x].length + ", expected: " + expectedHeight
                );
            }
        }
    }

    private static void verifyHeadlessContract(Class<?> type) {
        assertAllowedType(type);

        for (Field field : type.getDeclaredFields()) {
            assertAllowedType(field.getType());
        }
        for (Method method : type.getDeclaredMethods()) {
            assertAllowedType(method.getReturnType());
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (Class<?> parameterType : parameterTypes) {
                assertAllowedType(parameterType);
            }
        }
    }

    private static void assertAllowedType(Class<?> type) {
        Class<?> current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        String name = current.getName();
        if (name.startsWith("java.awt.") || name.startsWith("javax.swing.")) {
            throw new IllegalStateException("Forbidden GUI dependency detected: " + name);
        }
    }
}
