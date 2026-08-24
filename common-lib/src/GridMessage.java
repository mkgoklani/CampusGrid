import java.io.Serializable;
import java.util.UUID;

/**
 * CAMPUS GRID - PROTOCOL MESSAGE ENVELOPE
 * 
 * Standardized top-level wire envelope exchanged between Master and Agent nodes.
 */
public class GridMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String messageId;
    private final long timestamp;
    private final MessageType type;
    private final String senderId;
    private final Object payload;

    public GridMessage(MessageType type, String senderId, Object payload) {
        this(UUID.randomUUID().toString(), System.currentTimeMillis(), type, senderId, payload);
    }

    public GridMessage(String messageId, long timestamp, MessageType type, String senderId, Object payload) {
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.type = type;
        this.senderId = senderId;
        this.payload = payload;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public MessageType getType() {
        return type;
    }

    public String getSenderId() {
        return senderId;
    }

    public Object getPayload() {
        return payload;
    }

    @Override
    public String toString() {
        return String.format("GridMessage[ID=%s, Type=%s, Sender=%s, Time=%d, Payload=%s]",
            messageId, type, senderId, timestamp, payload != null ? payload.getClass().getSimpleName() : "null");
    }
}
