package com.droidenx.clouseau.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/** Finds JSON in a log message, for the JSON formatter and highlighter alike. */
final class JsonDetection {

    private JsonDetection() {}

    private static final com.google.gson.TypeAdapter<JsonElement> ELEMENTS = new Gson().getAdapter(JsonElement.class);

    /**
     * Finds the first index where a JSON object or array begins that runs to the end of the
     * message. Each '{' and '[' is tried in order. Parsing is strict, as JSON is written:
     * Gson's default lenient parser would take "[production, us-east-1]" for an array of
     * strings and show quotes the log never had. An empty {@code []} or {@code {}} doesn't
     * count: "args=[]" has nothing to format, and the toggle would only split the line.
     */
    static int findJsonStart(String input) {
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '{' || c == '[') && isNonEmptyJson(input.substring(i))) return i;
        }
        return -1;
    }

    /** Whether {@code text} is one non-empty JSON array or object, written strictly, and nothing after it. */
    private static boolean isNonEmptyJson(String text) {
        try (com.google.gson.stream.JsonReader reader =
                     new com.google.gson.stream.JsonReader(new java.io.StringReader(text))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            JsonElement value = ELEMENTS.read(reader);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) return false;
            return value.isJsonArray() ? !value.getAsJsonArray().isEmpty()
                 : !value.isJsonObject() || !value.getAsJsonObject().isEmpty();
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }
}
