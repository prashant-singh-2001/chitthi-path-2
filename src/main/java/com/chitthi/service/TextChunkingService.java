package com.chitthi.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TextChunkingService {

    // Regex pattern matching sentence boundaries including Devanagari danda (।), double danda (॥), ., ?, !, \n
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("[^।॥.?!\\n]+[।॥.?!\\n]*|\\n+", Pattern.DOTALL);

    /**
     * Splits text into coherent chunks of at most maxChars characters,
     * respecting sentence boundaries where possible and falling back to word boundaries.
     */
    public List<String> chunkText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return List.of(trimmed);
        }

        List<String> sentences = extractSentences(trimmed);
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            String sentenceTrimmed = sentence.trim();
            if (sentenceTrimmed.isEmpty()) {
                continue;
            }

            if (sentenceTrimmed.length() > maxChars) {
                // If a single sentence exceeds maxChars, flush current chunk and split on words
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                chunks.addAll(splitLongSentence(sentenceTrimmed, maxChars));
                continue;
            }

            int potentialLength = currentChunk.length() == 0 
                    ? sentenceTrimmed.length() 
                    : currentChunk.length() + 1 + sentenceTrimmed.length();

            if (potentialLength <= maxChars) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(" ");
                }
                currentChunk.append(sentenceTrimmed);
            } else {
                chunks.add(currentChunk.toString().trim());
                currentChunk.setLength(0);
                currentChunk.append(sentenceTrimmed);
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> extractSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            sentences.add(matcher.group());
        }
        if (sentences.isEmpty()) {
            sentences.add(text);
        }
        return sentences;
    }

    private List<String> splitLongSentence(String longSentence, int maxChars) {
        List<String> result = new ArrayList<>();
        String[] words = longSentence.split("\\s+");
        StringBuilder sb = new StringBuilder();

        for (String word : words) {
            if (word.length() > maxChars) {
                // Hard cut word if a single word is larger than maxChars
                if (sb.length() > 0) {
                    result.add(sb.toString().trim());
                    sb.setLength(0);
                }
                for (int i = 0; i < word.length(); i += maxChars) {
                    result.add(word.substring(i, Math.min(i + maxChars, word.length())));
                }
                continue;
            }

            int potentialLength = sb.length() == 0 ? word.length() : sb.length() + 1 + word.length();
            if (potentialLength <= maxChars) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(word);
            } else {
                result.add(sb.toString().trim());
                sb.setLength(0);
                sb.append(word);
            }
        }

        if (sb.length() > 0) {
            result.add(sb.toString().trim());
        }

        return result;
    }
}
