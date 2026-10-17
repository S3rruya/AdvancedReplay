# Validation — 2026-10-17

Candidate: `2.0.0-spacecraft.1`, Java 21, module `folia-replay`.

## Automated

`./gradlew :folia-replay:build --console=plain` passed. Seven JUnit tests, zero failures/errors:

- Translation preserves yaw/pitch and handles negative world coordinates.
- Eight concurrent producers append 16,000 events without loss/duplicates.
- Sealing while a producer runs yields an immutable valid snapshot.
- Event limits cannot be exceeded.
- GZIP JSON round-trip preserves complete stair BlockData; a separate reader discovers newly published files.
- Storage rejects traversal and a file with a mismatched replay identity.
- Templates retain chunk data, omit world UUID, and cleanup refuses unmarked folders and template traversal.

JAR inspection confirms Java 21 bytecode (major 65), `folia-supported: true`, the v2 entry point, and no legacy `ReplaySystem` class. ZIP integrity and UTF-8 without BOM checks passed. Gson is bundled/relocated; Paper/Folia and PacketEvents are not bundled.

## Isolated runtime matrix

Only temporary test servers under the checkout were used; no production server was restarted or changed.

| Component | Exact tested version |
| --- | --- |
| Java | Oracle JDK 21.0.9 |
| Folia | 1.21.11 build 14, commit `529aabc` |
| Paper | 1.21.11 build 132, commit `c5eb079` |
| PacketEvents on Paper | 2.13.0 |
| Test clients | Mineflayer 4.37.1, protocol 1.21.11 |

Folia used four region tick threads. Recorder initialized without ProtocolLib or PacketEvents. Playback initialized with PacketEvents on Paper. The following paths passed:

- Capture two named players together and then 5,000 blocks apart in distinct Folia regions.
- One player changes to the Nether and back; the arena stream removes the actor outside the configured world.
- Player movement, main-hand swing, and placing an east-facing stair are saved. File inspection found 3,227 events in the initial 82-second replay, including complete stair BlockData.
- Completed files are transferred to Paper after the save log. The Paper reader discovers the file without restart/cache refresh.
- Two viewers simultaneously open the same replay and receive different copied world instances and different virtual entity IDs.
- An origin translation of `(+2000, 0, +3000)` changes source position `(0.5, 0, 0.5)` into destination `(2000.5, 0, 3000.5)`.
- The stair at source block `(1, 0, 0)` becomes `(2001, 0, 3000)`, preserving `facing=east`, `half=bottom`, `shape=straight`, `waterlogged=false` in the client's block data.
- Pause, speed 2, explicit leave and ordinary completion restore viewer game mode/location and remove the disposable copies.
- Recorder player quit/rejoin produces a REMOVE followed by new STATE events for the same UUID. The post-rejoin test stream contained 452 events for that player.
- Viewer quit during a paused replay unloads/removes the private world. Rejoining restores survival mode and the saved return location.
- Paper shutdown with an active paused replay preserves return PDC. The next startup reclaims the unused marked world, and the viewer recovers on join.
- The final JAR was booted again on both server builds, then used for another recording/save and playback/leave smoke test.
- Normal Folia shutdown with a recording already in progress completed a 1,236-event, 30.9-second save before the test JVM exited.

Runtime logs were scanned for plugin exceptions, off-owner access, asynchronous world access, scheduler failures and ZIP/classloader errors; none were found in the exercised paths. OSHI Windows performance-counter warnings and the test servers' offline-mode/version notifications are unrelated startup messages.

The heuristic Folia audit on this module reported **0 high / 7 review** findings. The reviewed findings are the plain-data I/O executor, test executors, Paper-only world lifecycle calls, and teleport completions that explicitly return to entity schedulers. The unchanged upstream sources still fail the audit and are not included in this module's JAR.

## Remaining validation before deployment

Not a production-scale benchmark or proof of every Minecraft mechanic. Test real arena templates, the duel plugin integration, long recordings with large item metadata, storage failure/disk exhaustion and your network transfer mechanism. Projectile capture/rendering is implemented but was not exercised with a live bow/fishing/trident scenario in this run. Signed skins were not visually tested with an authenticated client. Only Overworld arena templates and position translation (no rotation) are supported by the template loader.

Use the declared capture limits. See README for omitted mechanics, old-file incompatibility, crash behavior and memory limits. This candidate should first be deployed to staging with representative players and arenas.
