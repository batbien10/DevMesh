package devmesh.tui;

/** A selectable item displayed by a popup. */
public record PopupItem(String value, String description) {
    public PopupItem {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Popup item value must not be blank");
        }
        description = description == null ? "" : description;
    }
}