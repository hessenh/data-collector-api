package no.ssb.dc.api.node;

public enum FormEncoding {
    TEXT_PLAIN("text/plain"),
    APPLICATION_X_WWW_FORM_URLENCODED("application/x-www-form-urlencoded"),
    MULTIPART_FORM_DATA("multipart/form-data"),
    APPLICATION_JSON("application/json");

    private final String mimeType;

    FormEncoding(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }
}
