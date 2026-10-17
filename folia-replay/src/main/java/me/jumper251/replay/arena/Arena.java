package me.jumper251.replay.arena;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import static me.jumper251.replay.arena.ReplayFile.Point;

public record Arena(String id, String revision, String world, Point origin, Point min, Point max,
                    String template, Point target, Point spectator) {
    public static Arena read(String id, ConfigurationSection config) {
        if (config == null) throw new IllegalArgumentException("Arena inexistente: " + id);
        String world = config.getString("source-world", "");
        if (world.isBlank()) throw new IllegalArgumentException("source-world vazio: " + id);
        Point min = point(config, "source-min"), max = point(config, "source-max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
            throw new IllegalArgumentException("Limites invertidos: " + id);
        Arena arena = new Arena(id, config.getString("revision", "1"), world,
                point(config, "source-origin"), min, max, config.getString("template", id),
                point(config, "target-origin"), point(config, "spectator-position"));
        if (arena.revision().isBlank() || arena.template().isBlank()) throw new IllegalArgumentException("Revisao/template vazio");
        double dx = arena.target().x() - arena.origin().x(), dy = arena.target().y() - arena.origin().y(), dz = arena.target().z() - arena.origin().z();
        if (dx != Math.rint(dx) || dy != Math.rint(dy) || dz != Math.rint(dz))
            throw new IllegalArgumentException("Translacao da arena deve preservar a grade de blocos: " + id);
        return arena;
    }
    private static Point point(ConfigurationSection config, String key) {
        List<?> list = config.getList(key);
        if (list == null || list.size() != 3 || list.stream().anyMatch(v -> !(v instanceof Number)))
            throw new IllegalArgumentException("Esperado [x, y, z]: " + key);
        Point point = new Point(((Number) list.get(0)).doubleValue(), ((Number) list.get(1)).doubleValue(),
                ((Number) list.get(2)).doubleValue(), 0, 0);
        if (!point.finite()) throw new IllegalArgumentException("Coordenada invalida: " + key);
        return point;
    }
    public boolean contains(Location location) {
        return location.getWorld().getName().equals(world) && contains(point(location));
    }
    public boolean contains(Point p) {
        return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }
    public static Point point(Location location) {
        return new Point(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }
}
