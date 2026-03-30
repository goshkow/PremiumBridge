package goshkow.premlogin;

import java.util.UUID;

record MigrationPreview(String nickname, UUID offlineUuid, UUID premiumUuid, boolean hasOfflineData) {
}
