package com.chitthi.client.sarvam;

import com.chitthi.client.sarvam.dto.*;
import com.chitthi.config.SarvamProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class SarvamClientWireMockTest {

    private static WireMockServer wireMockServer;
    private SarvamClient sarvamClient;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        SarvamProperties properties = new SarvamProperties(
                wireMockServer.baseUrl(),
                "test-api-key-123",
                1000,
                5,
                5000,
                30000,
                "/doc-ai/v1/job/digitise",
                "/doc-ai/v1/job/{jobId}/status",
                "/translate",
                "/text-to-speech",
                "shubh",
                "bulbul:v3",
                "sarvam-translate:v1"
        );
        sarvamClient = new SarvamClient(RestClient.builder(), properties);
    }

    @Test
    void testSubmitDigitiseJob() {
        wireMockServer.stubFor(post(urlEqualTo("/doc-ai/v1/job/digitise"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-abc-123\"}")
                        .withStatus(200)));

        byte[] fakeFile = "fake pdf content".getBytes();
        DigitiseJobResponse response = sarvamClient.submitDigitiseJob(fakeFile, "sample.pdf", "hi-IN");

        assertNotNull(response);
        assertEquals("job-abc-123", response.jobId());

        wireMockServer.verify(postRequestedFor(urlEqualTo("/doc-ai/v1/job/digitise"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123")));
    }

    @Test
    void testGetJobStatus() {
        wireMockServer.stubFor(get(urlEqualTo("/doc-ai/v1/job/job-abc-123/status"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-abc-123\",\"status\":\"completed\",\"download_url\":\"https://example.com/res.zip\"}")
                        .withStatus(200)));

        DigitiseJobStatusResponse response = sarvamClient.getJobStatus("job-abc-123");

        assertNotNull(response);
        assertEquals("job-abc-123", response.jobId());
        assertTrue(response.isCompleted());
        assertEquals("https://example.com/res.zip", response.downloadUrl());
    }

    @Test
    void testTranslate() {
        wireMockServer.stubFor(post(urlEqualTo("/translate"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"translated_text\":\"Dear Grandfather, hope you are well.\"}")
                        .withStatus(200)));

        TranslateRequest request = TranslateRequest.toEnglish("आदरणीय दादाजी, आशा है आप सकुशल हैं।", "hi-IN");
        TranslateResponse response = sarvamClient.translate(request);

        assertNotNull(response);
        assertEquals("Dear Grandfather, hope you are well.", response.translatedText());
    }

    @Test
    void testTextToSpeech() {
        wireMockServer.stubFor(post(urlEqualTo("/text-to-speech"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"audios\":[\"UklGRigAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=\"]}")
                        .withStatus(200)));

        TtsResponse response = sarvamClient.textToSpeech(List.of("Hello world"), "en-IN");

        assertNotNull(response);
        assertNotNull(response.audios());
        assertEquals(1, response.audios().size());
        wireMockServer.verify(postRequestedFor(urlEqualTo("/text-to-speech"))
                .withHeader("api-subscription-key", equalTo("test-api-key-123")));
    }
}
