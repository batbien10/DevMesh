package devmesh.repository;

public record RepositorySearchResult(String path, String symbol, int line,
                                     double relevance, SymbolConfidence confidence) {}