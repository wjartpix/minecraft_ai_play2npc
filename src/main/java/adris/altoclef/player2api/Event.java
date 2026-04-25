package adris.altoclef.player2api;

public sealed interface Event // tagged union basically of the below events
        permits Event.UserMessage, Event.CharacterMessage, Event.InfoMessage {
    String message();

    public String getConversationHistoryString();

    default EventPriority getPriority() {
        return EventPriority.NORMAL;
    }

    enum EventPriority {
        LOW, NORMAL, HIGH, CRITICAL
    }

    public record UserMessage(String message, String userName) implements Event {
        public String getConversationHistoryString() {
            return String.format("User Message: [%s]: %s", userName, message);
        }

        public String toString() {
            return String.format("UserMessage(userName='%s', message='%s')", userName, message);
        }

        @Override
        public EventPriority getPriority() {
            String lower = message.toLowerCase();
            if (lower.contains("救命") || lower.contains("快死了") || lower.contains("救我")
                    || lower.contains("危险") || lower.contains("攻击我") || lower.contains("help")
                    || lower.contains("dying") || lower.contains("save me")) {
                return EventPriority.CRITICAL;
            }
            if (lower.contains("过来") || lower.contains("快来") || lower.contains("在哪")
                    || lower.contains("快点") || lower.contains(" hurry") || lower.contains("come")) {
                return EventPriority.HIGH;
            }
            return EventPriority.NORMAL;
        }
    }

    public record InfoMessage(String message) implements Event {
        public String getConversationHistoryString() {
            return String.format("Info: %s", message);
        }

        public String toString() {
            return getConversationHistoryString();
        }
    }

    public record CharacterMessage(String message, String command, AgentConversationData sendingCharacterData)
            implements Event {
        public String getConversationHistoryString() {
            return String.format("Other AI Message: [%s]: %s", sendingCharacterData.getName(), message);
        }

        public String toString() {
            return String.format("CharacterMessage(name='%s', message='%s', command='%s')",
                    sendingCharacterData.getName(), message, command);
        }

    }
}
