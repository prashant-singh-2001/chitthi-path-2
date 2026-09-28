package com.chitthi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudioStitcherServiceTest {

    private AudioStitcherService stitcherService;

    @BeforeEach
    void setUp() {
        stitcherService = new AudioStitcherService();
    }

    @Test
    void isRiffWav_withValidWav_shouldReturnTrue() {
        byte[] wav = AudioStitcherService.createDummyWav(100, 22050);
        assertThat(stitcherService.isRiffWav(wav)).isTrue();
    }

    @Test
    void isRiffWav_withInvalidData_shouldReturnFalse() {
        assertThat(stitcherService.isRiffWav(new byte[]{1, 2, 3})).isFalse();
        assertThat(stitcherService.isRiffWav(null)).isFalse();
    }

    @Test
    void concatenateWavFiles_shouldCombinePcmDataAndUpdateHeader() {
        int samples1 = 100;
        int samples2 = 150;
        byte[] wav1 = AudioStitcherService.createDummyWav(samples1, 22050); // 44 + 200 = 244 bytes
        byte[] wav2 = AudioStitcherService.createDummyWav(samples2, 22050); // 44 + 300 = 344 bytes

        byte[] stitched = stitcherService.concatenateWavFiles(List.of(wav1, wav2));

        assertThat(stitcherService.isRiffWav(stitched)).isTrue();

        int expectedPcmBytes = (samples1 + samples2) * 2; // 250 * 2 = 500 bytes
        int expectedTotalLength = 44 + expectedPcmBytes; // 544 bytes
        assertThat(stitched.length).isEqualTo(expectedTotalLength);

        // Verify subchunk2 size at byte 40 (little endian)
        ByteBuffer buffer = ByteBuffer.wrap(stitched).order(ByteOrder.LITTLE_ENDIAN);
        int subchunk2Size = buffer.getInt(40);
        assertThat(subchunk2Size).isEqualTo(expectedPcmBytes);

        // Verify RIFF chunk size at byte 4 (36 + subchunk2Size)
        int chunkSize = buffer.getInt(4);
        assertThat(chunkSize).isEqualTo(36 + expectedPcmBytes);
    }

    @Test
    void concatenateWavFiles_withSingleChunk_shouldReturnOriginal() {
        byte[] wav = AudioStitcherService.createDummyWav(50, 22050);
        byte[] stitched = stitcherService.concatenateWavFiles(List.of(wav));
        assertThat(stitched).isEqualTo(wav);
    }
}
