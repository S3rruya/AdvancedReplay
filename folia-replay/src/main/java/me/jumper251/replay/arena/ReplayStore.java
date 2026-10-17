package me.jumper251.replay.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Call on I/O executor only. No object deserialization or Bukkit objects. */
final class ReplayStore {
    private final Path directory;
    private final Gson gson = new GsonBuilder().create();
    ReplayStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }
    Path file(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("ID invalido");
        return directory.resolve(id + ".arena-replay.gz");
    }
    Path save(ReplayFile replay) throws IOException {
        Files.createDirectories(directory);
        Path destination = file(replay.id());
        Path temporary = Files.createTempFile(directory, ".writing-", ".tmp");
        try {
            try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
                gson.toJson(replay, writer);
            }
            // A file becomes visible to consumers only after its complete, atomic publication.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return destination;
        } finally { Files.deleteIfExists(temporary); }
    }
    ReplayFile load(String id) throws IOException {
        try (InputStream stream = new GZIPInputStream(Files.newInputStream(file(id)))) {
            // Bound decompression as well as event count; never accept an unbounded compressed payload.
            byte[] bytes = stream.readNBytes(256 * 1024 * 1024 + 1);
            if (bytes.length > 256 * 1024 * 1024) throw new IOException("Replay excede 256 MiB");
            ReplayFile replay = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), ReplayFile.class);
            validate(replay, id);
            return replay;
        } catch (RuntimeException error) { throw new IOException("Replay invalido: " + error.getMessage(), error); }
    }
    static void validate(ReplayFile replay, String id) throws IOException {
        if (replay == null || replay.format() != ReplayFile.FORMAT || !"1.21.11".equals(replay.minecraft())
                || !id.equals(replay.id()) || replay.origin() == null || !replay.origin().finite()
                || replay.events() == null || replay.events().isEmpty() || replay.events().size() > 1_000_000
                || replay.duration() < 0 || replay.duration() > 3_600_000)
            throw new IOException("Formato, versao, duracao ou eventos invalidos");
        long time = -1, sequence = -1;
        for (var event : replay.events()) {
            if (event == null || event.kind() == null || event.time() < time || event.sequence() <= sequence
                    || event.time() > replay.duration() || event.position() != null && !event.position().finite())
                throw new IOException("Ordem/coordenadas invalidas");
            if (event.kind() == ReplayFile.Kind.STATE && (event.actor() == null || event.position() == null || event.state() == null))
                throw new IOException("Estado sem ator/posicao");
            if (event.kind() == ReplayFile.Kind.BLOCK && (event.position() == null || event.value() == null || event.before() == null))
                throw new IOException("Bloco incompleto");
            time = event.time(); sequence = event.sequence();
        }
    }
    List<String> list() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".arena-replay.gz"))
                    .map(n -> n.substring(0, n.length() - ".arena-replay.gz".length())).sorted().toList();
        }
    }
}
