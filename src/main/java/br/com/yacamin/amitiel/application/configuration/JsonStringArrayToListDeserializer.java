package br.com.yacamin.amitiel.application.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class JsonStringArrayToListDeserializer extends JsonDeserializer<List<String>> {

    private static final TypeReference<List<String>> LIST_STR = new TypeReference<>() {};

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonToken token = p.currentToken();

        if (token == JsonToken.VALUE_NULL) {
            return Collections.emptyList();
        }

        // Caso venha como array "normal"
        if (token == JsonToken.START_ARRAY) {
            return mapper.readValue(p, LIST_STR);
        }

        // Caso venha como string contendo JSON (ex: "[\"UP\",\"DOWN\"]")
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getValueAsString();
            if (raw == null || raw.isBlank() || raw.equals("[]")) {
                return Collections.emptyList();
            }
            return mapper.readValue(raw, LIST_STR);
        }

        // fallback
        String raw = p.getValueAsString();
        if (raw == null || raw.isBlank() || raw.equals("[]")) {
            return Collections.emptyList();
        }
        return mapper.readValue(raw, LIST_STR);
    }
}
