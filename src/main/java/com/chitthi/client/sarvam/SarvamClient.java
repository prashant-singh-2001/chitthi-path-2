package com.chitthi.client.sarvam;

import com.chitthi.client.sarvam.dto.*;
import com.chitthi.config.SarvamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class SarvamClient {

    private static final Logger log = LoggerFactory.getLogger(SarvamClient.class);
    private final RestClient restClient;
    private final SarvamProperties properties;

    public SarvamClient(SarvamProperties properties) {
        this.properties = properties;
        
        ClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-subscription-key", properties.apiSubscriptionKey())
                .requestFactory(requestFactory)
                .build();
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
                .uri("/doc-ai/v1/job/digitise")
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
                .uri("/doc-ai/v1/job/{jobId}/status", jobId)
                .retrieve()
                .body(DigitiseJobStatusResponse.class);
    }

    /**
     * Download the result archive (ZIP containing page markdown and metadata).
     */
    public byte[] downloadJobResult(String downloadUrl) {
        // downloadUrl might be absolute or relative
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
                .uri("/translate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TranslateResponse.class);
    }

    /**
     * Convert text to speech via Sarvam Bulbul v3.
     */
    public TtsResponse textToSpeech(TtsRequest request) {
        log.debug("Calling Bulbul TTS for language: {}, texts count: {}", 
                request.targetLanguageCode(), 
                request.inputs() != null ? request.inputs().size() : 0);

        return restClient.post()
                .uri("/text-to-speech/convert")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TtsResponse.class);
    }
}
