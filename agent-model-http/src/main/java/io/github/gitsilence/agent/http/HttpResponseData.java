package io.github.gitsilence.agent.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpResponseData {

    private final int status;
    private final String body;
    private final Map<String, List<String>> headers;

    public HttpResponseData(int status,
                            String body,
                            Map<String, List<String>> headers) {
        this.status = status;
        this.body = body;
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(entry.getKey(), Collections.unmodifiableList(
                    new ArrayList<String>(entry.getValue())
                ));
            }
        }
        this.headers = Collections.unmodifiableMap(copy);
    }

    public int getStatus() { return status; }
    public String getBody() { return body; }
    public Map<String, List<String>> getHeaders() { return headers; }
    public boolean isSuccessful() { return status >= 200 && status < 300; }
}
