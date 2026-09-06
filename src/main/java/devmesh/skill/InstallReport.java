package devmesh.skill;

/**
 *
 */
public record InstallReport(
        String skillName,
        String targetDir,
        int fileCount,
        long totalBytes
) {}
