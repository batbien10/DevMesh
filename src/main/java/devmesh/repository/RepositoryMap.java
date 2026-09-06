package devmesh.repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record RepositoryMap(
        Path root,
        String projectType,
        List<String> languages,
        List<String> buildSystems,
        List<RepositoryModule> modules,
        List<String> sourceRoots,
        List<String> testRoots,
        List<String> configFiles,
        List<String> generatedPaths,
        List<String> importantFiles,
        List<String> entryPoints,
        List<RepositorySymbol> symbols,
        Map<String, List<String>> imports,
        Map<String, String> testRelationships,
        Map<String, String> gitState,
        long indexedFiles,
        long scanMillis
) {
    public RepositoryMap {
        languages = List.copyOf(languages == null ? List.of() : languages);
        buildSystems = List.copyOf(buildSystems == null ? List.of() : buildSystems);
        modules = List.copyOf(modules == null ? List.of() : modules);
        sourceRoots = List.copyOf(sourceRoots == null ? List.of() : sourceRoots);
        testRoots = List.copyOf(testRoots == null ? List.of() : testRoots);
        configFiles = List.copyOf(configFiles == null ? List.of() : configFiles);
        generatedPaths = List.copyOf(generatedPaths == null ? List.of() : generatedPaths);
        importantFiles = List.copyOf(importantFiles == null ? List.of() : importantFiles);
        entryPoints = List.copyOf(entryPoints == null ? List.of() : entryPoints);
        symbols = List.copyOf(symbols == null ? List.of() : symbols);
        imports = Map.copyOf(imports == null ? Map.of() : imports);
        testRelationships = Map.copyOf(testRelationships == null ? Map.of() : testRelationships);
        gitState = Map.copyOf(gitState == null ? Map.of() : gitState);
    }
}