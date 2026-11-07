package eu.neverblink.ohi.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The Jackson mapper of the SDK. */
final class Json {
    /** Rejects anything after the JSON value, which Jackson ignores by default. */
    static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private Json() {
    }
}
