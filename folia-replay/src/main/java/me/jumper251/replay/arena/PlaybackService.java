package me.jumper251.replay.arena;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import static me.jumper251.replay.arena.ReplayFile.*;

/** Created exclusively on Paper. Viewer tasks own their player; world lifecycle is Paper-only. */
final class PlaybackService implements Listener {
    private final ArenaReplayPlugin plugin;
    private final Path templates, worldContainer;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final NamespacedKey returnKey;
    private final int maxSessions;
    private final String returnWorld;
    private final Point returnPoint;
    private static final class Session {
        final Player watcher;
        final String instance = "ar_replay_" + UUID.randomUUID();
        volatile boolean cancelled;
        volatile boolean copied;
        boolean entering, finishing, disposed;
        ReplayFile replay;
        Arena arena;
        World world;
        PacketActors actors;
        ScheduledTask clock;
        int cursor;
        double time, speed = 1;
        boolean paused;
        Session(Player watcher) { this.watcher = watcher; }
    }
    PlaybackService(ArenaReplayPlugin plugin) {
        this.plugin = plugin;
        Path configured = Path.of(plugin.getConfig().getString("playback.templates-directory", "templates"));
        templates = configured.isAbsolute() ? configured : plugin.getDataFolder().toPath().resolve(configured);
        worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        maxSessions = Math.clamp(plugin.getConfig().getInt("playback.max-concurrent-sessions", 4), 1, 32);
        returnKey = new NamespacedKey(plugin, "replay_return");
        returnWorld = plugin.getConfig().getString("playback.return-world", "world");
        var list = plugin.getConfig().getDoubleList("playback.return-position");
        if (list.size() != 3) throw new IllegalArgumentException("playback.return-position requer [x,y,z]");
        returnPoint = new Point(list.get(0), list.get(1), list.get(2), 0, 0);
        if (!returnPoint.finite() || Bukkit.getWorld(returnWorld) == null)
            throw new IllegalArgumentException("Mundo/posicao de retorno indisponivel");
        recoverCopies();
    }
    private void recoverCopies() {
        plugin.async(() -> {
            try (var paths = java.nio.file.Files.list(worldContainer)) {
                var names = paths.map(p -> p.getFileName().toString()).filter(n -> n.matches("ar_replay_[0-9a-f-]{36}")).toList();
                if (plugin.stopping()) return;
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> names.forEach(name -> {
                    if (Bukkit.getWorld(name) == null && sessions.values().stream().noneMatch(s -> s.instance.equals(name)))
                        plugin.async(() -> {
                            try { WorldTemplates.delete(worldContainer, name); }
                            catch (IOException error) { plugin.getLogger().warning("Copia antiga preservada: " + name + ": " + error.getMessage()); }
                        });
                }));
            } catch (IOException error) { plugin.getLogger().log(Level.WARNING, "Falha ao verificar copias antigas", error); }
        });
    }
    synchronized boolean reserve(Player player) {
        return sessions.size() < maxSessions && sessions.putIfAbsent(player.getUniqueId(), new Session(player)) == null;
    }
    void prepare(Player player, ReplayFile replay, Arena arena) throws IOException {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.cancelled) return;
        if (!arena.revision().equals(replay.revision()) || !arena.world().equals(replay.sourceWorld())
                || !arena.origin().equals(replay.origin())) throw new IOException("Revisao/mundo/origem do template nao correspondem ao replay");
        for (Event event : replay.events())
            if (event.position() != null && !arena.contains(event.position())) throw new IOException("Evento fora da arena configurada");
        session.replay = replay; session.arena = arena;
        WorldTemplates.copy(templates, arena.template(), worldContainer, session.instance);
        session.copied = true;
        if (session.cancelled || plugin.stopping()) { delete(session); return; }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (session.cancelled || plugin.stopping()) { delete(session); return; }
            try {
                // Paper-only world loading; never reachable from a Folia RECORDER instance.
                session.world = Objects.requireNonNull(Bukkit.createWorld(new WorldCreator(session.instance)), "Falha ao carregar copia");
                session.world.setAutoSave(false);
                session.world.setGameRule(GameRules.SPAWN_MOBS, false);
                session.world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
                session.world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
                session.world.setGameRule(GameRules.ADVANCE_TIME, false);
                session.world.setGameRule(GameRules.ADVANCE_WEATHER, false);
                session.world.setGameRule(GameRules.MOB_DROPS, false);
                session.world.setGameRule(GameRules.BLOCK_DROPS, false);
                session.world.setDifficulty(Difficulty.PEACEFUL);
                boolean queued = player.getScheduler().execute(plugin, () -> enter(session), () -> abandon(session), 1);
                if (!queued) abandon(session);
            } catch (Exception error) { fail(player, error); }
        });
    }
    private void enter(Session session) {
        Player player = session.watcher;
        if (session.cancelled || !player.isOnline()) { abandon(session); return; }
        // Keep return data in the player's saved PDC so quit/restart can recover spectator mode.
        if (player.getPersistentDataContainer().has(returnKey)) {
            fail(player, new IllegalStateException("Recuperacao anterior pendente; entre novamente antes de assistir")); return;
        }
        Location start = player.getLocation();
        String saved = String.join(";", Base64.getEncoder().encodeToString(start.getWorld().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Double.toString(start.getX()), Double.toString(start.getY()), Double.toString(start.getZ()),
                Float.toString(start.getYaw()), Float.toString(start.getPitch()), player.getGameMode().name(),
                Boolean.toString(player.getAllowFlight()), Boolean.toString(player.isFlying()));
        player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, saved);
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        Point spawn = session.arena.spectator();
        session.entering = true;
        player.teleportAsync(location(session.world, spawn)).whenComplete((success, error) -> {
            if (plugin.stopping()) return;
            boolean queued = player.getScheduler().execute(plugin, () -> {
                session.entering = false;
                if (session.cancelled || error != null || !Boolean.TRUE.equals(success)) { finish(session); return; }
                session.actors = new PacketActors(player);
                session.clock = player.getScheduler().runAtFixedRate(plugin, task -> tick(session), () -> abandon(session), 2, 1);
                if (session.clock == null) abandon(session);
                else plugin.reply(player, "Reproduzindo " + session.replay.id() + ". /replay leave para sair.");
            }, () -> abandon(session), 1);
            if (!queued) abandon(session);
        });
    }
    private void tick(Session session) {
        if (session.cancelled || !session.watcher.isOnline()) { finish(session); return; }
        if (!session.watcher.getWorld().equals(session.world)) { finish(session); return; }
        if (session.paused) return;
        try {
            List<Event> events = session.replay.events();
            // A bounded batch prevents a malicious/dense replay from monopolizing the Paper tick.
            int budget = 5000;
            while (session.cursor < events.size() && events.get(session.cursor).time() <= session.time && budget-- > 0)
                apply(session, events.get(session.cursor++));
            if (session.cursor < events.size() && events.get(session.cursor).time() <= session.time) return;
            session.time += 50 * session.speed;
            if (session.time > session.replay.duration() + 1000 && session.cursor == events.size()) finish(session);
        } catch (Exception error) { fail(session.watcher, error); }
    }
    private void apply(Session session, Event event) {
        Point point = event.position() == null ? null : event.position().translate(session.replay.origin(), session.arena.target());
        switch (event.kind()) {
            case STATE -> session.actors.state(event.actor(), point, event.state());
            case REMOVE -> session.actors.remove(event.actor());
            case SWING -> session.actors.animate(event.actor(), false, "OFF_ARM_SWING".equals(event.value()));
            case HURT -> session.actors.animate(event.actor(), true, false);
            case BLOCK -> {
                // Paper has one owning tick thread. No block state access from I/O callbacks.
                var block = location(session.world, point).getBlock();
                block.setBlockData(Bukkit.createBlockData(event.value()), false);
            }
        }
    }
    void control(Player player, String[] args) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) throw new IllegalArgumentException("Voce nao possui sessao");
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "leave" -> finish(session);
            case "pause" -> { session.paused = !session.paused; plugin.reply(player, session.paused ? "Pausado" : "Continuando"); }
            case "speed" -> {
                if (args.length != 2) throw new IllegalArgumentException("speed <0.5|1|2>");
                double speed = Double.parseDouble(args[1]);
                if (speed != 0.5 && speed != 1 && speed != 2) throw new IllegalArgumentException("Velocidade deve ser 0.5, 1 ou 2");
                session.speed = speed;
            }
        }
    }
    void fail(Player player, Exception error) {
        plugin.getLogger().log(Level.WARNING, "Falha no replay", error);
        plugin.reply(player, "Falha: " + error.getMessage());
        if (plugin.stopping()) return;
        player.getScheduler().run(plugin, task -> {
            Session session = sessions.get(player.getUniqueId());
            if (session != null) finish(session);
        }, () -> { Session session = sessions.get(player.getUniqueId()); if (session != null) abandon(session); });
    }
    private void finish(Session session) {
        session.cancelled = true;
        // Wait for the entry teleport callback before returning the viewer. Otherwise the
        // pending entry could complete after their return and strand them in the copy.
        if (session.entering || session.finishing) return;
        session.finishing = true;
        sessions.remove(session.watcher.getUniqueId(), session);
        if (session.clock != null) session.clock.cancel();
        try { if (session.actors != null) session.actors.clear(); }
        catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Falha removendo atores virtuais", error); }
        finally { restore(session.watcher, () -> cleanup(session)); }
    }
    private void abandon(Session session) {
        // May be an entity retirement callback. Touch plugin-owned data only, then hand off cleanup.
        session.cancelled = true;
        sessions.remove(session.watcher.getUniqueId(), session);
        if (session.clock != null) session.clock.cancel();
        if (!plugin.stopping()) Bukkit.getGlobalRegionScheduler().execute(plugin, () -> cleanup(session));
    }
    private void restore(Player player, Runnable done) {
        String saved = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        if (saved == null) { done.run(); return; }
        try {
            String[] fields = saved.split(";");
            World original = Bukkit.getWorld(new String(Base64.getDecoder().decode(fields[0]), java.nio.charset.StandardCharsets.UTF_8));
            Location destination = original == null ? fallback() : new Location(original, Double.parseDouble(fields[1]),
                    Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Float.parseFloat(fields[4]), Float.parseFloat(fields[5]));
            org.bukkit.GameMode mode = org.bukkit.GameMode.valueOf(fields[6]);
            player.teleportAsync(destination).whenComplete((success, error) -> {
                if (plugin.stopping()) return;
                player.getScheduler().run(plugin, task -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        player.setGameMode(mode);
                        player.setAllowFlight(Boolean.parseBoolean(fields[7]));
                        player.setFlying(Boolean.parseBoolean(fields[8]) && player.getAllowFlight());
                        player.getPersistentDataContainer().remove(returnKey);
                    } else plugin.reply(player, "Falha ao retornar. Seus dados de retorno foram preservados; entre novamente.");
                    done.run();
                }, () -> { if (!plugin.stopping()) Bukkit.getGlobalRegionScheduler().execute(plugin, done); });
            });
        } catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Retorno pendente para " + player.getUniqueId(), error); done.run(); }
    }
    private Location fallback() {
        World world = Bukkit.getWorld(returnWorld);
        if (world == null) throw new IllegalStateException("Mundo de retorno indisponivel: " + returnWorld);
        return location(world, returnPoint);
    }
    private void cleanup(Session session) {
        if (session.world == null) {
            if (session.copied) plugin.async(() -> delete(session));
            return; // prepare() owns cleanup while a filesystem copy is pending
        }
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            if (session.disposed) return;
            if (!session.world.getPlayers().isEmpty()) {
                plugin.getLogger().warning("Copia preservada pois ainda possui jogadores: " + session.instance); return;
            }
            if (Bukkit.unloadWorld(session.world, false)) {
                session.disposed = true;
                plugin.async(() -> delete(session));
            }
            else plugin.getLogger().warning("Falha ao descarregar copia: " + session.instance);
        });
    }
    private void delete(Session session) {
        try { WorldTemplates.delete(worldContainer, session.instance); }
        catch (IOException error) { plugin.getLogger().log(Level.WARNING, "Falha removendo copia " + session.instance, error); }
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> restore(player, () -> {}), null, 10);
    }
    @EventHandler(ignoreCancelled = true) public void physics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (privateWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void flow(org.bukkit.event.block.BlockFromToEvent event) {
        if (privateWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void ignite(org.bukkit.event.block.BlockIgniteEvent event) {
        if (privateWorld(event.getBlock().getWorld())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void spawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (privateWorld(event.getLocation().getWorld())) event.setCancelled(true);
    }
    private static boolean privateWorld(World world) { return world.getName().startsWith("ar_replay_"); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) abandon(session); // PDC is retained for recovery on join
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        // Do not allow a second viewer to enter another session's private world.
        if (event.getTo() == null) return;
        String world = event.getTo().getWorld().getName();
        if (!world.startsWith("ar_replay_")) return;
        Session own = sessions.get(event.getPlayer().getUniqueId());
        if (own == null || !own.instance.equals(world) || own.cancelled) event.setCancelled(true);
    }
    void close() {
        for (Session session : sessions.values()) {
            session.cancelled = true;
            if (session.clock != null) session.clock.cancel();
            // Player PDC survives shutdown; worlds remain recoverable until next normal startup.
        }
        sessions.clear();
    }
    private static Location location(World world, Point point) {
        return new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }
}
