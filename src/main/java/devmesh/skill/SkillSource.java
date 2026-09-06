package devmesh.skill;

/**
 *
 */
public record SkillSource(
        String owner,
        String repo,
        String ref,
        String subpath,
        String name,
        String original
) {}
