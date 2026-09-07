package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Skateboards for RP Engine.
 *
 * <p>The engine already knows how to be a skateboard-shaped vehicle: a model
 * that rolls, a standing seat, a jump on space, and a rider dressed in an
 * animation per state. What it does not know is how a skateboard is
 * <em>ridden</em> — you push it, you tuck to go faster, you flick it round
 * under you in the air, and you fall off if you land it sideways. That is
 * this plugin, and it is built out of nothing but the public API:
 * {@link Vehicle#input()}, {@link Vehicle#nudge}, {@link Vehicle#spin},
 * {@link Vehicle#dress} and the vehicle events.
 *
 * <p>Everything the board <em>is</em> — its model, textures, the vehicle
 * definition, the item and every rider emote — is an ordinary RP Engine
 * content folder carried inside this jar and installed into the engine's
 * {@code content/skateboards/} on enable ({@link ContentInstaller}). A server
 * owner can open it, read it and change it.
 */
public final class SkateboardsPlugin extends JavaPlugin {

    /** The content namespace, which is also the folder installed into the engine. */
    static final String NAMESPACE = "skateboards";

    /** The vehicle. */
    static final ContentId BOARD = ContentId.parse(NAMESPACE + ":skateboard").orElseThrow();

    /** The item you carry it as - a different id, because ids are unique across a pack. */
    static final ContentId DECK = ContentId.parse(NAMESPACE + ":deck").orElseThrow();

    private static final String PREFIX = ChatColor.AQUA + "Skateboards " + ChatColor.GRAY;

    private RPEnginePlugin engine;
    private SkateboardMechanics mechanics;
    private Stance stance;

    @Override
    public void onEnable() {
        Plugin found = getServer().getPluginManager().getPlugin("RPEngine");
        if (!(found instanceof RPEnginePlugin)) {
            getLogger().severe("RP Engine is not here, and a skateboard is nothing without it. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        engine = (RPEnginePlugin) found;

        Path into = engine.getDataFolder().toPath().resolve("content").resolve(NAMESPACE);
        boolean changed;
        try {
            changed = ContentInstaller.install(getFile(), "content/" + NAMESPACE + "/", into,
                    getDescription().getVersion(), getLogger());
        } catch (Exception e) {
            getLogger().severe("Could not install the skateboard content into " + into + ": " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (changed) {
            // The engine built its packs before we enabled. One reload folds
            // ours in; on every later start the files are already there and
            // the engine read them itself.
            getLogger().info("Installed the skateboard content into the engine; reloading it once.");
            engine.reload();
        }

        stance = new Stance(this);
        mechanics = new SkateboardMechanics(this, engine, stance);
        getServer().getPluginManager().registerEvents(mechanics, this);
        getServer().getScheduler().runTaskTimer(this, mechanics::tick, 1L, 1L);
        getLogger().info("Skate or die. /skateboard for a board.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(PREFIX + "Only a player can hold a skateboard.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length > 0 && args[0].equalsIgnoreCase("stance")) {
            // Named by the foot that leads: left foot forward or right foot
            // forward. Skaters call those regular and goofy, and those words
            // are accepted too, but nobody should have to know them.
            String word = args.length > 1 ? args[1].toLowerCase() : "";
            boolean right = word.equals("right") || word.equals("goofy");
            boolean left = word.equals("left") || word.equals("regular");
            if (!left && !right) {
                player.sendMessage(PREFIX + "You ride " + ChatColor.WHITE + (stance.isGoofy(player) ? "right" : "left")
                        + " foot forward" + ChatColor.GRAY + ". /skateboard stance left|right to change.");
                return true;
            }
            stance.set(player, right);
            mechanics.restance(player);
            player.sendMessage(PREFIX + "You ride " + ChatColor.WHITE + (right ? "right" : "left") + " foot forward"
                    + ChatColor.GRAY + " now" + (engine.vehicles().isRiding(player.getUniqueId()) ? " - turning you round." : "."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("here")) {
            Location ahead = player.getLocation().clone();
            Vector forward = ahead.getDirection().setY(0);
            if (forward.lengthSquared() > 1e-6) {
                ahead.add(forward.normalize().multiply(1.5));
            }
            ahead.setX(Math.floor(ahead.getX()) + 0.5);
            ahead.setZ(Math.floor(ahead.getZ()) + 0.5);
            ahead.setY(Math.floor(ahead.getY()));
            Optional<Vehicle> board = engine.vehicles().spawn(ahead, BOARD);
            player.sendMessage(PREFIX + (board.isPresent()
                    ? "Board's down. Right-click it to step on."
                    : "Couldn't set a board down there."));
            return true;
        }
        Optional<ItemStack> item = engine.items().create(DECK);
        if (item.isEmpty()) {
            player.sendMessage(PREFIX + "The engine has no skateboard item yet - has it finished loading? Try /rp reload.");
            return true;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(item.get());
        for (ItemStack overflow : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        player.sendMessage(PREFIX + "Here's a board. Right-click the ground to set it down, "
                + "W to push, hold sprint to tuck, space to ollie, A/D in the air to spin it. "
                + "/skateboard stance right if you ride right foot forward.");
        return true;
    }

    static String prefix() {
        return PREFIX;
    }
}
