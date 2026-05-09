package com.tuandev.fbsbarcode.integration.wb;

import java.io.IOException;

public class WbApiException extends IOException {
    private final int statusCode;
    private final String responseBody;

    public WbApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
