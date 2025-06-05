package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

public class ChatRoom implements Serializable {
    private static final long serialVersionUID = 1L;
    private int roomId;
    private String roomName;
    private LocalDateTime createdAt;
    private boolean isGroupChat;
    private List<User> participants;
    private int unreadMessageCount; // 추가

    public ChatRoom(int roomId, String roomName, LocalDateTime createdAt, boolean isGroupChat) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.createdAt = createdAt;
        this.isGroupChat = isGroupChat;
    }

    // Getters and Setters
    public int getRoomId() {
        return roomId;
    }

    public void setRoomId(int roomId) {
        this.roomId = roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isGroupChat() {
        return isGroupChat;
    }

    public void setGroupChat(boolean groupChat) {
        isGroupChat = groupChat;
    }

    public List<User> getParticipants() {
        return participants;
    }

    public void setParticipants(List<User> participants) {
        this.participants = participants;
    }

    // unreadMessageCount Getter and Setter 추가
    public int getUnreadMessageCount() {
        return unreadMessageCount;
    }

    public void setUnreadMessageCount(int unreadMessageCount) {
        this.unreadMessageCount = unreadMessageCount;
    }

    @Override
    public String toString() {
        return roomName;
    }
}