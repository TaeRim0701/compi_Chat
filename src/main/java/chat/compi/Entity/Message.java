package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L; // 직렬화 버전 ID
    private int messageId;
    private int roomId;
    private int senderId;
    private String senderNickname; // 발신자 닉네임 추가
    private MessageType messageType;
    private String content;
    private LocalDateTime sentAt;
    private boolean isNotice;
    private int unreadCount; // 안 읽은 사용자 수

    public Message(int roomId, int senderId, String senderNickname, MessageType messageType, String content, boolean isNotice) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.messageType = messageType;
        this.content = content;
        this.isNotice = isNotice;
        this.sentAt = LocalDateTime.now(); // 메시지 생성 시 현재 시간으로 설정
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

    // Getters and Setters
    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public int getRoomId() {
        return roomId;
    }

    public void setRoomId(int roomId) {
        this.roomId = roomId;
    }

    public int getSenderId() {
        return senderId;
    }

    public void setSenderId(int senderId) {
        this.senderId = senderId;
    }

    public String getSenderNickname() {
        return senderNickname;
    }

    public void setSenderNickname(String senderNickname) {
        this.senderNickname = senderNickname;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public boolean isNotice() {
        return isNotice;
    }

    public void setNotice(boolean notice) {
        isNotice = notice;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    @Override
    public String toString() {
        return "[" + sentAt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + "] " +
                (senderNickname != null ? senderNickname : "알 수 없음") + ": " + content;
    }
}