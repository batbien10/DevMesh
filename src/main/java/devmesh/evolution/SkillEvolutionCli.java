package devmesh.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline CLI for the verified Skill evolution lifecycle. */
public final class SkillEvolutionCli {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private SkillEvolutionCli() {}

    /** Returns null when the argument list is not an evolution command. */
    public static Integer tryRun(String[] args) {
        boolean relevant = false;
        for (String arg : args) {
            if (arg.startsWith("--skill-evolution-")) {
                relevant = true;
                break;
            }
        }
        if (!relevant) return null;

        try {
            Map<String, String> values = new LinkedHashMap<>();
            boolean list = false;
            String output = "text";
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--skill-evolution-list")) {
                    list = true;
                } else if (arg.equals("--output-format") && i + 1 < args.length) {
                    output = args[++i];
                } else if (arg.startsWith("--output-format=")) {
                    output = arg.substring("--output-format=".length());
                } else if (arg.startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    values.put(arg, args[++i]);
                } else if (arg.contains("=")) {
                    int split = arg.indexOf('=');
                    values.put(arg.substring(0, split), arg.substring(split + 1));
                }
            }

            Path workspace = Path.of(values.getOrDefault("--workspace", System.getProperty("user.dir")));
            var service = new SkillEvolutionService(workspace);
            if (list) {
                var views = service.store().listViews();
                if ("json".equals(output)) {
                    System.out.println(MAPPER.writeValueAsString(views));
                } else {
                    if (views.isEmpty()) {
                        System.out.println("No Skill evolution candidates.");
                    } else {
                        for (var view : views) {
                            System.out.printf("%s  %-11s  %s v%d  evidence=%d%n",
                                    view.candidate().id(), view.status(), view.candidate().name(),
                                    view.candidate().version(), view.evidenceCount());
                        }
                    }
                }
                return 0;
            }

            if (values.containsKey("--skill-evolution-propose")) {
                var candidate = service.propose(SkillEvolutionService.loadProposal(
                        Path.of(values.get("--skill-evolution-propose"))));
                if ("json".equals(output)) System.out.println(MAPPER.writeValueAsString(candidate));
                else System.out.printf("Candidate %s created in QUARANTINED state.%n", candidate.id());
                return 0;
            }

            if (values.containsKey("--skill-evolution-evaluate")) {
                require(values, "--baseline-traces", "--candidate-traces",
                        "--baseline-outcomes", "--candidate-outcomes", "--evolution-policy");
                var result = service.evaluate(values.get("--skill-evolution-evaluate"),
                        Path.of(values.get("--baseline-traces")),
                        Path.of(values.get("--candidate-traces")),
                        Path.of(values.get("--baseline-outcomes")),
                        Path.of(values.get("--candidate-outcomes")),
                        Path.of(values.get("--evolution-policy")));
                if ("json".equals(output)) System.out.println(MAPPER.writeValueAsString(result.toMap()));
                else System.out.print(result.renderText());
                return result.passed() ? 0 : 2;
            }

            if (values.containsKey("--skill-evolution-promote")) {
                Path target = service.store().promote(values.get("--skill-evolution-promote"));
                System.out.println("Promoted Skill to " + target);
                return 0;
            }

            if (values.containsKey("--skill-evolution-rollback")) {
                Path target = service.store().rollback(values.get("--skill-evolution-rollback"));
                System.out.println("Rolled back Skill at target " + target);
                return 0;
            }

            throw new IllegalArgumentException("No Skill evolution action was specified");
        } catch (Exception e) {
            System.err.println("Skill evolution command failed: " + e.getMessage());
            return 2;
        }
    }

    private static void require(Map<String, String> values, String... keys) {
        var missing = new ArrayList<String>();
        for (String key : keys) if (!values.containsKey(key)) missing.add(key);
        if (!missing.isEmpty()) throw new IllegalArgumentException("Missing options: " + String.join(", ", missing));
    }
}
