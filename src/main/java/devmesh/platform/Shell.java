package devmesh.platform;

import java.util.List;

public record Shell(String name, String executable, List<String> prefixArguments) {
    public Shell {
        prefixArguments = List.copyOf(prefixArguments == null ? List.of() : prefixArguments);
    }

    public List<String> command(String script) {
        var result = new java.util.ArrayList<String>(prefixArguments);
        result.add(script);
        return List.copyOf(result);
    }
}