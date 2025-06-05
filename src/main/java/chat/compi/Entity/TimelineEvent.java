package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class TimelineEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private int eventId;
    private int roomId;
    private int userId;
    private String username; // 이벤트 발생 사용자 이름
    private String command;
    private String description;
    private LocalDateTime eventTime;
    private String eventType;
    private String eventName;

    // 기존 생성자 (eventType, eventName 없이 호출될 경우 기본값 설정)
    public TimelineEvent(int eventId, int roomId, int userId, String username, String command, String description, LocalDateTime eventTime) {
        this(eventId, roomId, userId, username, command, description, eventTime, "MESSAGE", null); // 기본값 설정
    }

    // 새로운 생성자 (eventType, eventName 포함)
    public TimelineEvent(int eventId, int roomId, int userId, String username, String command, String description, LocalDateTime eventTime, String eventType, String eventName) {
        this.eventId = eventId;
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.command = command;
        this.description = description;
        this.eventTime = eventTime;
        this.eventType = eventType;
        this.eventName = eventName;
    }

    // Getters (모두 추가 또는 확인)
    public int getEventId() {
        return eventId;
    }

    public int getRoomId() {
        return roomId;
    }

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getCommand() {
        return command;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventName() {
        return eventName;
    }

    @Override
    public String toString() {
        return "[" + eventTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "] " +
                username + " " + command + ": " + description;
    }
}