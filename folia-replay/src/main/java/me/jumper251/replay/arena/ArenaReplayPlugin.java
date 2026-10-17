package me.jumper251.replay.arena;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class ArenaReplayPlugin extends JavaPlugin {
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "ArenaReplay-IO"));
    private ReplayStore store;
    private Map<String, Arena> arenas;
    private RecorderService recorder;
    private PlaybackService playback;
    private volatile boolean stopping;
    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            if (!Bukkit.getMinecraftVersion().equals("1.21.11")) throw new IllegalArgumentException("Requer Minecraft 1.21.11");
            String role = getConfig().getString("role", "RECORDER").toUpperCase(Locale.ROOT);
            boolean folia;
            try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); folia = true; }
            catch (ClassNotFoundException ignored) { folia = false; }
            Path configured = Path.of(getConfig().getString("storage-directory", "replays-v2"));
            store = new ReplayStore(configured.isAbsolute() ? configured : getDataFolder().toPath().resolve(configured));
            Map<String, Arena> loaded = new HashMap<>();
            var section = Objects.requireNonNull(getConfig().getConfigurationSection("arenas"), "arenas ausente");
            for (String id : section.getKeys(false)) loaded.put(id, Arena.read(id, section.getConfigurationSection(id)));
            arenas = Map.copyOf(loaded);
            switch (role) {
                case "RECORDER" -> {
                    recorder = new RecorderService(this);
                    Bukkit.getPluginManager().registerEvents(recorder, this);
                }
                case "PLAYBACK" -> {
                    if (folia) throw new IllegalArgumentException("PLAYBACK exige Paper, RECORDER e o modo para Folia");
                    if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) throw new IllegalArgumentException("PLAYBACK requer PacketEvents 2.13.0");
                    playback = new PlaybackService(this);
                    Bukkit.getPluginManager().registerEvents(playback, this);
                }
                default -> throw new IllegalArgumentException("role deve ser RECORDER ou PLAYBACK");
            }
            Objects.requireNonNull(getCommand("replay")).setExecutor(this);
            getLogger().info("Arena replay v2: " + role + " em " + Bukkit.getVersion());
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "Configuracao invalida", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    Arena arena(String id) {
        Arena arena = arenas.get(id);
        if (arena == null) throw new IllegalArgumentException("Arena inexistente: " + id);
        return arena;
    }
    /** API for duel plugins; handles are sampled later on their own entity schedulers. */
    public String recordArena(String arenaId, Collection<Player> players) {
        if (recorder == null || stopping) throw new IllegalStateException("RECORDER indisponivel");
        return recorder.start(arena(arenaId), List.copyOf(new LinkedHashSet<>(players)));
    }
    public boolean stopRecording(String replayId) { return recorder != null && recorder.stop(replayId); }
    boolean stopping() { return stopping; }
    void async(Runnable action) { if (!io.isShutdown()) io.execute(action); }
    void save(ReplayFile replay) {
        if (replay.events().isEmpty()) {
            getLogger().warning("Gravacao vazia descartada: " + replay.id() + "; verifique jogadores, mundo e limites da arena");
            return;
        }
        async(() -> {
            try { getLogger().info("Replay salvo: " + store.save(replay)); }
            catch (Exception error) { getLogger().log(Level.SEVERE, "FALHA AO SALVAR " + replay.id(), error); }
        });
    }
    void reply(CommandSender sender, String message) {
        if (stopping) return;
        if (sender instanceof Player player)
            player.getScheduler().run(this, task -> player.sendMessage("[Replay] " + message), null);
        else Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage("[Replay] " + message));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (!sender.hasPermission("replay.admin")) return true;
            if (args.length == 0) {
                reply(sender, "/replay record <arena> [jogadores...] | stop <id> | active | list | play <id> [arena] | leave | pause | speed <0.5|1|2>");
            } else switch (args[0].toLowerCase(Locale.ROOT)) {
                case "record" -> {
                    if (recorder == null) throw new IllegalArgumentException("Servidor nao e RECORDER");
                    if (args.length < 2) throw new IllegalArgumentException("record <arena> [jogadores...]");
                    Set<Player> players = new LinkedHashSet<>();
                    for (int i = 2; i < args.length; i++) players.add(Objects.requireNonNull(Bukkit.getPlayerExact(args[i]), "Jogador offline: " + args[i]));
                    if (args.length == 2 && sender instanceof Player player) players.add(player);
                    String id = recorder.start(arena(args[1]), List.copyOf(players));
                    reply(sender, "Gravando: " + id + ". O log confirma quando o arquivo estiver salvo.");
                }
                case "stop" -> {
                    if (recorder == null || args.length != 2) throw new IllegalArgumentException("stop <id> no RECORDER");
                    reply(sender, recorder.stop(args[1]) ? "Gravacao encerrada; salvamento em andamento." : "ID nao esta gravando.");
                }
                case "active" -> reply(sender, recorder != null ? recorder.active().toString() : "Modo PLAYBACK");
                case "list" -> async(() -> {
                    try { reply(sender, String.join(", ", store.list())); }
                    catch (Exception error) { reply(sender, "Falha ao listar: " + error.getMessage()); }
                });
                case "play" -> {
                    if (playback == null || !(sender instanceof Player player) || args.length < 2 || args.length > 3)
                        throw new IllegalArgumentException("play <id> [arena] como jogador no Paper PLAYBACK");
                    String id = args[1], override = args.length == 3 ? args[2] : null;
                    if (!playback.reserve(player)) throw new IllegalArgumentException("Sessao existente ou limite atingido");
                    async(() -> {
                        try {
                            ReplayFile replay = store.load(id);
                            Arena arena = arena(override == null ? replay.arena() : override);
                            playback.prepare(player, replay, arena);
                        } catch (Exception error) { playback.fail(player, error); }
                    });
                }
                case "leave", "pause", "speed" -> {
                    if (playback == null || !(sender instanceof Player player)) throw new IllegalArgumentException("Comando do espectador no Paper");
                    playback.control(player, args);
                }
                default -> throw new IllegalArgumentException("Subcomando desconhecido");
            }
        } catch (Exception error) { reply(sender, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
        return true;
    }
    @Override public void onDisable() {
        stopping = true;
        if (recorder != null) recorder.close();
        if (playback != null) playback.close();
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        // Executor owns immutable snapshots; no Bukkit world/entity access during final writes.
        io.shutdown();
    }
}
