package devmesh.repository;

import java.util.List;

public record RepositoryModule(String name, String path, List<String> dependencies) {
    public RepositoryModule {
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    }
}