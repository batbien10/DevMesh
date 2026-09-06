package devmesh.tui.tea;


public class MouseMessage implements Message {

    public enum MouseButton {
        MouseButtonWheelUp,
        MouseButtonWheelDown,
        OTHER
    }

    private final MouseButton button;

    public MouseMessage(MouseButton button) {
        this.button = button;
    }

    public MouseButton getButton() {
        return button;
    }
}
