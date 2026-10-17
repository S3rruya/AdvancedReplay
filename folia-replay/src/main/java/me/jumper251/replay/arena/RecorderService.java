package me.jumper251.replay.arena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static me.jumper251.replay.arena.ReplayFile.*;

/** Captures Bukkit state only in the entity/region that owns it. */
final class RecorderService implements Listener {
    private final ArenaReplayPlugin plugin;
    private final Map<String, Recording> recordings = new ConcurrentHashMap<>();
    private final Map<UUID, Recording> participants = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, ScheduledTask>> tasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTask> deadlines = new ConcurrentHashMap<>();
    private final int period, maxEvents;
    private final long duration;
    private boolean closed;
    RecorderService(ArenaReplayPlugin plugin) {
        this.plugin = plugin;
        period = Math.clamp(plugin.getConfig().getInt("recording.sample-period-ticks", 1), 1, 20);
        maxEvents = Math.clamp(plugin.getConfig().getInt("recording.max-events", 1_000_000), 100, 1_000_000);
        duration = Math.clamp(plugin.getConfig().getLong("recording.max-duration-seconds", 900), 1, 3600);
    }
    synchronized String start(Arena arena, List<Player> players) {
        if (closed) throw new IllegalStateException("Gravador encerrado");
        if (players.isEmpty()) throw new IllegalArgumentException("Informe os jogadores");
        Recording recording = new Recording(arena, maxEvents);
        // Reserve all UUIDs before scheduling capture. Roll back a partial reservation on conflict.
        List<UUID> reserved = new ArrayList<>();
        for (Player player : players) {
            UUID id = player.getUniqueId(); // immutable identity; entity state is read by track() only
            if (participants.putIfAbsent(id, recording) != null) {
                reserved.forEach(key -> participants.remove(key, recording));
                throw new IllegalArgumentException("Jogador ja possui gravacao: " + id);
            }
            reserved.add(id);
        }
        tasks.put(recording.id, new ConcurrentHashMap<>());
        recordings.put(recording.id, recording);
        players.forEach(player -> track(recording, player));
        ScheduledTask deadline = Bukkit.getAsyncScheduler().runDelayed(plugin, task -> stop(recording.id), duration, java.util.concurrent.TimeUnit.SECONDS);
        deadlines.put(recording.id, deadline);
        if (!recordings.containsKey(recording.id)) { deadline.cancel(); deadlines.remove(recording.id, deadline); }
        return recording.id;
    }
    private void track(Recording recording, Entity entity) {
        UUID id = entity.getUniqueId();
        var registry = tasks.get(recording.id);
        if (registry == null || registry.containsKey(id)) return;
        boolean[] visible = {false}; // accessed only by this entity's serial scheduler
        ScheduledTask task = entity.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!recordings.containsKey(recording.id)) { scheduled.cancel(); return; }
            Location location = entity.getLocation();
            if (!recording.arena.contains(location) || entity.isDead()) {
                if (visible[0]) recording.append(Kind.REMOVE, id, null, null, null, null);
                visible[0] = false;
                if (!(entity instanceof Player)) scheduled.cancel();
                return;
            }
            State state;
            if (entity instanceof Player player) {
                byte flags = (byte) ((player.getFireTicks() > 0 ? 1 : 0) | (player.isSneaking() ? 2 : 0)
                        | (player.isSprinting() ? 8 : 0) | (player.isSwimming() ? 16 : 0)
                        | (player.isInvisible() ? 32 : 0) | (player.isGlowing() ? 64 : 0) | (player.isGliding() ? 128 : 0));
                var texture = player.getPlayerProfile().getProperties().stream()
                        .filter(property -> property.getName().equals("textures")).findFirst();
                var inventory = player.getInventory();
                List<String> equipment = Arrays.asList(encode(inventory.getItemInMainHand()), encode(inventory.getItemInOffHand()),
                        encode(inventory.getBoots()), encode(inventory.getLeggings()), encode(inventory.getChestplate()), encode(inventory.getHelmet()));
                state = new State(player.getName(), "PLAYER", texture.map(p -> p.getValue()).orElse(null),
                        texture.map(p -> p.getSignature()).orElse(null), flags, player.getPose().name(), List.copyOf(equipment));
            } else {
                state = new State("", entity.getType().name(), null, null, 0, "STANDING", List.of());
            }
            boolean accepted = recording.append(Kind.STATE, id, Arena.point(location), null, null, state);
            visible[0] = accepted;
            if (!accepted && recording.full()) stop(recording.id);
        }, () -> recording.append(Kind.REMOVE, id, null, null, null, null), 1, period);
        if (task != null) {
            ScheduledTask previous = registry.putIfAbsent(id, task);
            if (previous != null) task.cancel();
            // Stop may have sealed/removed this registry while runAtFixedRate was being registered.
            if (!recordings.containsKey(recording.id)) task.cancel();
        } else recording.append(Kind.REMOVE, id, null, null, null, null);
    }
    private static String encode(ItemStack item) {
        return item == null || item.getType().isAir() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }
    synchronized boolean stop(String id) {
        Recording recording = recordings.remove(id);
        if (recording == null) return false;
        ReplayFile replay = recording.seal();
        ScheduledTask deadline = deadlines.remove(id);
        if (deadline != null) deadline.cancel();
        participants.entrySet().removeIf(e -> e.getValue() == recording);
        var registered = tasks.remove(id);
        if (registered != null) registered.values().forEach(ScheduledTask::cancel);
        plugin.save(replay);
        return true;
    }
    synchronized void close() { closed = true; List.copyOf(recordings.keySet()).forEach(this::stop); }
    Set<String> active() { return Set.copyOf(recordings.keySet()); }

    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Recording recording = participants.get(id);
        if (recording == null) return;
        var registry = tasks.get(recording.id);
        ScheduledTask task = registry == null ? null : registry.remove(id);
        if (task != null) task.cancel();
        recording.append(Kind.REMOVE, id, null, null, null, null);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Recording recording = participants.get(event.getPlayer().getUniqueId());
        if (recording != null) track(recording, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swing(PlayerAnimationEvent event) {
        Recording recording = participants.get(event.getPlayer().getUniqueId());
        if (recording != null && recording.arena.contains(event.getPlayer().getLocation()))
            recording.append(Kind.SWING, event.getPlayer().getUniqueId(), null, event.getAnimationType().name(), null, null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageEvent event) {
        Recording recording = participants.get(event.getEntity().getUniqueId());
        if (recording != null && recording.arena.contains(event.getEntity().getLocation()))
            recording.append(Kind.HURT, event.getEntity().getUniqueId(), null, null, null, null);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        // Do not dereference the shooter: this event owns the projectile only.
        for (Recording recording : recordings.values())
            if (recording.arena.contains(projectile.getLocation())) track(recording, projectile);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        changed(event.getBlockPlaced().getLocation(), event.getBlockReplacedState().getBlockData().getAsString());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        changed(event.getBlock().getLocation(), event.getBlock().getBlockData().getAsString());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        for (var block : event.blockList()) {
            Location location = block.getLocation();
            if (Bukkit.isOwnedByCurrentRegion(location)) changed(location, block.getBlockData().getAsString());
            else plugin.getLogger().warning("Explosion crossed region ownership; block omitted at " + location);
        }
    }
    private void changed(Location location, String before) {
        // Capture matching sessions now. A delayed callback must not leak into a newer recording.
        List<Recording> matching = recordings.values().stream().filter(r -> r.arena.contains(location)).toList();
        if (matching.isEmpty()) return;
        Point point = Arena.point(location);
        Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> {
            String after = location.getBlock().getBlockData().getAsString();
            if (!before.equals(after)) matching.forEach(r -> r.append(Kind.BLOCK, null, point, after, before, null));
        }, 1);
    }
}
