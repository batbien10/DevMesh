package devmesh.tui.tea;


public record KeyPressMessage(String key, char[] runes) implements Message {
    public String key() { return key; }
    public char[] runes() { return runes; }
}
