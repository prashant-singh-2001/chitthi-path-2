package com.chitthi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkingServiceTest {

    private TextChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new TextChunkingService();
    }

    @Test
    void chunkText_withShortText_shouldReturnSingleChunk() {
        String text = "नमस्ते नानाजी, आपका पत्र मिला।";
        List<String> chunks = chunkingService.chunkText(text, 2000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void chunkText_withMultipleDevanagariSentences_shouldSplitAtDanda() {
        String sentence1 = "नानाजी का पत्र 1987 में जयपुर से आया था।";
        String sentence2 = "उसमें पुराने मकान की मरम्मत का ज़िक्र था।";
        String sentence3 = "हम सब बहुत खुश हुए।";
        String fullText = sentence1 + " " + sentence2 + " " + sentence3;

        // Force split by setting maxChars lower than combined length of sentence 1 + sentence 2
        int maxChars = sentence1.length() + 10;
        List<String> chunks = chunkingService.chunkText(fullText, maxChars);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo(sentence1);
        assertThat(chunks.get(1)).isEqualTo(sentence2);
        assertThat(chunks.get(2)).isEqualTo(sentence3);
    }

    @Test
    void chunkText_withLongSingleSentence_shouldSplitOnWordsWithoutExceedingMaxChars() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("शब्द").append(i).append(" ");
        }
        String longText = sb.toString().trim(); // No punctuation

        int maxChars = 40;
        List<String> chunks = chunkingService.chunkText(longText, maxChars);

        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(maxChars);
        }
    }

    @Test
    void chunkText_withEmptyOrNull_shouldReturnEmptyList() {
        assertThat(chunkingService.chunkText(null, 100)).isEmpty();
        assertThat(chunkingService.chunkText("", 100)).isEmpty();
        assertThat(chunkingService.chunkText("   ", 100)).isEmpty();
    }
}
