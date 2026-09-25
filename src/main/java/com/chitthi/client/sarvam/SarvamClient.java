package com.chitthi.client.sarvam;

import com.chitthi.client.sarvam.dto.*;
import com.chitthi.config.SarvamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class SarvamClient {

    private static final Logger log = LoggerFactory.getLogger(SarvamClient.class);
    private final RestClient restClient;
    private final SarvamProperties properties;

    public SarvamClient(RestClient.Builder restClientBuilder, SarvamProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeoutMs());
        requestFactory.setReadTimeout((int) properties.readTimeoutMs());

        RestClient.Builder builder = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory);

        if (properties.apiSubscriptionKey() != null && !properties.apiSubscriptionKey().isBlank()) {
            builder.defaultHeader("api-subscription-key", properties.apiSubscriptionKey());
        }

        this.restClient = builder.build();
    }

    /**
     * Submit up to 10 pages for Document AI digitization.
     */
    public DigitiseJobResponse submitDigitiseJob(byte[] fileBytes, String filename, String languageCode) {
        log.info("Submitting Sarvam Document AI job for file: {}, language: {}", filename, languageCode);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        body.add("file", resource);
        body.add("language_code", languageCode);
        body.add("output_format", "md");

        return restClient.post()
                .uri(properties.docAiEndpoint())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(DigitiseJobResponse.class);
    }

    /**
     * Poll the status of a digitization job.
     */
    public DigitiseJobStatusResponse getJobStatus(String jobId) {
        return restClient.get()
                .uri(properties.docAiStatusEndpoint(), jobId)
                .retrieve()
                .body(DigitiseJobStatusResponse.class);
    }

    /**
     * Download the result archive (ZIP containing page markdown and metadata).
     */
    public byte[] downloadJobResult(String downloadUrl) {
        return RestClient.create().get()
                .uri(downloadUrl)
                .retrieve()
                .body(byte[].class);
    }

    /**
     * Translate text from source Indic language to English.
     */
    public TranslateResponse translate(TranslateRequest request) {
        log.debug("Translating text (chars: {}) from {} to {}",
                request.input() != null ? request.input().length() : 0,
                request.sourceLanguageCode(),
                request.targetLanguageCode());

        return restClient.post()
                .uri(properties.translateEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TranslateResponse.class);
    }

    /**
     * Convert text to speech via Sarvam Bulbul.
     */
    public TtsResponse textToSpeech(TtsRequest request) {
        log.debug("Calling Bulbul TTS for language: {}, texts count: {}",
                request.targetLanguageCode(),
                request.inputs() != null ? request.inputs().size() : 0);

        return restClient.post()
                .uri(properties.ttsEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TtsResponse.class);
    }

    /**
     * Convenience method to convert text to speech using configured speaker and model.
     */
    public TtsResponse textToSpeech(List<String> texts, String targetLanguageCode) {
        TtsRequest request = TtsRequest.create(texts, targetLanguageCode, properties.ttsSpeaker(), properties.ttsModel());
        return textToSpeech(request);
    }
}
