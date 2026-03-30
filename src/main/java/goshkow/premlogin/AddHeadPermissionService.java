package goshkow.premlogin;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AddHeadPermissionService {

    private final PremiumLoginPlugin plugin;
    private final Map<UUID, PermissionAttachment> attachments = new ConcurrentHashMap<>();

    AddHeadPermissionService(PremiumLoginPlugin plugin) {
        this.plugin = plugin;
    }

    void apply(Player player) {
        if (!plugin.getConfig().getBoolean("integrations.addhead-premium.enabled", true)) {
            return;
        }

        String permission = plugin.getConfig().getString("integrations.addhead-premium.permission", "addhead.premium");
        PermissionAttachment oldAttachment = attachments.remove(player.getUniqueId());
        if (oldAttachment != null) {
            player.removeAttachment(oldAttachment);
        }

        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission(permission, true);
        attachments.put(player.getUniqueId(), attachment);
    }

    void clear(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) {
            player.removeAttachment(attachment);
        }
    }
}
