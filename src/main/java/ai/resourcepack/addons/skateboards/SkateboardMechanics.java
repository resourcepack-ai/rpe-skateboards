package ai.resourcepack.addons.skateboards;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleInput;
import ai.resourcepack.engine.api.VehicleState;
import ai.resourcepack.engine.api.event.VehicleExitEvent;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * How a skateboard is ridden, on top of how the engine drives it.
 *
 * <p>The engine's physics is a car's: a throttle that accelerates, a brake,
 * grip, a jump. A board is not driven like that, and the vehicle definition
 * gives it almost no throttle of its own ({@code acceleration: 0.6}) so that
 * everything below is what moves it:
 *
 * <ul>
 *   <li><b>Pushing.</b> Holding W on the ground kicks the board along every
 *   {@link #PUSH_INTERVAL} ticks — a nudge of {@link #PUSH_BOOST} and the
 *   push emote for the length of the kick — up to a cruising speed the legs
 *   cannot beat. That is the rhythm of skating: kick, roll, kick.</li>
 *   <li><b>Pushing backwards.</b> S is the same kick with the other foot: the
 *   back foot holds the tail and the FRONT one reaches down and sweeps toward
 *   the nose, which is how a board is walked backwards. It only starts once
 *   the board has all but stopped, so holding S while rolling forward is
 *   still a brake, and it tops out low — nobody pushes fast backwards.</li>
 *   <li><b>The coffin.</b> Hold shift while rolling and the rider lies flat
 *   on their back along the board, feet first, hands folded on their chest.
 *   Shift is Minecraft's dismount, so this only works because the engine can
 *   be asked to keep a rider in their seat while the vehicle is moving
 *   ({@link Vehicle#holdOccupant}) - and because that hold lets go the moment
 *   the board stops, which is also how you get off.</li>
 *   <li><b>Tucking.</b> Holding sprint crouches the rider and lifts the speed
 *   cap from cruising to the board's top, picking up speed slowly while held.
 *   "Bending down to go faster" is the mechanic the owner asked for by name,
 *   and it is the whole reason the sprint key reaches a plugin at all.</li>
 *   <li><b>Rolling.</b> A board coasts a long way, and the engine's road
 *   vehicle does not (its coast floor is a car rolling to a halt). While
 *   nobody is pushing or braking, most of that drag is handed back.</li>
 *   <li><b>Spinning.</b> A or D in the air flicks the board round under the
 *   rider — one flick per flight, carried by the engine's mid-air spin.</li>
 *   <li><b>Bailing.</b> Land with the board sideways to where it is going
 *   and the rider comes off. Straight is fine and so is backwards (a fakie);
 *   it is the 90 that puts you on the ground.</li>
 * </ul>
 *
 * <p>Per-board state is kept here by chassis id and dropped when the rider
 * gets out; nothing is written to the vehicle.
 */
final class SkateboardMechanics implements Listener {

    /** Fastest the legs can push it, blocks per second. Above this only a tuck adds speed. */
    static final double PUSH_CAP = 8.0;
    /** One kick's worth of speed. */
    static final double PUSH_BOOST = 2.4;
    /** Ticks between kicks while W is held. */
    static final int PUSH_INTERVAL = 14;
    /**
     * How long the push emote is worn per kick, ticks. The SAME as
     * {@link #PUSH_INTERVAL} and the emote's own length (0.7 s), so one kick
     * plays exactly one cycle; worn shorter, the emote was cut mid-stride on
     * every kick and the rider jittered.
     */
    static final int PUSH_EMOTE_TICKS = 14;
    /** One backwards kick's worth of speed. Less than a forward one: it is an awkward push. */
    static final double PUSH_BACK_BOOST = 1.4;
    /** Fastest the front foot will walk it backwards, blocks per second. */
    static final double PUSH_BACK_CAP = 4.0;
    /**
     * How slowly the board has to be rolling FORWARD before S starts kicking
     * back rather than braking. The engine's own reverse threshold, so the
     * kick starts exactly where its brake gives up.
     */
    static final double PUSH_BACK_FROM = 0.5;
    /** Speed cap while standing up. The definition's own top speed is the tucked one. */
    static final double CRUISE_LIMIT = 9.0;
    /** Speed picked up per tick while tucked and moving, blocks per second. */
    static final double TUCK_GAIN = 0.05;
    /** Below this a tuck is just a crouch. */
    static final double TUCK_MIN_SPEED = 2.0;
    /** Drag handed back per tick while rolling free — most of the engine's coast floor. */
    static final double ROLL_ASSIST = 0.15;
    /** The flick, degrees per second added to the spin. */
    static final double SPIN = 320;
    /** A landing this far off the direction of travel, either way, is a bail. */
    static final double BAIL_FROM = 50;
    static final double BAIL_TO = 130;
    /** Below this speed a sideways landing is a stumble, not a fall. */
    static final double BAIL_MIN_SPEED = 3.0;

    static final String EMOTE_PUSH = "skateboards_push";
    static final String EMOTE_PUSH_BACK = "skateboards_pushback";
    static final String EMOTE_TUCK = "skateboards_tuck";
    static final String EMOTE_COFFIN = "skateboards_coffin";

    /**
     * The seat is drawn for a regular rider (yaw 90: chest to the board's
     * right, left foot forward). A goofy rider faces the other way and wears
     * the mirrored copies of every emote, which the content ships as
     * {@code <name>_goofy}. See Stance.
     */
    static final double REGULAR_YAW = 90;
    static final double GOOFY_YAW = 270;

    /**
     * Where a rider faces while tucked: straight down the board, whichever
     * stance they ride.
     *
     * <p><strong>The tuck is the one pose the rider turns for, and the turn is
     * what makes it possible at all.</strong> A downhill tuck is a fold
     * FORWARD - chest over the thighs, back flat, head up looking down the
     * road - and a skater stands across the board, so down the road is
     * sideways to them. A Minecraft rig cannot fold sideways: its knees hinge
     * one way, and its root turns the whole figure about the hip, so the angle
     * that leans the legs toward the nose leans the body toward the tail.
     * Three goes at authoring around that produced a rider folded into a ball
     * on the deck. Turning them to face the nose makes the tuck an ordinary
     * forward fold, which the rig does perfectly - and squaring your shoulders
     * down the road is what a tucking skater does anyway. The price is that
     * both feet come round with them.
     */
    static final double TUCK_YAW = 0;

    /** Where a rider faces lying down, for the same reason as the tuck: down the board. */
    static final double COFFIN_YAW = 0;

    /**
     * How fast the board has to be rolling for shift to lie the rider down
     * rather than put them off.
     *
     * <p>Above the engine's own release speed, and deliberately not by much:
     * the hold that keeps a rider on the board stops holding below that, so a
     * coffin that started any slower would be a rider lying down for a tick
     * and then standing in the road.
     */
    static final double COFFIN_MIN_SPEED = 2.0;

    private final Plugin plugin;
    private final RPEnginePlugin engine;
    private final Map<UUID, Ride> rides = new HashMap<>();
    /**
     * Read fresh every time rather than held, so {@code /skateboard reload}
     * reaches a rider who is already on a board.
     */
    private final Supplier<Messages> messages;
    private final BooleanSupplier bailing;

    SkateboardMechanics(Plugin plugin, RPEnginePlugin engine, Stance stance,
                        Supplier<Messages> messages, BooleanSupplier bailing) {
        this.plugin = plugin;
        this.engine = engine;
        this.stance = stance;
        this.messages = messages;
        this.bailing = bailing;
    }

    /** A rider changed stance mid-ride: turn them and re-dress them now. */
    void restance(Player rider) {
        engine.vehicles().of(rider).ifPresent(board -> {
            Ride ride = rides.get(board.uniqueId());
            if (ride != null) {
                ride.known = false;
                ride.facing = null;
                ride.worn = null;
            }
        });
    }

    /** What one board is up to. */
    private static final class Ride {
        int pushCooldown;
        int pushEmoteLeft;
        boolean tucking;
        boolean coffin;
        boolean wasAirborne;
        boolean flicked;
        boolean limited;
        Location last;
        boolean goofy;
        /** Whether this ride has read the rider's stance yet. */
        boolean known;
        /** The seat yaw the rider is currently turned to, or null for none yet. */
        Double facing;
        /** What the rider is currently dressed in, so a change is one call. */
        String worn;
    }

    private final Stance stance;

    /** The emote a rider wears for what the board is doing, in their stance. */
    private static String stateEmote(Vehicle board, boolean goofy) {
        String base;
        if (board.is(VehicleState.AIRBORNE)) {
            base = "skateboards_airborne";
        } else if (board.is(VehicleState.TURNING)) {
            base = "skateboards_turning";
        } else if (board.is(VehicleState.REVERSING)) {
            base = "skateboards_reversing";
        } else if (board.is(VehicleState.MOVING)) {
            base = "skateboards_moving";
        } else {
            base = "skateboards_idle";
        }
        return goofy ? base + "_goofy" : base;
    }

    /** Dresses the rider in {@code emote}, in their stance, only when it changes. */
    private static void wear(Vehicle board, Player rider, Ride ride, String emote) {
        String wanted = ride.goofy && !emote.endsWith("_goofy") ? emote + "_goofy" : emote;
        if (wanted.equals(ride.worn)) {
            return;
        }
        ride.worn = wanted;
        board.dress(rider, wanted);
    }

    void tick() {
        for (Vehicle board : engine.vehicles().loaded()) {
            if (!board.id().equals(SkateboardsPlugin.BOARD)) {
                continue;
            }
            Optional<Player> driver = board.driver();
            if (driver.isEmpty()) {
                rides.remove(board.uniqueId());
                continue;
            }
            ride(board, driver.get(), rides.computeIfAbsent(board.uniqueId(), id -> new Ride()));
        }
    }

    private void ride(Vehicle board, Player rider, Ride ride) {
        if (!ride.known) {
            ride.goofy = stance.isGoofy(rider);
            ride.known = true;
            // Asked for ONCE, at the start of the ride, and not when the key
            // is seen: the dismount happens on the tick shift goes down, and
            // a hold applied after reading the key would be applied to
            // somebody already standing in the road. The engine lets go by
            // itself the moment the board stops, which is what makes "stop,
            // then step off" the whole rule.
            board.holdOccupant(rider, true);
        }
        VehicleInput input = board.input();
        double speed = board.groundSpeed();
        boolean airborne = board.is(VehicleState.AIRBORNE);
        Location now = board.location();

        if (airborne) {
            inTheAir(board, input, ride);
        } else {
            if (ride.wasAirborne) {
                landed(board, rider, ride, now);
            }
            onTheGround(board, rider, input, speed, ride);
        }

        ride.wasAirborne = airborne;
        ride.last = now;

        // Which way the rider stands on the board: their stance, except lying
        // down or tucked, when they square up down the board. See TUCK_YAW.
        face(board, rider, ride, ride.coffin ? COFFIN_YAW
                : ride.tucking ? TUCK_YAW
                : ride.goofy ? GOOFY_YAW : REGULAR_YAW);

        // Whatever the board is doing, unless a push or a tuck is in progress:
        // the rider is dressed from here rather than from the seat's table,
        // because the seat's table cannot know their stance.
        if (!ride.tucking && !ride.coffin && ride.pushEmoteLeft == 0) {
            wear(board, rider, ride, stateEmote(board, ride.goofy));
        }
    }

    /** Turns the rider to {@code yaw}, only when it changes. */
    private static void face(Vehicle board, Player rider, Ride ride, double yaw) {
        if (ride.facing != null && ride.facing == yaw) {
            return;
        }
        ride.facing = yaw;
        board.turnOccupant(rider, yaw);
    }

    private void onTheGround(Vehicle board, Player rider, VehicleInput input, double speed, Ride ride) {
        if (ride.pushCooldown > 0) {
            ride.pushCooldown--;
        }
        if (ride.pushEmoteLeft > 0) {
            ride.pushEmoteLeft--;
        }

        // The coffin: shift held while rolling. It beats everything else on
        // the ground - a rider lying on their back is not pushing, tucking or
        // steering with their feet - and it lets the board roll on, because
        // the whole point of it is that you are not doing anything.
        ride.coffin = input.sneak() && speed > COFFIN_MIN_SPEED;
        if (ride.coffin) {
            ride.tucking = false;
            limit(board, ride, true);
            wear(board, rider, ride, EMOTE_COFFIN);
            board.nudge(ROLL_ASSIST);
            return;
        }

        // Tuck: sprint held while rolling. The cap comes off, the rider
        // crouches, and the board creeps toward its real top speed.
        boolean tuck = input.sprint() && speed > TUCK_MIN_SPEED && !input.backward();
        ride.tucking = tuck;
        limit(board, ride, !tuck);
        if (tuck) {
            wear(board, rider, ride, EMOTE_TUCK);
            board.nudge(TUCK_GAIN);
            return;
        }

        // Push backwards: S, once the brake has done its work. groundSpeed()
        // has no sign — a drift is still moving — so the state is what says
        // which way along the board it is going.
        if (input.backward()) {
            double along = board.is(VehicleState.REVERSING) ? -speed : speed;
            if (along < PUSH_BACK_FROM && along > -PUSH_BACK_CAP && ride.pushCooldown == 0) {
                board.nudge(-Math.min(PUSH_BACK_BOOST, PUSH_BACK_CAP + 0.5 + along));
                wear(board, rider, ride, EMOTE_PUSH_BACK);
                ride.pushCooldown = PUSH_INTERVAL;
                ride.pushEmoteLeft = PUSH_EMOTE_TICKS;
                rider.getWorld().playSound(board.location(), Sound.BLOCK_STONE_STEP, 0.4f, 0.8f);
            }
            return;
        }

        // Push: W on the ground, below the speed the legs can reach.
        if (input.forward() && speed < PUSH_CAP) {
            if (ride.pushCooldown == 0) {
                board.nudge(Math.min(PUSH_BOOST, PUSH_CAP + 0.5 - speed));
                wear(board, rider, ride, EMOTE_PUSH);
                ride.pushCooldown = PUSH_INTERVAL;
                ride.pushEmoteLeft = PUSH_EMOTE_TICKS;
                rider.getWorld().playSound(board.location(), Sound.BLOCK_STONE_STEP, 0.4f, 0.8f);
            }
            return;
        }

        // Rolling free: give back most of the engine's coast, so a board
        // rolls like a board and not like a car with its engine off.
        if (!input.forward() && !input.backward() && !input.jump() && speed > 0.5) {
            board.nudge(ROLL_ASSIST);
        }
    }

    private void inTheAir(Vehicle board, VehicleInput input, Ride ride) {
        if (!ride.wasAirborne) {
            ride.flicked = false;
            ride.coffin = false;
            // The airborne emote is the tuck-and-grab; a push or a tuck in
            // progress gives way to it.
            ride.pushEmoteLeft = 0;
            ride.tucking = false;
        }
        if (!ride.flicked && (input.left() || input.right())) {
            board.spin(input.right() ? SPIN : -SPIN);
            ride.flicked = true;
        }
    }

    private void landed(Vehicle board, Player rider, Ride ride, Location now) {
        if (ride.last == null || ride.last.getWorld() != now.getWorld()) {
            return;
        }
        double dx = now.getX() - ride.last.getX();
        double dz = now.getZ() - ride.last.getZ();
        double travelled = Math.hypot(dx, dz) * 20;
        if (travelled < BAIL_MIN_SPEED) {
            return;
        }
        // Minecraft's yaw: 0 faces +z and increases clockwise, so the
        // direction of travel is atan2(-dx, dz).
        double travelYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double off = Math.abs(wrap180(travelYaw - board.heading()));
        if (off >= BAIL_FROM && off <= BAIL_TO) {
            // Turned off in config.yml, a sideways landing simply carries on.
            if (bailing.getAsBoolean()) {
                bail(board, rider);
            }
        } else if (ride.flicked) {
            rider.sendMessage(messages.get().get(off > BAIL_TO ? "fakie" : "landed"));
        }
    }

    private void bail(Vehicle board, Player rider) {
        board.eject(rider);
        board.stop();
        rider.damage(1.0);
        rider.sendMessage(messages.get().get("bailed"));
        rider.getWorld().playSound(rider.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.6f, 1.0f);
    }

    /** Sets or clears the standing-up speed cap, only when it changes. */
    private void limit(Vehicle board, Ride ride, boolean on) {
        if (ride.limited == on) {
            return;
        }
        ride.limited = on;
        board.setSpeedLimit(on ? CRUISE_LIMIT : 0);
    }

    /**
     * Sneak and right-click an empty board to pick it up. Ahead of the
     * engine's own click handler, which would otherwise seat you; the engine
     * listens with {@code ignoreCancelled}, so cancelling here is enough.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickUp(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()) {
            return;
        }
        Optional<Vehicle> found = engine.vehicles().at(event.getRightClicked());
        if (found.isEmpty() || !found.get().id().equals(SkateboardsPlugin.BOARD) || !found.get().isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Optional<ItemStack> item = engine.items().create(SkateboardsPlugin.DECK);
        if (item.isEmpty()) {
            return;
        }
        Vehicle board = found.get();
        rides.remove(board.uniqueId());
        if (board.remove()) {
            Player player = event.getPlayer();
            for (ItemStack overflow : player.getInventory().addItem(item.get()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
        }
    }

    @EventHandler
    public void onExit(VehicleExitEvent event) {
        Ride ride = rides.remove(event.vehicle().uniqueId());
        if (ride != null && ride.limited) {
            // Leave the board as the definition made it for the next rider.
            event.vehicle().setSpeedLimit(0);
        }
    }

    static double wrap180(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped < 0) {
            wrapped += 360;
        }
        return wrapped > 180 ? wrapped - 360 : wrapped;
    }
}
