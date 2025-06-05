// TimelineEvent.java
package chat.compi.Entity;

import java.io.Serializable;
import java.time.LocalDateTime;

public class TimelineEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private int eventId;
    private int roomId;
    private int userId;
    private String senderNickname; // 변경: username 대신 닉네임 저장용
    private String command;
    private String description;
    private LocalDateTime eventTime;
    private String eventType;
    private String eventName;

    // 기존 생성자 (eventType, eventName 없이 호출될 경우 기본값 설정) - senderNickname 받도록 수정
    public TimelineEvent(int eventId, int roomId, int userId, String senderNickname, String command, String description, LocalDateTime eventTime) {
        this(eventId, roomId, userId, senderNickname, command, description, eventTime, "MESSAGE", null);
    }

    // 새로운 생성자 (eventType, eventName 포함) - senderNickname 받도록 수정
    public TimelineEvent(int eventId, int roomId, int userId, String senderNickname, String command, String description, LocalDateTime eventTime, String eventType, String eventName) {
        this.eventId = eventId;
        this.roomId = roomId;
        this.userId = userId;
        this.senderNickname = senderNickname; // 변경
        this.command = command;
        this.description = description;
        this.eventTime = eventTime;
        this.eventType = eventType;
        this.eventName = eventName;
    }

    // Getters (senderNickname의 Getter 추가 또는 수정)
    public int getEventId() {
        return eventId;
    }

    public int getRoomId() {
        return roomId;
    }

    public int getUserId() {
        return userId;
    }

    public String getSenderNickname() { // Getter 이름 변경 (getUsername -> getSenderNickname)
        return senderNickname;
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
                senderNickname + " " + command + ": " + description;
    }
}