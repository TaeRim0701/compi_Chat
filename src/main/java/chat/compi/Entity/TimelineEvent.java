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

    public TimelineEvent(int eventId, int roomId, int userId, String username, String command, String description, LocalDateTime eventTime) {
        this.eventId = eventId;
        this.roomId = roomId;
        this.userId = userId;
        this.username = username;
        this.command = command;
        this.description = description;
        this.eventTime = eventTime;
    }

    // Getters
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

    @Override
    public String toString() {
        return "[" + eventTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "] " +
                username + " " + command + ": " + description;
    }
}