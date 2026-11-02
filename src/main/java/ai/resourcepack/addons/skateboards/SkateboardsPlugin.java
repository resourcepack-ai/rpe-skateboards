package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.AddonContent;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
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

    private RPEnginePlugin engine;
    private SkateboardMechanics mechanics;
    private Stance stance;
    private Messages messages;
    private Skatepark park;

    /** This plugin's SpigotMC resource id: the number in its page's URL. */
    private static final int SPIGOT_RESOURCE = 139432;

    private ai.resourcepack.common.update.UpdateCheck updates;

    private VehicleGuard guard;

    @Override
    public void onLoad() {
        // Asked before the worlds load, so the answer is back for the startup banner.
        ai.resourcepack.common.update.UpdateCheck.early(this, SPIGOT_RESOURCE);
    }

    @Override
    public void onEnable() {
        if (updates != null) updates.stop();
        updates = ai.resourcepack.common.update.UpdateCheck.spigot(this, SPIGOT_RESOURCE)
                .name("Skateboards")
                .permission("skateboards.update")
                .enabledWhen(() -> getConfig().getBoolean("update-check", true))
                .player(this::tellUpdate);
        ai.resourcepack.common.banner.StartupBanner banner = ai.resourcepack.common.banner.StartupBanner.begin(this)
                .name("Skateboards")
                .addonOf("RP Engine")
                .tagline("Skateboards with real tricks.")
                .docs("https://resourcepack.ai/docs/rp-engine/skateboards")
                .updates(updates);
        Plugin found = getServer().getPluginManager().getPlugin("RPEngine");
        if (!(found instanceof RPEnginePlugin)) {
            getLogger().severe("RP Engine is not here, and a skateboard is nothing without it. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        engine = (RPEnginePlugin) found;

        boolean changed;
        try {
            changed = AddonContent.install(this, engine, NAMESPACE);
        } catch (Exception e) {
            getLogger().severe("Could not install the skateboard content: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (changed) {
            // The engine built its packs before we enabled. One reload folds
            // ours in; on every later start the files are already there and
            // the engine read them itself.
            banner.ok("Content", "Installed into RP Engine, which reloaded once");
            engine.reload();
        }

        saveDefaultConfig();
        messages = new Messages(getConfig());
        stance = new Stance(this);
        getServer().getPluginManager().registerEvents(new JoinGift(this, engine), this);
        park = new Skatepark(engine, this::messages);
        guard = new VehicleGuard(this, engine, BOARD, "skateboards", this::messages);
        getServer().getPluginManager().registerEvents(guard, this);
        mechanics = new SkateboardMechanics(this, engine, stance, this::messages,
                () -> getConfig().getBoolean("bail", true),
                () -> getConfig().getBoolean("trick-messages", true), guard);
        getServer().getPluginManager().registerEvents(mechanics, this);
        getServer().getScheduler().runTaskTimer(this, mechanics::tick, 1L, 1L);
        banner.ok("Commands", "/skateboard for a board, /skatepark for somewhere to ride");
        updates.start();
        banner.print();
    }

    /** The join nudge: the sentence, then the link as one clickable piece. */
    private void tellUpdate(Player player, ai.resourcepack.common.update.UpdateCheck.Update update) {
        net.md_5.bungee.api.chat.TextComponent line = new net.md_5.bungee.api.chat.TextComponent(
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(messages.get("update",
                        "latest", update.latest(), "running", update.running())));
        net.md_5.bungee.api.chat.TextComponent link = new net.md_5.bungee.api.chat.TextComponent(
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(messages.plain("update-link")));
        link.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                net.md_5.bungee.api.chat.ClickEvent.Action.OPEN_URL, update.url()));
        link.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.hover.content.Text(update.url())));
        line.addExtra(link);
        player.spigot().sendMessage(line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return command.getName().equalsIgnoreCase("skatepark") ? park.complete(args) : completeBoard(sender, args);
    }

    /**
     * The command's own words, and only the ones this sender may use. Null
     * would have Bukkit offer the names of online players, none of which
     * mean anything here.
     */
    private List<String> completeBoard(CommandSender sender, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("stance")) {
            return StringUtil.copyPartialMatches(args[1], List.of("left", "right"), new ArrayList<>());
        }
        if (args.length != 1) {
            return List.of();
        }
        List<String> words = new ArrayList<>(List.of("controls", "stance"));
        if (sender.hasPermission("skateboards.here")) {
            words.add("here");
        }
        if (sender.hasPermission("skateboards.reload")) {
            words.add("reload");
        }
        return StringUtil.copyPartialMatches(args[0], words, new ArrayList<>());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skatepark")) {
            return park.command(sender, args);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")
                && sender.hasPermission("skateboards.reload")) {
            reloadConfig();
            messages = new Messages(getConfig());
            sender.sendMessage(messages.get("reloaded"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("controls")) {
            sender.sendMessage(messages.get("controls"));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.get("players-only"));
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
                player.sendMessage(messages.get("stance-current",
                        "stance", stance.isGoofy(player) ? "right" : "left"));
                return true;
            }
            stance.set(player, right);
            mechanics.restance(player);
            player.sendMessage(messages.get(
                    engine.vehicles().isRiding(player.getUniqueId()) ? "stance-set-riding" : "stance-set",
                    "stance", right ? "right" : "left"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("here")) {
            // A board out of nothing, so staff only, and still asked of the
            // same protection the item is - see VehicleGuard.
            if (!player.hasPermission("skateboards.here")) {
                player.sendMessage(messages.get("here-denied"));
                return true;
            }
            Location ahead = player.getLocation().clone();
            Vector forward = ahead.getDirection().setY(0);
            if (forward.lengthSquared() > 1e-6) {
                ahead.add(forward.normalize().multiply(1.5));
            }
            ahead.setX(Math.floor(ahead.getX()) + 0.5);
            ahead.setZ(Math.floor(ahead.getZ()) + 0.5);
            ahead.setY(Math.floor(ahead.getY()));
            String refused = guard.refuseSpawn(player, ahead);
            if (refused != null) {
                player.sendMessage(refused);
                return true;
            }
            Optional<Vehicle> board = engine.vehicles().spawn(ahead, BOARD);
            board.ifPresent(placed -> guard.spawned(player, placed));
            player.sendMessage(messages.get(board.isPresent() ? "placed" : "place-failed"));
            return true;
        }
        if (args.length > 0) {
            // Anything else - a typo, or reload without the permission - is
            // not a request for another one, so it gets the usage instead.
            player.sendMessage(messages.get("usage"));
            return true;
        }
        Optional<ItemStack> item = engine.items().create(DECK);
        if (item.isEmpty()) {
            player.sendMessage(messages.get("no-item"));
            return true;
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(item.get());
        for (ItemStack overflow : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        player.sendMessage(messages.get("given"));
        return true;
    }

    /** Every string the plugin says, as the config has them right now. */
    Messages messages() {
        return messages;
    }
}
