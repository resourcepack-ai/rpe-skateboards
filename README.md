# Skateboards

A skateboard you can ride, for Minecraft servers, built on
[RP Engine](https://github.com/resourcepack-ai/rp-engine). Push it along, tuck
for speed, ollie, flick it round in the air, ride a wall, lie down on it, and
come off when you land it badly. It brings a skatepark with it: ramps, quarter
pipes, boxes, ledges and rails, all placeable and all skateable.

It is an RP Engine addon and uses nothing but the engine's public API
(`ai.resourcepack.engine.api`). The board is an ordinary RP Engine vehicle; this
plugin is the skating on top of it. If you are writing an addon of your own, it
is a worked example of `Vehicle.input()`, `nudge`, `spin`, `setSpeedLimit`,
`dress` and the vehicle events.

![Riding a skateboard in Minecraft](https://resourcepack.ai/listings/skateboards/riding.gif)

## Requirements

- **Spigot or Paper 1.19.4 or newer.** No client mods.
- **[RP Engine](https://resourcepack.ai/rp-engine) 0.2.32 or newer.**

## Installing

1. Put `RPEngine.jar` and `RPE-skateboards-<version>.jar` in `plugins/` and
   start the server.
2. Skateboards installs its content into
   `plugins/RPEngine/content/skateboards/` and reloads the engine once, so the
   resource pack the engine serves carries the board, its animations and the
   park pieces. That folder is yours to edit; an update overwrites only its
   own files.

## Riding

| Key | What it does |
|---|---|
| W | Push. A kick every 0.7 s, up to a speed your legs cannot beat |
| S | Push it backwards with the front foot, once you have stopped |
| A / D | Steer. In the air, flick the board round under you |
| Sprint | Tuck. The speed cap comes off and the board creeps toward its top speed |
| Space | Ollie. Held against a wall, ride the wall |
| Shift (hold) | Lie flat on your back along the board |
| Shift (tap) | Step off |

Land a long way round from the way you are travelling and you come off.
Straight is fine, and so is straight backwards.

## Commands

| Command | What it does | Permission | Default |
|---|---|---|---|
| `/skateboard` | Get a board | `skateboards.use` | Everyone |
| `/skateboard controls` | The keys, in chat | `skateboards.use` | Everyone |
| `/skateboard stance <left\|right>` | The foot you lead with, remembered per player | `skateboards.use` | Everyone |
| `/skateboard here` | Put a board down in front of you, without an item | `skateboards.here` | Ops |
| `/skateboard reload` | Re-read `config.yml` | `skateboards.reload` | Ops |
| `/skatepark [piece \| all]` | List the park pieces, or get them | `skateboards.park` | Ops |

Getting on a board that is already down is `skateboards.ride` (everyone).
`skateboards.here.unlimited` skips the cooldown and the cap on boards made by
command, and `skateboards.pickup.others` picks up a board somebody else put
down.

`plugins/RPESkateboards/config.yml` holds every message and the switches:
bailing, trick messages in chat, and handing players a board when they join.

The full guide is at
[resourcepack.ai/docs/rp-engine/skateboards](https://resourcepack.ai/docs/rp-engine/skateboards).

## How it is put together

| Class | What it does |
|---|---|
| `SkateboardsPlugin` | Installs the content folder into the engine, and the commands |
| `SkateboardMechanics` | The skating: pushing, the tuck, spins in the air, wall rides, bails |
| `Stance` | Which foot leads, and the mirrored set of rider animations |
| `Skatepark` | The park pieces and handing them out |
| `VehicleGuard` | Who may ride and pick up a board, and the limits on boards made by command |
| `JoinGift`, `Messages` | A board on first join, and every word the plugin says |

The content the engine builds the pack from is in
`src/main/resources/content/skateboards/`: the board's model and textures, the
vehicle definition, the rider emotes and the park pieces, in RP Engine's
[content format](https://resourcepack.ai/docs/rp-engine/content).

## Building

```bash
./gradlew build
```

The jar is `build/libs/RPE-skateboards-<version>.jar`. The build compiles
against RP Engine, which it downloads from `cdn.resourcepack.ai` the first time
and checks against a SHA-256. Set `ENGINE_JAR` to compile against a jar of your
own.

## Support

Questions and bug reports: [our Discord](https://resourcepack.ai/discord), or
an issue here.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
