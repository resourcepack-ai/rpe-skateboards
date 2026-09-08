package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Gives a player a board when they join, if the server asked for that.
 *
 * <p><strong>Off unless asked for.</strong> A plugin that puts something in
 * somebody's inventory uninvited is one a server owner has to undo, and the
 * ones who do want it nearly always want it ONCE - a board on your first
 * visit, not a new board in your hotbar every evening. So there are two keys
 * rather than one, and both are in {@code config.yml}.
 *
 * <p>It respects {@code skateboards.use} as well, which means revoking that
 * permission is enough to stop the gift for a player or a whole group without
 * touching the config at all.
 */
final class JoinGift implements Listener {

    private final SkateboardsPlugin plugin;
    private final RPEnginePlugin engine;

    JoinGift(SkateboardsPlugin plugin, RPEnginePlugin engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("give-on-join", false)) {
            return;
        }
        if (plugin.getConfig().getBoolean("first-join-only", true)
                && event.getPlayer().hasPlayedBefore()) {
            return;
        }
        if (!event.getPlayer().hasPermission("skateboards.use")) {
            return;
        }
        Optional<ItemStack> board = engine.items().create(SkateboardsPlugin.DECK);
        if (board.isEmpty()) {
            // The engine has not built its packs yet, or the content is not
            // installed. Silently: a player joining is not the person to tell,
            // and the console already says when content fails to load.
            return;
        }
        // Anything that will not fit goes on the floor rather than nowhere,
        // the same way the command does it.
        for (ItemStack overflow : event.getPlayer().getInventory().addItem(board.get()).values()) {
            event.getPlayer().getWorld().dropItemNaturally(event.getPlayer().getLocation(), overflow);
        }
        event.getPlayer().sendMessage(plugin.messages().get("given"));
    }
}
