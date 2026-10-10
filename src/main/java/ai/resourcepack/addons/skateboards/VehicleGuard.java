package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.event.ModelBreakEvent;
import ai.resourcepack.engine.api.event.ModelPlaceEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who may put one of this addon's vehicles down without an item, and who may
 * pick one up.
 *
 * <p>{@code Vehicles.spawn} asks nobody: it is a plugin putting a vehicle
 * down, and the engine's docs say a plugin that wants the item's refusal asks
 * {@link ModelPlaceEvent} itself. That event is the engine's protection door -
 * its WorldGuard flag listens to it, and so does anything else a server uses
 * to keep models out of a lobby - so every spawn here asks it first, the same
 * question the engine asks when the item is placed. On top of that, a command
 * that produces a vehicle from nothing gets a cooldown and a cap on how many a
 * player may have standing at once.
 *
 * <p>Picking one up is the other half. It has to run ahead of the engine's own
 * click handler (which would seat the player instead, at LOW, and cancel the
 * click), so it cannot simply wait for protection plugins to vote on the
 * click itself. Instead it asks them: {@link ModelBreakEvent}, the engine's
 * door for taking a model away, and a plain {@link PlayerInteractEntityEvent}
 * on the entity that was clicked, which is what region and claim plugins
 * already judge. Either refusing is a refusal. And a vehicle remembers who put
 * it down, in its own {@link Vehicle#data()}, so only that player (or one with
 * {@code <addon>.pickup.others}) may take it; one with no owner on record -
 * parked before this existed - is anybody's, as it always was.
 */
final class VehicleGuard implements Listener {

    private final JavaPlugin plugin;
    private final RPEnginePlugin engine;
    private final ContentId vehicle;
    /** The permission prefix, which is also the config's word for this addon. */
    private final String name;
    private final Supplier<Messages> messages;
    private final NamespacedKey ownerKey;
    /** When each player last put one down by command, in millis. */
    private final Map<UUID, Long> lastSpawn = new HashMap<>();
    /** The {@link ModelPlaceEvent} this guard is firing itself, so the tagger below ignores it. */
    private ModelPlaceEvent asking;

    VehicleGuard(JavaPlugin plugin, RPEnginePlugin engine, ContentId vehicle, String name,
                 Supplier<Messages> messages) {
        this.plugin = plugin;
        this.engine = engine;
        this.vehicle = vehicle;
        this.name = name;
        this.messages = messages;
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    // ---- putting one down ---------------------------------------------------

    /**
     * Why this player may not put one down by command at {@code where} right
     * now, already worded, or null if they may. Cooldown and cap first (cheap,
     * and they are the plugin's own rule), then the protection plugins.
     * {@link #spawned} records a success.
     */
    String refuseSpawn(Player player, Location where) {
        if (!player.hasPermission(name + ".here.unlimited")) {
            long cooldown = Math.max(0, plugin.getConfig().getLong("spawn-limits.cooldown-seconds", 10)) * 1000L;
            Long last = lastSpawn.get(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < cooldown) {
                long left = (cooldown - (now - last) + 999) / 1000;
                return messages.get().get("spawn-cooldown", "seconds", String.valueOf(left));
            }
            int cap = plugin.getConfig().getInt("spawn-limits.max-per-player", 2);
            if (cap >= 0) {
                int out = standing(player.getUniqueId());
                if (out >= cap) {
                    return messages.get().get("spawn-limit", "count", String.valueOf(out));
                }
            }
        }
        if (!mayPlace(player, where.getBlock())) {
            return messages.get().get("place-denied");
        }
        return null;
    }

    /** Asks the engine's placement question, as placing the item would. */
    boolean mayPlace(Player player, Block block) {
        ModelPlaceEvent ask = new ModelPlaceEvent(player, vehicle, block);
        asking = ask;
        try {
            plugin.getServer().getPluginManager().callEvent(ask);
        } finally {
            asking = null;
        }
        return !ask.isCancelled();
    }

    /** A spawn this guard allowed went through: start the cooldown and remember whose it is. */
    void spawned(Player player, Vehicle placed) {
        lastSpawn.put(player.getUniqueId(), System.currentTimeMillis());
        claim(placed, player);
    }

    /** How many of this addon's vehicles this player has standing in loaded chunks. */
    private int standing(UUID player) {
        int count = 0;
        for (Vehicle each : engine.vehicles().loaded()) {
            if (each.isValid() && each.id().equals(vehicle)
                    && owner(each).filter(player::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    // ---- whose it is --------------------------------------------------------

    void claim(Vehicle placed, Player owner) {
        placed.data().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    Optional<UUID> owner(Vehicle placed) {
        String raw = placed.data().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The engine parks a vehicle from its own item after asking
     * {@link ModelPlaceEvent} and says nothing about who it parked it for. So
     * note which of ours were already standing there, and the next tick the
     * new one is the placer's.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(ModelPlaceEvent event) {
        if (event == asking || !event.model().equals(vehicle)) {
            return;
        }
        Player player = event.getPlayer();
        Location where = event.block().getLocation().add(0.5, 0, 0.5);
        Set<UUID> before = new HashSet<>();
        for (Vehicle each : engine.vehicles().near(where, 1.5)) {
            before.add(each.uniqueId());
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Vehicle each : engine.vehicles().near(where, 1.5)) {
                if (each.id().equals(vehicle) && !before.contains(each.uniqueId())
                        && owner(each).isEmpty()) {
                    claim(each, player);
                    return;
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSpawn.remove(event.getPlayer().getUniqueId());
    }

    // ---- picking one up -----------------------------------------------------

    /**
     * Why this player may not pick this vehicle up, already worded, or null if
     * they may. {@code clicked} is the entity they right-clicked.
     */
    String refusePickUp(Player player, Vehicle placed, Entity clicked) {
        Optional<UUID> owner = owner(placed);
        if (owner.isPresent() && !owner.get().equals(player.getUniqueId())
                && !player.hasPermission(name + ".pickup.others")) {
            return messages.get().get("pickup-not-yours");
        }
        Location at = placed.location();
        ModelBreakEvent breaking = new ModelBreakEvent(vehicle,
                at != null ? at : clicked.getLocation(), player, true);
        plugin.getServer().getPluginManager().callEvent(breaking);
        if (breaking.isCancelled()) {
            return messages.get().get("pickup-denied");
        }
        PlayerInteractEntityEvent probe = new PlayerInteractEntityEvent(player, clicked, EquipmentSlot.HAND);
        plugin.getServer().getPluginManager().callEvent(probe);
        if (probe.isCancelled()) {
            return messages.get().get("pickup-denied");
        }
        return null;
    }
}
