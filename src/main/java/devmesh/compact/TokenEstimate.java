package devmesh.compact;

public record TokenEstimate(int tokens, Confidence confidence) {
    public enum Confidence { EXACT, ESTIMATED, UNKNOWN }
}