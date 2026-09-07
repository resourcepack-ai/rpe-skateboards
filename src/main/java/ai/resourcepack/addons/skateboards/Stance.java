package ai.resourcepack.addons.skateboards;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Which way a player rides: regular (left foot forward, chest to the
 * board's right) or goofy (the other way). A fact about the person, kept on
 * the player rather than on any board, so it follows them onto every board
 * they ever step on and survives a restart.
 */
final class Stance {

    private final NamespacedKey key;

    Stance(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "goofy");
    }

    boolean isGoofy(Player player) {
        Byte flag = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return flag != null && flag != 0;
    }

    void set(Player player, boolean goofy) {
        if (goofy) {
            player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(key);
        }
    }
}
