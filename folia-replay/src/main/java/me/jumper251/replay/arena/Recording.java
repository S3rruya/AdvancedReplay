package me.jumper251.replay.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static me.jumper251.replay.arena.ReplayFile.*;

/** Short lock protects plain-data append/seal only; never Bukkit access or I/O. */
final class Recording {
    final String id = UUID.randomUUID().toString();
    final Arena arena;
    final long createdAt = System.currentTimeMillis();
    private final long started = System.nanoTime();
    private final int limit;
    private final List<Event> events = new ArrayList<>();
    private boolean sealed;
    private long sequence;
    Recording(Arena arena, int limit) { this.arena = arena; this.limit = limit; }
    synchronized boolean append(Kind kind, UUID actor, Point point, String value, String before, State state) {
        if (sealed || events.size() >= limit) return false;
        events.add(new Event(elapsed(), sequence++, kind, actor, point, value, before, state));
        return true;
    }
    synchronized boolean full() { return events.size() >= limit; }
    synchronized ReplayFile seal() {
        if (sealed) return null;
        sealed = true;
        // append assigns both monotonic time and sequence while holding this same lock.
        return new ReplayFile(FORMAT, "1.21.11", id, arena.id(), arena.revision(), arena.world(),
                arena.origin(), createdAt, elapsed(), List.copyOf(events));
    }
    private long elapsed() { return (System.nanoTime() - started) / 1_000_000L; }
}
