package com.chitthi.exception;

public class RateLimitExceededException extends RuntimeException {

    private final String clientIp;
    private final int retryAfterSeconds;

    public RateLimitExceededException(String clientIp, int retryAfterSeconds) {
        super("Too many requests from IP " + clientIp + ". Please try again in " + retryAfterSeconds + " seconds.");
        this.clientIp = clientIp;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getClientIp() {
        return clientIp;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
