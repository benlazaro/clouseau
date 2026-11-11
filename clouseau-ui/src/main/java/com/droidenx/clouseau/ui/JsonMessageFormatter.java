package com.droidenx.clouseau.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.droidenx.clouseau.api.LogFormatter;

/**
 * Built-in formatter that detects JSON embedded in a log message and pretty-prints it.
 * Handles messages where JSON follows a text prefix (e.g. "Some text: [id] {...}").
 */
final class JsonMessageFormatter implements LogFormatter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String getName() { return "JSON"; }

    @Override
    public boolean canFormat(String input) {
        if (input == null) return false;
        return findJsonStart(input) >= 0;
    }

    @Override
    public String format(String input) {
        if (input == null) return input;
        int jsonStart = findJsonStart(input);
        if (jsonStart < 0) return input;
        String prefix = input.substring(0, jsonStart);
        String jsonPart = input.substring(jsonStart);
        JsonElement element = JsonParser.parseString(jsonPart);
        String pretty = GSON.toJson(element);
        return prefix.isEmpty() ? pretty : prefix + "\n" + pretty;
    }

    private static int findJsonStart(String input) {
        return JsonDetection.findJsonStart(input);
    }
}
