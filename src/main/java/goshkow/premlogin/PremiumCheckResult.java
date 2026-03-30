package goshkow.premlogin;

record PremiumCheckResult(boolean premium, boolean secure, String reason) {

    static PremiumCheckResult secure(String reason) {
        return new PremiumCheckResult(true, true, reason);
    }

    static PremiumCheckResult insecure(String reason) {
        return new PremiumCheckResult(true, false, reason);
    }

    static PremiumCheckResult notPremium(String reason) {
        return new PremiumCheckResult(false, false, reason);
    }
}
