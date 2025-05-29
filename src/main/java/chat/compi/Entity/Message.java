package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List; // List 임포트 추가

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;
    private int messageId;
    private int roomId;
    private int senderId;
    private String senderNickname;
    private MessageType messageType;
    private String content;
    private LocalDateTime sentAt;
    private boolean isNotice;
    private int unreadCount; // 기존 미열람자 수 필드는 유지 (필요에 따라 사용)
    private List<User> readers; // 새로 추가된 필드: 메시지를 읽은 사용자 목록

    public Message(int roomId, int senderId, String senderNickname, MessageType messageType, String content, boolean isNotice) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.messageType = messageType;
        this.content = content;
        this.isNotice = isNotice;
        this.sentAt = LocalDateTime.now();
    }

    // DB에서 불러올 때 사용하는 생성자
    public Message(int messageId, int roomId, int senderId, String senderNickname, MessageType messageType, String content, LocalDateTime sentAt, boolean isNotice) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.messageType = messageType;
        this.content = content;
        this.sentAt = sentAt;
        this.isNotice = isNotice;
    }

    // Getters and Setters (기존 필드들)
    public int getMessageId() { return messageId; }
    public void setMessageId(int messageId) { this.messageId = messageId; }
    public int getRoomId() { return roomId; }
    public void setRoomId(int roomId) { this.roomId = roomId; }
    public int getSenderId() { return senderId; }
    public void setSenderId(int senderId) { this.senderId = senderId; }
    public String getSenderNickname() { return senderNickname; }
    public void setSenderNickname(String senderNickname) { this.senderNickname = senderNickname; }
    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public boolean isNotice() { return isNotice; }
    public void setNotice(boolean notice) { isNotice = notice; }
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    // 새로 추가된 readers 필드의 Getter and Setter
    public List<User> getReaders() {
        return readers;
    }

    public void setReaders(List<User> readers) {
        this.readers = readers;
    }

    @Override
    public String toString() {
        return "[" + sentAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + "] " +
                (senderNickname != null ? senderNickname : "알 수 없음") + ": " + content;
    }
}