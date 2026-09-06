package devmesh.repository;

public record RepositorySymbol(String name, String kind, String path, int line,
                               String packageName, SymbolConfidence confidence) {}