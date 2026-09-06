package devmesh.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PopupControllerTest {
    private static PopupController popup() {
        return new PopupController(List.of(
                new PopupItem("help", "Show help"),
                new PopupItem("model", "Select model"),
                new PopupItem("provider", "Select provider"),
                new PopupItem("session", "Manage sessions")));
    }

    @Test
    void ranksExactAndPrefixBeforeFuzzyMatches() {
        var popup = popup();
        popup.setQuery("mo");
        assertEquals("model", popup.filteredItems().get(0).value());
        popup.setQuery("hlp");
        assertEquals("help", popup.selectedItem().value());
    }

    @Test
    void supportsSelectionMovementAndVisibleWindow() {
        var popup = popup();
        popup.move(2, 2);
        assertEquals("provider", popup.selectedItem().value());
        assertEquals(2, popup.visibleItems(2).size());
        popup.move(-10, 2);
        assertEquals("help", popup.selectedItem().value());
    }

    @Test
    void reportsEmptyResultsAndClampedResize() {
        var popup = popup();
        popup.setQuery("does-not-exist");
        assertTrue(popup.isEmpty());
        var size = popup.layout(8, 2);
        assertTrue(size.width() >= 12);
        assertTrue(size.height() >= 3);
    }
}