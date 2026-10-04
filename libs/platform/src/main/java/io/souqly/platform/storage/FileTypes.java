package io.souqly.platform.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Recognises file formats from their first bytes. A declared content type is only a claim; this
 * is what the file actually is.
 */
public final class FileTypes {

    /** Enough leading bytes to recognise every supported format. */
    public static final int SNIFF_BYTES = 12;

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};

    private FileTypes() {
    }

    public static Optional<String> detect(byte[] head) {
        if (startsWith(head, 0, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, 0, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(head, 0, RIFF) && startsWith(head, 8, WEBP)) {
            return Optional.of("image/webp");
        }
        if (startsWith(head, 0, PDF)) {
            return Optional.of("application/pdf");
        }
        return Optional.empty();
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException("Unsupported file type " + contentType);
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
