package goshkow.premlogin.api;

public enum PremiumStatus {
    NOT_PREMIUM,
    PREMIUM_INSECURE,
    PREMIUM_SECURE;

    public boolean isPremium() {
        return this != NOT_PREMIUM;
    }

    public boolean isSecure() {
        return this == PREMIUM_SECURE;
    }
}
