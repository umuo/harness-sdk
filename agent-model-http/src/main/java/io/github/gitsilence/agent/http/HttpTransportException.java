package io.github.gitsilence.agent.http;

public final class HttpTransportException extends RuntimeException {

    private final int status;
    private final String responseBody;

    public HttpTransportException(int status, String responseBody) {
        super("HTTP " + status + ": " + truncate(responseBody, 4000));
        this.status = status;
        this.responseBody = responseBody;
    }

    public int getStatus() { return status; }
    public String getResponseBody() { return responseBody; }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
