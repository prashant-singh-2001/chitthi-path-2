package com.chitthi.api;

import com.chitthi.api.dto.UsageSummaryResponse;
import com.chitthi.exception.DailyWordCapExceededException;
import com.chitthi.service.UsageLedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageLedgerService usageLedgerService;

    public UsageController(UsageLedgerService usageLedgerService) {
        this.usageLedgerService = usageLedgerService;
    }

    /**
     * Retrieve usage ledger, estimated spend, latency metrics, and daily word cap tracking.
     */
    @GetMapping
    public ResponseEntity<UsageSummaryResponse> getUsageSummary(
            @RequestParam(value = "ownerId", defaultValue = "default") String ownerId,
            @RequestParam(value = "documentId", required = false) UUID documentId) {
        return ResponseEntity.ok(usageLedgerService.getUsageSummary(ownerId, documentId));
    }

    @ExceptionHandler(DailyWordCapExceededException.class)
    public ResponseEntity<Map<String, Object>> handleDailyWordCapExceeded(DailyWordCapExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "DAILY_WORD_CAP_EXCEEDED",
                "message", ex.getMessage(),
                "ownerId", ex.getOwnerId(),
                "currentWords", ex.getCurrentWords(),
                "cap", ex.getCap(),
                "timestamp", Instant.now()
        ));
    }
}
