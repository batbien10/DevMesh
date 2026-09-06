package devmesh.compact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/** Small source cache with metadata validation so edits never serve stale content. */
public final class RepositoryContextCache {
    private record Entry(String content, long modifiedMillis, long size) {}

    private final Map<Path, Entry> entries = new HashMap<>();
    private long hits;
    private long misses;

    public synchronized String read(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
        Entry cached = entries.get(normalized);
        if (cached != null && cached.modifiedMillis() == attributes.lastModifiedTime().toMillis()
                && cached.size() == attributes.size()) {
            hits++;
            return cached.content();
        }
        String content = Files.readString(normalized);
        entries.put(normalized, new Entry(content, attributes.lastModifiedTime().toMillis(), attributes.size()));
        misses++;
        return content;
    }

    public synchronized void invalidate(Path path) {
        entries.remove(path.toAbsolutePath().normalize());
    }

    public synchronized void clear() { entries.clear(); }
    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
}