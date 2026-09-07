package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /skatepark} — the pieces you build a park out of.
 *
 * <p>Every one is an ordinary placeable RP Engine model in this addon's
 * content folder: right-click the ground to put it down, punch it to take it
 * back. They hold a vehicle up on their own cubes, so a board rolls up a
 * ramp's steps and grinds along the top of a box; that is a property of the
 * pieces (`vehicle-collision`, on by default) and not of this command.
 *
 * <p><strong>The list is read off the engine, not held here.</strong> Every
 * item in this addon's namespace whose id starts with {@code park_} is a
 * piece, so importing a new one with {@code scripts/import-park.py} puts it
 * in the command, the tab completion and the count with nothing to edit — and
 * a server owner who deletes a piece's YAML gets a command that agrees with
 * their content folder rather than one that promises something missing.
 */
final class Skatepark {

    /** What marks an item in this addon as a park piece. */
    static final String PREFIX = "park_";

    private final RPEnginePlugin engine;
    private final Supplier<Messages> messages;

    Skatepark(RPEnginePlugin engine, Supplier<Messages> messages) {
        this.engine = engine;
        this.messages = messages;
    }

    /** Every piece, in the order the pack lists them. */
    List<ContentId> pieces() {
        List<ContentId> out = new ArrayList<>();
        for (ContentId id : engine.items().ids()) {
            if (id.namespace().equals(SkateboardsPlugin.NAMESPACE) && id.path().startsWith(PREFIX)) {
                out.add(id);
            }
        }
        return out;
    }

    /** The short name a player types: the id without the prefix. */
    static String shortName(ContentId id) {
        return id.path().substring(PREFIX.length());
    }

    boolean command(CommandSender sender, String[] args) {
        Messages say = messages.get();
        if (!(sender instanceof Player)) {
            sender.sendMessage(say.get("players-only"));
            return true;
        }
        Player player = (Player) sender;
        List<ContentId> pieces = pieces();
        if (pieces.isEmpty()) {
            player.sendMessage(say.get("park-none"));
            return true;
        }
        if (args.length == 0) {
            StringBuilder list = new StringBuilder();
            for (ContentId id : pieces) {
                if (list.length() > 0) {
                    list.append(", ");
                }
                list.append(shortName(id));
            }
            player.sendMessage(say.get("park-list",
                    "count", String.valueOf(pieces.size()), "pieces", list.toString()));
            return true;
        }
        String wanted = args[0].toLowerCase(Locale.ROOT);
        if (wanted.equals("all")) {
            int given = 0;
            for (ContentId id : pieces) {
                if (give(player, id)) {
                    given++;
                }
            }
            player.sendMessage(say.get("park-all", "count", String.valueOf(given)));
            return true;
        }
        for (ContentId id : pieces) {
            if (shortName(id).equals(wanted) || id.path().equals(wanted)) {
                player.sendMessage(give(player, id)
                        ? say.get("park-given", "piece", shortName(id))
                        : say.get("no-item"));
                return true;
            }
        }
        player.sendMessage(say.get("park-unknown", "piece", args[0]));
        return true;
    }

    /** Tab completion: the piece names, and {@code all}. */
    List<String> complete(String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("all".startsWith(typed)) {
            out.add("all");
        }
        for (ContentId id : pieces()) {
            if (shortName(id).startsWith(typed)) {
                out.add(shortName(id));
            }
        }
        return out;
    }

    /** One of the piece in the player's inventory, or on the floor if it is full. */
    private boolean give(Player player, ContentId id) {
        Optional<ItemStack> item = engine.items().create(id);
        if (item.isEmpty()) {
            return false;
        }
        for (ItemStack overflow : player.getInventory().addItem(item.get()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        return true;
    }
}
