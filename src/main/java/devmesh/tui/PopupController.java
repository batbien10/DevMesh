package devmesh.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reusable, rendering-independent controller for filtered keyboard popups.
 * It owns query, selection, scrolling, and the terminal-size calculation;
 * callers decide how the resulting state is rendered.
 */
public final class PopupController {
    private final List<PopupItem> items;
    private List<PopupItem> filtered = List.of();
    private String query = "";
    private int selected;
    private int firstVisible;

    public PopupController(List<PopupItem> items) {
        this.items = List.copyOf(items == null ? List.of() : items);
        refresh();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
        selected = 0;
        firstVisible = 0;
        refresh();
    }

    public String query() { return query; }
    public List<PopupItem> filteredItems() { return filtered; }
    public int selectedIndex() { return selected; }
    public boolean isEmpty() { return filtered.isEmpty(); }

    public PopupItem selectedItem() {
        return filtered.isEmpty() ? null : filtered.get(selected);
    }

    public void move(int delta, int visibleRows) {
        if (filtered.isEmpty()) return;
        selected = Math.max(0, Math.min(filtered.size() - 1, selected + delta));
        int rows = Math.max(1, visibleRows);
        if (selected < firstVisible) firstVisible = selected;
        if (selected >= firstVisible + rows) firstVisible = selected - rows + 1;
    }

    public List<PopupItem> visibleItems(int visibleRows) {
        int rows = Math.max(0, visibleRows);
        int end = Math.min(filtered.size(), firstVisible + rows);
        return List.copyOf(filtered.subList(Math.min(firstVisible, end), end));
    }

    /** Returns a stable popup size that fits inside the terminal. */
    public PopupSize layout(int terminalWidth, int terminalHeight) {
        int width = Math.max(12, Math.min(Math.max(12, terminalWidth - 2), contentWidth() + 4));
        int height = Math.max(3, Math.min(Math.max(3, terminalHeight - 2), filtered.size() + 2));
        return new PopupSize(width, height);
    }

    private void refresh() {
        String normalized = query.toLowerCase(Locale.ROOT).strip();
        var ranked = new ArrayList<RankedItem>();
        for (int index = 0; index < items.size(); index++) {
            String value = items.get(index).value().toLowerCase(Locale.ROOT);
            int score = score(value, normalized);
            if (score >= 0) ranked.add(new RankedItem(items.get(index), score, index));
        }
        ranked.sort(Comparator.comparingInt(RankedItem::score)
                .thenComparingInt(RankedItem::originalIndex));
        filtered = ranked.stream().map(RankedItem::item).toList();
        selected = Math.min(selected, Math.max(0, filtered.size() - 1));
        firstVisible = Math.min(firstVisible, selected);
    }

    private int contentWidth() {
        return items.stream().mapToInt(item -> item.value().length() + item.description().length() + 5)
                .max().orElse(10);
    }

    private static int score(String value, String query) {
        if (query.isEmpty()) return 0;
        if (value.equals(query)) return 1;
        if (value.startsWith(query)) return 2;
        int position = 0;
        int gaps = 0;
        for (int i = 0; i < query.length(); i++) {
            int found = value.indexOf(query.charAt(i), position);
            if (found < 0) return -1;
            gaps += found - position;
            position = found + 1;
        }
        return 10 + gaps;
    }

    private record RankedItem(PopupItem item, int score, int originalIndex) {}

    public record PopupSize(int width, int height) {}
}