package devmesh.task;

import java.util.Locale;

public final class TaskComplexityAnalyzer {
    private TaskComplexityAnalyzer() {}

    public static TaskComplexity analyze(String request) {
        String text = request == null ? "" : request.toLowerCase(Locale.ROOT);
        int signals = 0;
        for (String word : new String[]{"and", "with", "test", "tests", "documentation", "architecture", "api", "integration", "refactor"}) {
            if (text.contains(word)) signals++;
        }
        if (text.length() < 80 && signals <= 1) return TaskComplexity.SIMPLE;
        if (text.length() < 220 && signals <= 3) return TaskComplexity.MEDIUM;
        return TaskComplexity.COMPLEX;
    }
}