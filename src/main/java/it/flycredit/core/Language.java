package it.flycredit.core;

import java.util.Locale;
import java.util.ResourceBundle;

public enum Language {
    ENGLISH(Locale.ROOT),
    ITALIAN(Locale.ITALIAN);

    private final ResourceBundle messages;

    Language(Locale locale) {
        messages = ResourceBundle.getBundle("lang.flycredit", locale, Language.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static Language forLocale(Locale locale) {
        return locale != null && "it".equals(locale.getLanguage()) ? ITALIAN : ENGLISH;
    }

    public String duration(long millis) {
        long minutes = millis / 60_000;
        long seconds = millis / 1000 % 60;
        String minuteText = minutes == 1 ? text("duration.minute") : text("duration.minutes", minutes);
        if (seconds == 0) return minuteText;
        String secondText = seconds == 1 ? text("duration.second") : text("duration.seconds", seconds);
        return minutes == 0 ? secondText : text("duration.both", minuteText, secondText);
    }

    public String text(String key, Object... arguments) {
        String template = messages.getString(key);
        return arguments.length == 0 ? template : String.format(Locale.ROOT, template, arguments);
    }
}
