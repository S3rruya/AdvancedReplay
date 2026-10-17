package me.jumper251.replay.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static me.jumper251.replay.arena.ReplayFile.*;

class ReplayCoreTest {
    @TempDir Path temporary;
    private final Point origin = new Point(1000, 64, -1000, 0, 0);
    private Arena arena() {
        return new Arena("duel", "1", "Duels", origin, new Point(900, 0, -1100, 0, 0),
                new Point(1100, 200, -900, 0, 0), "duel-v1", new Point(0, 64, 0, 0, 0), origin);
    }
    @Test void mappingPreservesRotationAndSupportsNegativeCoordinates() {
        Point mapped = new Point(1003.25, 70, -1004.5, 120, -35).translate(origin, new Point(-2000, 100, 500, 0, 0));
        assertEquals(new Point(-1996.75, 106, 495.5, 120, -35), mapped);
    }
    @Test void concurrentRegionsSealWithoutLosingOrDuplicatingEvents() throws Exception {
        Recording recording = new Recording(arena(), 100_000);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                for (int j = 0; j < 2000; j++) recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null);
            }));
            for (var future : futures) future.get();
        }
        ReplayFile replay = recording.seal();
        assertEquals(16000, replay.events().size());
        assertEquals(16000, replay.events().stream().map(Event::sequence).distinct().count());
        assertFalse(recording.append(Kind.REMOVE, UUID.randomUUID(), null, null, null, null));
        assertNull(recording.seal());
        ReplayStore.validate(replay, replay.id());
    }
    @Test void sealRacingProducersProducesValidImmutableSnapshot() throws Exception {
        Recording recording = new Recording(arena(), 100_000);
        CountDownLatch entered = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> producer = pool.submit(() -> { for (int i = 0; i < 10000; i++) {
                recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null); entered.countDown();
            }});
            entered.await();
            ReplayFile replay = recording.seal();
            producer.get();
            ReplayStore.validate(replay, replay.id());
            assertThrows(UnsupportedOperationException.class, () -> replay.events().clear());
        }
    }
    @Test void eventLimitNeverGrowsPastConfiguredBound() {
        Recording recording = new Recording(arena(), 2);
        assertTrue(recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null));
        assertTrue(recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null));
        assertFalse(recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null));
        assertEquals(2, recording.seal().events().size());
    }
    @Test void storeRoundTripsFullBlockDataAndDiscoversExternalFiles() throws Exception {
        Recording recording = new Recording(arena(), 10);
        recording.append(Kind.BLOCK, null, origin, "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]", "minecraft:air", null);
        ReplayFile replay = recording.seal();
        ReplayStore writer = new ReplayStore(temporary);
        ReplayStore reader = new ReplayStore(temporary);
        assertEquals(List.of(), reader.list());
        writer.save(replay);
        assertEquals(List.of(replay.id()), reader.list());
        assertEquals(replay, reader.load(replay.id()));
        try (var paths = Files.list(temporary)) { assertEquals(1, paths.count()); }
    }
    @Test void storageRejectsTraversalAndWrongFileIdentity() throws Exception {
        ReplayStore store = new ReplayStore(temporary);
        assertThrows(IllegalArgumentException.class, () -> store.file("../outside"));
        Recording recording = new Recording(arena(), 10);
        recording.append(Kind.SWING, UUID.randomUUID(), null, null, null, null);
        ReplayFile replay = recording.seal();
        store.save(replay);
        String other = UUID.randomUUID().toString();
        Files.copy(store.file(replay.id()), store.file(other));
        assertThrows(IOException.class, () -> store.load(other));
    }
    @Test void templateCopiesWithoutIdentityAndOnlyDeletesMarkedInstances() throws Exception {
        Path templates = Files.createDirectory(temporary.resolve("templates"));
        Path source = Files.createDirectory(templates.resolve("duel"));
        Files.writeString(source.resolve("level.dat"), "test");
        Files.writeString(source.resolve("uid.dat"), "original-world-uuid");
        Path regions = Files.createDirectory(source.resolve("region"));
        Files.writeString(regions.resolve("r.0.0.mca"), "chunk");
        Path container = Files.createDirectory(temporary.resolve("worlds"));
        String instance = "ar_replay_" + UUID.randomUUID();
        Path copy = WorldTemplates.copy(templates, "duel", container, instance);
        assertTrue(Files.exists(copy.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(copy.resolve("uid.dat")));
        WorldTemplates.delete(container, instance);
        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(source.resolve("uid.dat")));
        String unmarked = "ar_replay_" + UUID.randomUUID();
        Files.createDirectory(container.resolve(unmarked));
        assertThrows(IOException.class, () -> WorldTemplates.delete(container, unmarked));
        assertTrue(Files.exists(container.resolve(unmarked)));
        assertThrows(IOException.class, () -> WorldTemplates.copy(templates, "../worlds", container, "ar_replay_" + UUID.randomUUID()));
    }
}
