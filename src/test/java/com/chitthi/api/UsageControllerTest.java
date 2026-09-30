package com.chitthi.api;

import com.chitthi.api.dto.EndpointUsageBreakdown;
import com.chitthi.api.dto.UsageSummaryResponse;
import com.chitthi.service.UsageLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsageController.class)
class UsageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UsageLedgerService usageLedgerService;

    @Test
    void getUsageSummary_shouldReturnUsageDetails() throws Exception {
        EndpointUsageBreakdown breakdown = new EndpointUsageBreakdown(
                "/translate", 5, BigDecimal.valueOf(5000), "characters", new BigDecimal("5.0000"),
                250.0, 150L, 350L
        );
        UsageSummaryResponse response = new UsageSummaryResponse(
                "default",
                null,
                1200,
                7000,
                5800,
                false,
                new BigDecimal("12.5000"),
                10,
                BigDecimal.valueOf(15000),
                BigDecimal.valueOf(2),
                List.of(breakdown),
                List.of()
        );

        when(usageLedgerService.getUsageSummary("default", null)).thenReturn(response);

        mockMvc.perform(get("/api/usage?ownerId=default")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("default"))
                .andExpect(jsonPath("$.dailyWordsProcessed").value(1200))
                .andExpect(jsonPath("$.dailyWordCap").value(7000))
                .andExpect(jsonPath("$.dailyWordsRemaining").value(5800))
                .andExpect(jsonPath("$.capExceeded").value(false))
                .andExpect(jsonPath("$.totalEstimatedCostInr").value(12.5))
                .andExpect(jsonPath("$.endpointBreakdown[0].endpoint").value("/translate"))
                .andExpect(jsonPath("$.endpointBreakdown[0].callCount").value(5));
    }

    @Test
    void getUsageSummary_withDocumentId_shouldReturnDocumentScopedSummary() throws Exception {
        UUID docId = UUID.randomUUID();
        UsageSummaryResponse response = new UsageSummaryResponse(
                "default",
                docId,
                500,
                7000,
                6500,
                false,
                new BigDecimal("3.5000"),
                2,
                BigDecimal.valueOf(3000),
                BigDecimal.valueOf(1),
                List.of(),
                List.of()
        );

        when(usageLedgerService.getUsageSummary("default", docId)).thenReturn(response);

        mockMvc.perform(get("/api/usage?ownerId=default&documentId=" + docId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId.toString()))
                .andExpect(jsonPath("$.totalEstimatedCostInr").value(3.5));
    }
}
