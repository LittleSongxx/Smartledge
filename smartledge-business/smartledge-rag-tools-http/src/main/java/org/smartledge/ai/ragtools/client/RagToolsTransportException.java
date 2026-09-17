package org.smartledge.ai.ragtools.client;

import org.springframework.http.MediaType;

/** Stable failure projection for HTTP status, protocol and timeout failures. */
public final class RagToolsTransportException extends IllegalStateException {

    private final String endpoint;
    private final Integer status;
    private final MediaType contentType;
    private final String bodyPreview;

    public RagToolsTransportException(String message,
                                       String endpoint,
                                       Integer status,
                                       MediaType contentType,
                                       String bodyPreview,
                                       Throwable cause) {
        super(format(message, endpoint, status, contentType, bodyPreview), cause);
        this.endpoint = endpoint;
        this.status = status;
        this.contentType = contentType;
        this.bodyPreview = bodyPreview == null ? "" : bodyPreview;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Integer getStatus() {
        return status;
    }

    public MediaType getContentType() {
        return contentType;
    }

    public String getBodyPreview() {
        return bodyPreview;
    }

    private static String format(String message,
                                  String endpoint,
                                  Integer status,
                                  MediaType contentType,
                                  String bodyPreview) {
        return message + ": endpoint=" + endpoint
            + ", status=" + (status == null ? "n/a" : status)
            + ", contentType=" + (contentType == null ? "n/a" : contentType)
            + ", body=" + (bodyPreview == null ? "" : bodyPreview);
    }
}
