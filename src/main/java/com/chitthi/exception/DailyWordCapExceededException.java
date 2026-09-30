package com.chitthi.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class DailyWordCapExceededException extends RuntimeException {

    private final String ownerId;
    private final int currentWords;
    private final int cap;

    public DailyWordCapExceededException(String ownerId, int currentWords, int cap) {
        super(String.format("Daily word processing cap reached for owner '%s'. Processed: %d words, Cap: %d words. Quota resets at midnight.",
                ownerId, currentWords, cap));
        this.ownerId = ownerId;
        this.currentWords = currentWords;
        this.cap = cap;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public int getCurrentWords() {
        return currentWords;
    }

    public int getCap() {
        return cap;
    }
}
