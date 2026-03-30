package goshkow.premlogin.protocol;

import java.net.InetSocketAddress;
import java.util.UUID;

record PendingPremiumSession(
    String requestedUsername,
    InetSocketAddress address,
    byte[] verifyToken,
    UUID offlineUuid
) {
}
