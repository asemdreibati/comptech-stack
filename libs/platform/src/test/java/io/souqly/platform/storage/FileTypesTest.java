package io.souqly.platform.storage;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypesTest {

    @Test
    void recognisesFilesByTheirFirstBytes() {
        assertThat(FileTypes.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).contains("image/jpeg");
        assertThat(FileTypes.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})).contains("image/png");
        assertThat(FileTypes.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).contains("image/webp");
        assertThat(FileTypes.detect("%PDF-1.7\n%".getBytes(StandardCharsets.ISO_8859_1))).contains("application/pdf");
    }

    @Test
    void aDeclaredTypeIsNotEnough() {
        assertThat(FileTypes.detect("#!/bin/sh\nrm -rf /".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(FileTypes.detect("<html><script>".getBytes(StandardCharsets.ISO_8859_1))).isEmpty();
        assertThat(FileTypes.detect(new byte[] {'%', 'P'})).isEmpty();
    }
}
