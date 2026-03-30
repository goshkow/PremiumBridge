package goshkow.premlogin;

import java.util.Locale;

enum MigrationMode {
    DISABLED,
    AUTOMATIC,
    ASK_PLAYER;

    static MigrationMode fromConfigValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTOMATIC;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "disabled", "off", "false" -> DISABLED;
            case "ask-player", "ask", "choice", "prompt", "manual" -> ASK_PLAYER;
            default -> AUTOMATIC;
        };
    }
}
