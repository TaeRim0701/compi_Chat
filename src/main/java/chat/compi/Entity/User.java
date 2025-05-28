package chat.compi.Entity;


import java.io.Serializable;
import java.time.LocalDateTime;

public class User implements Serializable {
    private static final long serialVersionUID = 1L;
    private int userId;
    private String username;
    private String nickname;
    private UserStatus status;
    private LocalDateTime lastLoginTime;

    public User(int userId, String username, String nickname, UserStatus status) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.status = status;
    }

    public User(int userId, String username, String nickname, UserStatus status, LocalDateTime lastLoginTime) {
        this.userId = userId;
        this.username = username;
        this.nickname = nickname;
        this.status = status;
        this.lastLoginTime = lastLoginTime;
    }

    // --- Getters and Setters (이 부분들을 반드시 추가/확인해주세요) ---
    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    @Override
    public String toString() {
        return nickname + " (" + username + ") - " + status;
    }
}