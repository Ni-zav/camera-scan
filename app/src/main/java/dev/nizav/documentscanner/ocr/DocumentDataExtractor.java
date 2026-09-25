package dev.nizav.documentscanner.ocr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentDataExtractor {
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATE = Pattern.compile(
            "\\b(?:[0-3]?\\d[./-][01]?\\d[./-](?:19|20)?\\d{2}|"
                    + "(?:19|20)\\d{2}[./-][01]?\\d[./-][0-3]?\\d)\\b"
    );
    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:Rp\\s?|IDR\\s?|USD\\s?|[$€£]\\s?)"
                    + "\\d[\\d.,]*(?:\\s?(?:rb|jt|k|m))?"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?62|0)[0-9() -]{7,15}(?!\\d)"
    );

    private DocumentDataExtractor() {
    }

    public static String extractJson(String text) {
        JSONObject root = new JSONObject();
        try {
            root.put("emails", matches(text, EMAIL));
            root.put("dates", matches(text, DATE));
            root.put("amounts", matches(text, MONEY));
            root.put("phones", matches(text, PHONE));
            root.put("characters", text == null ? 0 : text.length());
        } catch (Exception ignored) {
            return "{}";
        }
        return root.toString();
    }

    private static JSONArray matches(String text, Pattern pattern) {
        JSONArray result = new JSONArray();
        if (text == null || text.isEmpty()) {
            return result;
        }

        Set<String> unique = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find() && unique.size() < 50) {
            unique.add(matcher.group().trim());
        }

        for (String value : unique) {
            result.put(value);
        }
        return result;
    }
}
