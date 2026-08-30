package com.sparxilium.smartpluginassistant.service;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

public class I18n {
    private static final String DEFAULT_LANG = "zh-tw";
    private static final Map<String, String> currentMessages = new HashMap<>();
    private static final StringProperty currentLangProperty = new SimpleStringProperty(DEFAULT_LANG);

    static {
        loadLanguage(DEFAULT_LANG);
    }

    public static void loadLanguage(String langCode) {
        currentMessages.clear();
        String resourcePath = "/com/sparxilium/smartpluginassistant/lang/" + langCode + ".lang";
        try (InputStream is = I18n.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eqIdx = line.indexOf('=');
                        if (eqIdx > 0) {
                            String key = line.substring(0, eqIdx).trim();
                            String value = line.substring(eqIdx + 1).trim();
                            currentMessages.put(key, value);
                        }
                    }
                }
            } else {
                System.err.println("Language file not found: " + resourcePath);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        currentLangProperty.set(langCode);
    }

    public static String get(String key) {
        return currentMessages.getOrDefault(key, key);
    }

    public static String get(String key, Object... args) {
        String pattern = currentMessages.getOrDefault(key, key);
        try {
            return MessageFormat.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }

    public static String getCurrentLang() {
        return currentLangProperty.get();
    }

    public static StringProperty currentLangProperty() {
        return currentLangProperty;
    }
}
