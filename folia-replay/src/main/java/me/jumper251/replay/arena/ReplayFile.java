package me.jumper251.replay.arena;

import java.util.List;
import java.util.UUID;

/** Only plain immutable data crosses entity, region, and I/O boundaries. */
public record ReplayFile(int format, String minecraft, String id, String arena, String revision,
                         String sourceWorld, Point origin, long createdAt, long duration,
                         List<Event> events) {
    public static final int FORMAT = 2;
    public record Point(double x, double y, double z, float yaw, float pitch) {
        public Point translate(Point source, Point target) {
            return new Point(x - source.x + target.x, y - source.y + target.y,
                    z - source.z + target.z, yaw, pitch);
        }
        public boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }
    public enum Kind { STATE, REMOVE, BLOCK, SWING, HURT }
    public record State(String name, String type, String texture, String signature,
                        int flags, String pose, List<String> equipment) {}
    public record Event(long time, long sequence, Kind kind, UUID actor, Point position,
                        String value, String before, State state) {}
}
