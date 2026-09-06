package devmesh.task;

import java.util.List;

public record RepairDiagnosis(FailureType type, String rootCause, List<String> relevantDiagnostics,
                              List<String> suggestedVerification) {
    public RepairDiagnosis {
        relevantDiagnostics = List.copyOf(relevantDiagnostics == null ? List.of() : relevantDiagnostics);
        suggestedVerification = List.copyOf(suggestedVerification == null ? List.of() : suggestedVerification);
    }
}