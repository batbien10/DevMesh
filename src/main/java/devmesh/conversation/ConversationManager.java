package devmesh.conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConversationManager {

    private final List<Message> history = new ArrayList<>();

    /**
     * Named, replaceable context that is sent to the model but never persisted
     * into the conversation transcript. This keeps loop-level guidance (for
     * example deferred-tool discovery and plan-mode state) idempotent instead
     * of appending a new reminder on every agent iteration.
     */
    private final Map<String, String> ephemeralContext = new LinkedHashMap<>();

    private boolean ltmInjected = false;

    public void addUserMessage(String content) {
        history.add(new Message("user", content));
    }

    public void addAssistantMessage(String content) {
        history.add(new Message("assistant", content));
    }

    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addToolResultsMessage(List<ToolResultBlock> results) {
        var msg = new Message("user", "");
        msg.setToolResults(results);
        history.add(msg);
    }

    public void injectLongTermMemory(String instructions, String memories) {
        if (ltmInjected) return;
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# devmeshMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        if (sections.isEmpty()) return;
        sections.add("# currentDate\nToday's date is " + java.time.LocalDate.now() + ".");
        String body = String.join("\n\n", sections);
        String wrapped = "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n" +
            body +
            "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>";
        history.add(0, new Message("user", wrapped));
        ltmInjected = true;
    }

    public void resetLtmInjected() {
        ltmInjected = false;
    }

    public void addSystemReminder(String content) {
        history.add(new Message("user", "<system-reminder>\n" + content + "\n</system-reminder>"));
    }

    public void setEphemeralContext(String key, String content) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ephemeral context key must not be blank");
        }
        if (content == null || content.isBlank()) {
            ephemeralContext.remove(key);
        } else {
            ephemeralContext.put(key, content);
        }
    }

    public void removeEphemeralContext(String key) {
        ephemeralContext.remove(key);
    }

    public Map<String, String> getEphemeralContext() {
        return Map.copyOf(ephemeralContext);
    }

    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    /**
     * Returns the request envelope used for inference. Ephemeral slots are
     * rendered as one trailing reminder so replacing a slot does not mutate
     * session history or invalidate the stable transcript prefix.
     */
    public List<Message> getMessagesForModel() {
        if (ephemeralContext.isEmpty()) {
            return getMessages();
        }
        var request = new ArrayList<Message>(history.size() + 1);
        request.addAll(history);
        var body = new StringBuilder("<system-reminder>\n# Ephemeral context\n");
        for (var entry : ephemeralContext.entrySet()) {
            body.append("\n## ").append(entry.getKey()).append('\n')
                    .append(entry.getValue()).append('\n');
        }
        body.append("</system-reminder>");
        request.add(new Message("user", body.toString()));
        return List.copyOf(request);
    }

    public List<Message> getMessagesMutable() {
        return history;
    }

    public int size() {
        return history.size();
    }

    public void truncateTo(int index) {
        if (index >= 0 && index < history.size()) {
            history.subList(index, history.size()).clear();
        }
    }

}
