package com.nexusagent.agentrun.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class RunJson {
    private final ObjectMapper mapper;

    public RunJson(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode object() { return mapper.createObjectNode(); }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }

    public JsonNode parse(String value) {
        if (value == null) { return null; }
        try { return mapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid persisted run JSON", e); }
    }

    public static void fields(JsonNode value, String... allowed) {
        if (value == null || !value.isObject()) { throw RunException.invalid("Expected a JSON object"); }
        Set<String> names = Set.of(allowed);
        value.fieldNames().forEachRemaining(name -> {
            if (!names.contains(name)) { throw RunException.invalid("Unexpected field: " + name); }
        });
    }

    public static String text(JsonNode value, String name, int max) {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.textValue().isBlank()
                || field.textValue().length() > max) {
            throw RunException.invalid(name + " must be nonblank text of at most " + max + " characters");
        }
        return field.textValue().trim();
    }

    public static UUID uuid(JsonNode value, String name) {
        String text = text(value, name, 36);
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException e) { throw RunException.invalid(name + " must be a UUID"); }
    }

    public static void limit(JsonNode value, int maxBytes) {
        if (value == null || value.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new RunException(413, "PAYLOAD_TOO_LARGE", "JSON payload exceeds its byte limit");
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static boolean matches(String expected, String supplied) {
        return expected != null && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
