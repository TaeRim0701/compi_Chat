// Message.java
package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

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
    private int unreadCount;
    private List<User> readers;
    private LocalDateTime noticeExpiryTime; // 새로 추가: 공지 유효 기간

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

    // DB에서 불러올 때 사용하는 생성자 (noticeExpiryTime 포함)
    public Message(int messageId, int roomId, int senderId, String senderNickname, MessageType messageType, String content, LocalDateTime sentAt, boolean isNotice, LocalDateTime noticeExpiryTime) {
        this.messageId = messageId;
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.messageType = messageType;
        this.content = content;
        this.sentAt = sentAt;
        this.isNotice = isNotice;
        this.noticeExpiryTime = noticeExpiryTime; // 초기화
    }


    // Getters and Setters
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

    public List<User> getReaders() {
        return readers;
    }

    public void setReaders(List<User> readers) {
        this.readers = readers;
    }

    // 새로 추가된 noticeExpiryTime 필드의 Getter and Setter
    public LocalDateTime getNoticeExpiryTime() {
        return noticeExpiryTime;
    }

    public void setNoticeExpiryTime(LocalDateTime noticeExpiryTime) {
        this.noticeExpiryTime = noticeExpiryTime;
    }

    @Override
    public String toString() {
        return "[" + sentAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + "] " +
                (senderNickname != null ? senderNickname : "알 수 없음") + ": " + content;
    }
}