package dev.tabularis.dameng;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

final class BlobWire {
    private static final String DEFAULT_MIME = "application/octet-stream";

    private BlobWire() {
    }

    static byte[] decode(String value, long maxBlobSize) {
        String text = value == null ? "" : value.strip();
        if (text.regionMatches(true, 0, "data:", 0, 5)) {
            return decodeBase64(dataUriPayload(text), maxBlobSize);
        }
        if (text.startsWith("BLOB:")) {
            return decodeBlobWire(text, maxBlobSize);
        }
        if (text.startsWith("BLOB_FILE_REF:")) {
            return decodeBlobFileRef(text, maxBlobSize);
        }
        return decodeBase64(text, maxBlobSize);
    }

    static String encode(byte[] bytes) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        return "BLOB:" + data.length + ":" + DEFAULT_MIME + ":"
                + Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decodeBlobWire(String value, long maxBlobSize) {
        String rest = value.substring("BLOB:".length());
        String[] parts = rest.split(":", 3);
        if (parts.length != 3) {
            throw new RpcException(-32602, "Invalid BLOB wire format.");
        }
        long declaredSize = parseSize(parts[0], "BLOB");
        checkSize(declaredSize, maxBlobSize);
        byte[] bytes = decodeBase64(parts[2], maxBlobSize);
        if (bytes.length != declaredSize) {
            throw new RpcException(-32602, "BLOB wire size mismatch: declared " + declaredSize
                    + " bytes but decoded " + bytes.length + " bytes.");
        }
        return bytes;
    }

    private static byte[] decodeBlobFileRef(String value, long maxBlobSize) {
        String rest = value.substring("BLOB_FILE_REF:".length());
        String[] parts = rest.split(":", 3);
        if (parts.length != 3) {
            throw new RpcException(-32602, "Invalid BLOB_FILE_REF wire format.");
        }
        long declaredSize = parseSize(parts[0], "BLOB_FILE_REF");
        checkSize(declaredSize, maxBlobSize);

        Path path = Path.of(parts[2]);
        try {
            long actualSize = Files.size(path);
            checkSize(actualSize, maxBlobSize);
            if (actualSize != declaredSize) {
                throw new RpcException(-32602, "BLOB_FILE_REF size mismatch: declared " + declaredSize
                        + " bytes but file is " + actualSize + " bytes.");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RpcException(-32602, "Could not read BLOB_FILE_REF file: " + e.getMessage());
        }
    }

    private static String dataUriPayload(String value) {
        int comma = value.indexOf(',');
        if (comma < 0 || !value.substring(0, comma).toLowerCase().contains(";base64")) {
            throw new RpcException(-32602, "Binary data URI values must include ';base64,'.");
        }
        return value.substring(comma + 1);
    }

    private static byte[] decodeBase64(String value, long maxBlobSize) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new RpcException(-32602, "Binary values must be base64 encoded.");
        }
        checkSize(bytes.length, maxBlobSize);
        return bytes;
    }

    private static long parseSize(String value, String format) {
        try {
            long size = Long.parseLong(value);
            if (size < 0) {
                throw new NumberFormatException("negative");
            }
            return size;
        } catch (NumberFormatException e) {
            throw new RpcException(-32602, "Invalid " + format + " size.");
        }
    }

    private static void checkSize(long size, long maxBlobSize) {
        if (maxBlobSize > 0 && size > maxBlobSize) {
            throw new RpcException(-32602, "Binary value is " + size
                    + " bytes, exceeding max_blob_size " + maxBlobSize + ".");
        }
    }
}
