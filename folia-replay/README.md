# AdvancedReplay: Folia recorder / Paper arena playback

SpaceCraft fork of Jumper251/AdvancedReplay, GPL-3.0. The original source and its build remain in the root project. This independent module replaces the original runtime with an ownership-aware recorder and a Paper-only player. Its JAR does **not** contain or initialize the legacy runtime.

Requirements: Minecraft **1.21.11**, Java **21**, Folia for recording and Paper for playback. Install **PacketEvents 2.13.0** on the Paper server only. ProtocolLib is not required by this module. Do not install the original AdvancedReplay JAR alongside this one (same plugin name/command).

## Build

```sh
./gradlew :folia-replay:build
```

Artifact: `folia-replay/build/libs/AdvancedReplay-FoliaPaper.jar`. Compilation uses the Folia 1.21.11 API in compileOnly scope. Dependencies for packet rendering are provided by PacketEvents; Gson is relocated inside the JAR.

## Configure recording

1. Install the JAR on Folia and let it generate `plugins/AdvancedReplay/config.yml`.
2. Set `role: RECORDER`. Define each arena's `source-world`, inclusive bounds, `source-origin`, and `revision` in the config. Bounds must include the whole played area and projectile paths.
3. Run `/replay record <arena> <player1> <player2> ...`. With no names, it records the command sender. Players may occupy different Folia regions. Starting a second recording for an already recorded player is refused.
4. Run `/replay stop <id>`. The response means capture stopped; **wait for `Replay salvo:` in the console before transferring**. Max duration and the event budget also stop capture. `/replay active` lists active IDs.

Players are sampled on their own entity schedulers, including position/rotation, pose, flags, signed skin properties, hands and armor. Projectiles launched inside the arena are sampled on their own schedulers. Swing, hurt, place, break and locally owned explosion block changes are recorded. Leaving the arena/world removes the actor; reentering restores it. Player quit/rejoin is supported while the recording is active.

The timeline uses monotonic elapsed milliseconds. Regions have independent ticks; timestamps preserve observed ordering rather than claiming one atomic server tick across all regions. The period is configurable, default 1 tick per entity. Capture does not trust inbound client movement packets.

## Prepare the arena copy

On the Paper server, provide an **offline world folder** at `plugins/AdvancedReplay/templates/<template>/`, including `level.dat` and `region/`. Keep it immutable while the server uses it. Do not copy a world folder while its source server is writing it. Create templates from an offline arena export/backup, before the match changes blocks.

The folder contains the arena at the coordinates configured by `target-origin`. Copying a world folder does not relocate its terrain: if you change the target origin, prepare the terrain at that destination first. The plugin translates replay coordinates; it does not paste schematics or rotate maps.

Recordings identify the arena and template revision. Change `revision` when the baseline map changes, and retain the old version for its existing replays. Playback refuses a mismatched revision, source world or source origin. An explicit arena override can select another mapping with the same source identity and revision.

Example: source arena origin `[1000, 64, 1000]`, destination arena origin `[0, 64, 0]`. A recorded actor at `[1005, 66, 1002]` appears at `[5, 66, 2]`. This applies to players, projectiles and block changes. Use integer translations to preserve the block grid. `spectator-position` is already in destination coordinates.

## Transfer and watch

On Paper set `role: PLAYBACK`, set `playback.return-world` and `return-position`, and install PacketEvents. Define the arena mapping with the same source identity/revision and the prepared template's destination origin.

Transfer the completed `<UUID>.arena-replay.gz` into `replays-v2/`, using a temporary name during transfer then an atomic rename. Alternatively set an absolute `storage-directory` backed by a shared filesystem that supports atomic rename. Both servers use the same JAR and format version. `/replay list` reads the directory each time; there is no stale cross-server listing cache.

```text
/replay play <UUID> [arena-mapping]
/replay pause
/replay speed 0.5
/replay speed 1
/replay speed 2
/replay leave
```

Each viewer receives a separate world copy named `ar_replay_<UUID>`. The immutable template is copied asynchronously; Paper loads/unloads the copy on its tick thread. Players/projectiles are virtual packet entities seen only by that viewer, so replay projectiles cannot damage anything. Block changes use full Bukkit BlockData strings and disable physics. Player mode and location are restored on exit. Return information is saved in player PDC for disconnect/restart recovery; configure a valid fallback world.

Copies are unloaded and removed after a successful return. Cleanup refuses worlds containing players and directories without the correct ownership marker. On startup, unused marked copies left by an interrupted session are reclaimed asynchronously; player PDC still restores the viewer's original location/mode on join. If manual cleanup is necessary, stop Paper and remove only `ar_replay_*` folders whose `.arena-replay-instance` matches their name. Never delete templates or source worlds. World loading still has a main-thread cost on Paper; limit concurrency and template size.

## Integrate with a duel plugin

Declare `depend: [AdvancedReplay]`, compile against this module and use the public entry point:

```java
ArenaReplayPlugin replay = (ArenaReplayPlugin) Bukkit.getPluginManager().getPlugin("AdvancedReplay");
String id = replay.recordArena("example", List.of(first, second));
// When the match ends:
replay.stopRecording(id);
```

Player references are scheduling handles; the recorder reads mutable player state inside each entity task. Stop seals an immutable snapshot before asynchronous I/O. A stop request is not a durable-save acknowledgment; use the save log for operational confirmation in this version.

## Format and limits

This is a **new v2 format**, GZIP UTF-8 JSON with a schema/version header, UUID, map revision and ordered immutable events. It does not load upstream Java-serialized `.replay` files or implement the old ReplayAPI. An old file cannot be made compatible by changing its extension.

Scope is arena match playback, not a full world backup or deterministic simulation. Changed blocks preserve BlockData (orientation, waterlogging, etc.); changes to block-entity NBT/container contents, redstone, fluids, piston motion, natural block updates, chat, dropped items and existing mobs are not captured. Initial block-entity data comes from the template. A single replay cannot span different source worlds; actors disappear outside the configured source arena. Explosion blocks outside the event's owned region are omitted with a warning instead of reading another region. Use an arena integration if those mechanics require additional capture.

No automatic rewind/seek or backward playback is exposed. Pause and speed control are supported. Recordings remain in memory until sealed (default 15 minutes / 1,000,000 events); monitor heap and tune budgets for player count and equipment. A hard process crash before saving loses the in-memory recording. A normal stop queues final saves; always wait for the completion logs before forcibly terminating the JVM.

The module has no hot reload. Change config with the server stopped, then perform a normal restart. PLAYBACK explicitly refuses Folia; using its global scheduler would not make arbitrary world/entity operations safe there.

See [VALIDATION.md](VALIDATION.md) for the tested builds, automated checks and runtime evidence.
