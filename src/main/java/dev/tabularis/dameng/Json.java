package dev.tabularis.dameng;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Json() {
    }
}
