package me.jumper251.replay.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Filesystem only. Source must be an offline, immutable world template. */
final class WorldTemplates {
    private static final String MARKER = ".arena-replay-instance";
    private static final Set<String> OMIT = Set.of("uid.dat", "session.lock", "playerdata", "stats", "advancements", "entities");
    static Path copy(Path templates, String template, Path worldContainer, String instance) throws IOException {
        Path root = templates.toAbsolutePath().normalize();
        Path source = root.resolve(template).normalize();
        if (!source.startsWith(root) || source.equals(root) || !Files.isDirectory(source)
                || !Files.isRegularFile(source.resolve("level.dat"))) throw new IOException("Template invalido: " + template);
        // Reject symlinks both in the template root ancestry and within the copied tree.
        Path cursor = source;
        while (cursor != null) { if (Files.isSymbolicLink(cursor)) throw new IOException("Symlink em template"); cursor = cursor.getParent(); }
        Path destination = target(worldContainer, instance);
        Files.createDirectory(destination);
        Files.writeString(destination.resolve(MARKER), instance, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) throw new IOException("Symlink em template");
                    if (!directory.equals(source) && OMIT.contains(directory.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                    Files.createDirectories(destination.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) throw new IOException("Arquivo nao regular em template");
                    if (!OMIT.contains(file.getFileName().toString()) && !file.getFileName().toString().equals(MARKER))
                        Files.copy(file, destination.resolve(source.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return destination;
        } catch (IOException error) { delete(worldContainer, instance); throw error; }
    }
    static Path target(Path container, String instance) {
        if (!instance.matches("ar_replay_[0-9a-f-]{36}")) throw new IllegalArgumentException("Instancia invalida");
        Path parent = container.toAbsolutePath().normalize();
        Path target = parent.resolve(instance).normalize();
        if (!target.getParent().equals(parent)) throw new IllegalArgumentException("Caminho invalido");
        return target;
    }
    static void delete(Path container, String instance) throws IOException {
        Path directory = target(container, instance);
        if (!Files.exists(directory)) return;
        if (Files.isSymbolicLink(directory) || !Files.readString(directory.resolve(MARKER), StandardCharsets.UTF_8).equals(instance))
            throw new IOException("Recusando remover diretorio sem marcador valido");
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
