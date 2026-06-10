package dev.tabularis.dameng;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BlobWireTest {
    @TempDir
    Path tempDir;

    @Test
    void decodesAllSupportedWireFormats() throws Exception {
        assertArrayEquals("hi".getBytes(), BlobWire.decode("aGk=", 0));
        assertArrayEquals("hi".getBytes(), BlobWire.decode("data:application/octet-stream;base64,aGk=", 0));
        assertArrayEquals("hi".getBytes(), BlobWire.decode("BLOB:2:text/plain:aGk=", 0));

        Path file = tempDir.resolve("blob.bin");
        Files.write(file, "hi".getBytes());
        assertArrayEquals("hi".getBytes(), BlobWire.decode("BLOB_FILE_REF:2:text/plain:" + file, 0));
    }

    @Test
    void encodesTabularisBlobWireFormat() {
        assertEquals("BLOB:2:application/octet-stream:aGk=", BlobWire.encode("hi".getBytes()));
    }

    @Test
    void rejectsInvalidOrOversizedBlobValues() throws Exception {
        Path file = tempDir.resolve("blob.bin");
        Files.write(file, "hello".getBytes());

        assertThrows(RpcException.class, () -> BlobWire.decode("BLOB:3:text/plain:aGk=", 0));
        assertThrows(RpcException.class, () -> BlobWire.decode("BLOB_FILE_REF:2:text/plain:" + file, 0));
        assertThrows(RpcException.class, () -> BlobWire.decode("aGk=", 1));
        assertThrows(RpcException.class, () -> BlobWire.decode("not-base64", 0));
        assertThrows(RpcException.class, () -> BlobWire.decode("data:text/plain,not-base64", 0));
    }
}
