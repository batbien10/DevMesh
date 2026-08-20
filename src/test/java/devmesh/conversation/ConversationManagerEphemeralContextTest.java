package devmesh.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConversationManagerEphemeralContextTest {

    @Test
    void namedContextIsReplaceableAndNotPersisted() {
        var conversation = new ConversationManager();
        conversation.addUserMessage("implement the feature");

        conversation.setEphemeralContext("plan-mode", "plan iteration 1");
        conversation.setEphemeralContext("plan-mode", "plan iteration 2");

        assertEquals(1, conversation.size());
        assertEquals(1, conversation.getMessages().size());
        assertEquals(2, conversation.getMessagesForModel().size());
        String envelope = conversation.getMessagesForModel().get(1).getContent();
        assertTrue(envelope.contains("plan iteration 2"));
        assertFalse(envelope.contains("plan iteration 1"));
    }

    @Test
    void removingLastSlotRestoresStableTranscript() {
        var conversation = new ConversationManager();
        conversation.addUserMessage("hello");
        conversation.setEphemeralContext("tools", "ToolA");
        conversation.removeEphemeralContext("tools");

        assertEquals(conversation.getMessages(), conversation.getMessagesForModel());
        assertTrue(conversation.getEphemeralContext().isEmpty());
    }
}
