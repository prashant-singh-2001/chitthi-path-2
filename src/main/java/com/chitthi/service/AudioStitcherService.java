package com.chitthi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AudioStitcherService {

    private static final Logger log = LoggerFactory.getLogger(AudioStitcherService.class);

    /**
     * Concatenates a list of RIFF PCM WAV audio byte arrays into a single seamless WAV track.
     * If the chunks are not WAV or cannot be parsed, falls back to direct byte concatenation.
     */
    public byte[] concatenateWavFiles(List<byte[]> wavChunks) {
        if (wavChunks == null || wavChunks.isEmpty()) {
            return new byte[0];
        }
        if (wavChunks.size() == 1) {
            return wavChunks.get(0);
        }

        byte[] firstChunk = wavChunks.get(0);
        if (!isRiffWav(firstChunk)) {
            log.warn("Input audio is not standard RIFF WAV format. Falling back to direct concatenation.");
            return directConcatenate(wavChunks);
        }

        try {
            int firstDataOffset = findDataChunkOffset(firstChunk);
            byte[] header = new byte[firstDataOffset + 8]; // Include 8 bytes of "data" tag + subchunk size
            System.arraycopy(firstChunk, 0, header, 0, header.length);

            ByteArrayOutputStream pcmDataStream = new ByteArrayOutputStream();

            for (byte[] chunk : wavChunks) {
                if (isRiffWav(chunk)) {
                    int dataOffset = findDataChunkOffset(chunk);
                    int dataLen = chunk.length - (dataOffset + 8);
                    if (dataLen > 0) {
                        pcmDataStream.write(chunk, dataOffset + 8, dataLen);
                    }
                } else {
                    pcmDataStream.write(chunk);
                }
            }

            byte[] allPcmData = pcmDataStream.toByteArray();
            int totalDataLen = allPcmData.length;
            int totalRiffLen = totalDataLen + header.length - 8;

            // Update ChunkSize (offset 4) and Subchunk2Size (data size at firstDataOffset + 4)
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(4, totalRiffLen);
            buffer.putInt(firstDataOffset + 4, totalDataLen);

            ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
            finalOut.write(header);
            finalOut.write(allPcmData);

            log.info("Successfully stitched {} WAV chunks into single WAV: total PCM size = {} bytes",
                    wavChunks.size(), totalDataLen);
            return finalOut.toByteArray();

        } catch (Exception e) {
            log.error("Failed to stitch WAV files cleanly, falling back to direct concatenation", e);
            return directConcatenate(wavChunks);
        }
    }

    public boolean isRiffWav(byte[] data) {
        if (data == null || data.length < 44) {
            return false;
        }
        String riff = new String(data, 0, 4, StandardCharsets.US_ASCII);
        String wave = new String(data, 8, 4, StandardCharsets.US_ASCII);
        return "RIFF".equals(riff) && "WAVE".equals(wave);
    }

    private int findDataChunkOffset(byte[] wavData) {
        // Standard PCM WAV usually has "data" at index 36, but can be preceded by metadata chunks
        for (int i = 12; i < wavData.length - 8; i++) {
            if (wavData[i] == 'd' && wavData[i + 1] == 'a' && wavData[i + 2] == 't' && wavData[i + 3] == 'a') {
                return i;
            }
        }
        return 36; // Default standard offset
    }

    private byte[] directConcatenate(List<byte[]> chunks) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            if (chunk != null) {
                try {
                    baos.write(chunk);
                } catch (IOException ignored) {}
            }
        }
        return baos.toByteArray();
    }

    /**
     * Helper to generate a minimal valid PCM WAV byte array for testing.
     */
    public static byte[] createDummyWav(int pcmSamplesCount, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int subChunk1Size = 16;
        int subChunk2Size = pcmSamplesCount * channels * (bitsPerSample / 8);
        int chunkSize = 36 + subChunk2Size;
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        short blockAlign = (short) (channels * (bitsPerSample / 8));

        ByteBuffer buffer = ByteBuffer.allocate(44 + subChunk2Size).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF header
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(chunkSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        // fmt subchunk
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(subChunk1Size);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort((short) bitsPerSample);
        // data subchunk
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(subChunk2Size);

        // Fill with silent samples
        for (int i = 0; i < pcmSamplesCount; i++) {
            buffer.putShort((short) 0);
        }

        return buffer.array();
    }
}
