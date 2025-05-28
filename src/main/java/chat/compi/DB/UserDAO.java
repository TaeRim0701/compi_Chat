package chat.compi.DB;

import chat.compi.Entity.User;
import chat.compi.Entity.UserStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class UserDAO {

    /**
     * 사용자 등록 (회원가입)
     * @param username 사용자 이름
     * @param password 비밀번호 (평문)
     * @param nickname 닉네임
     * @return 등록 성공 여부
     */
    public boolean registerUser(String username, String password, String nickname) {
        // String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt()); // 비밀번호 해싱 부분 제거
        String sql = "INSERT INTO users (username, password, nickname, status) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, password); // 평문 비밀번호 저장
            pstmt.setString(3, nickname);
            pstmt.setString(4, UserStatus.OFFLINE.name());
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) { // Duplicate entry for key 'username'
                System.err.println("Registration failed: Username '" + username + "' already exists.");
            } else {
                System.err.println("Error registering user: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * 사용자 로그인
     * @param username 사용자 이름
     * @param password 비밀번호 (평문)
     * @return 로그인 성공 시 User 객체, 실패 시 null
     */
    public User loginUser(String username, String password) {
        String sql = "SELECT user_id, username, password, nickname, status FROM users WHERE username = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String storedPassword = rs.getString("password"); // 저장된 평문 비밀번호 가져오기
                if (password.equals(storedPassword)) { // 평문 비밀번호 직접 비교
                    int userId = rs.getInt("user_id");
                    String userNickname = rs.getString("nickname");
                    UserStatus status = UserStatus.valueOf(rs.getString("status"));
                    updateUserStatus(userId, UserStatus.ONLINE); // 로그인 시 상태 ONLINE으로 업데이트
                    return new User(userId, username, userNickname, UserStatus.ONLINE);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error logging in user: " + e.getMessage());
        }
        return null;
    }

    /**
     * 사용자 상태 업데이트
     * @param userId 사용자 ID
     * @param status 변경할 상태
     * @return 업데이트 성공 여부
     */
    public boolean updateUserStatus(int userId, UserStatus status) {
        String sql = "UPDATE users SET status = ?, last_login_time = ? WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.name());
            pstmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setInt(3, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating user status: " + e.getMessage());
            return false;
        }
    }

    /**
     * 사용자 ID로 사용자 정보 조회
     * @param userId 사용자 ID
     * @return User 객체 또는 null
     */
    public User getUserByUserId(int userId) {
        String sql = "SELECT user_id, username, nickname, status, last_login_time FROM users WHERE user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String username = rs.getString("username");
                String nickname = rs.getString("nickname");
                UserStatus status = UserStatus.valueOf(rs.getString("status"));
                Timestamp lastLoginTs = rs.getTimestamp("last_login_time");
                LocalDateTime lastLoginTime = (lastLoginTs != null) ? lastLoginTs.toLocalDateTime() : null;
                return new User(userId, username, nickname, status, lastLoginTime);
            }
        } catch (SQLException e) {
            System.err.println("Error getting user by ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * 사용자 이름으로 사용자 정보 조회
     * @param username 사용자 이름
     * @return User 객체 또는 null
     */
    public User getUserByUsername(String username) {
        String sql = "SELECT user_id, username, nickname, status, last_login_time FROM users WHERE username = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int userId = rs.getInt("user_id");
                String nickname = rs.getString("nickname");
                UserStatus status = UserStatus.valueOf(rs.getString("status"));
                Timestamp lastLoginTs = rs.getTimestamp("last_login_time");
                LocalDateTime lastLoginTime = (lastLoginTs != null) ? lastLoginTs.toLocalDateTime() : null;
                return new User(userId, username, nickname, status, lastLoginTime);
            }
        } catch (SQLException e) {
            System.err.println("Error getting user by username: " + e.getMessage());
        }
        return null;
    }

    /**
     * 모든 사용자 정보 조회 (친구 추가용)
     * @return 모든 사용자 리스트
     */
    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        String sql = "SELECT user_id, username, nickname, status, last_login_time FROM users";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                int userId = rs.getInt("user_id");
                String username = rs.getString("username");
                String nickname = rs.getString("nickname");
                UserStatus status = UserStatus.valueOf(rs.getString("status"));
                Timestamp lastLoginTs = rs.getTimestamp("last_login_time");
                LocalDateTime lastLoginTime = (lastLoginTs != null) ? lastLoginTs.toLocalDateTime() : null;
                users.add(new User(userId, username, nickname, status, lastLoginTime));
            }
        } catch (SQLException e) {
            System.err.println("Error getting all users: " + e.getMessage());
        }
        return users;
    }

    /**
     * 친구 추가
     * @param userId 친구를 추가하는 사용자 ID
     * @param friendId 추가될 친구 ID
     * @return 성공 여부
     */
    public boolean addFriend(int userId, int friendId) {
        // 이미 친구인지 확인
        if (isFriend(userId, friendId)) {
            System.out.println("Already friends.");
            return false;
        }

        String sql = "INSERT INTO friends (user_id, friend_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false); // 트랜잭션 시작

            pstmt.setInt(1, userId);
            pstmt.setInt(2, friendId);
            pstmt.executeUpdate();

            // 상호 친구 관계를 위해 반대 방향도 추가
            pstmt.setInt(1, friendId);
            pstmt.setInt(2, userId);
            pstmt.executeUpdate();

            conn.commit(); // 커밋
            return true;
        } catch (SQLException e) {
            System.err.println("Error adding friend: " + e.getMessage());
            return false;
        }
    }

    /**
     * 친구 여부 확인
     * @param userId
     * @param friendId
     * @return
     */
    public boolean isFriend(int userId, int friendId) {
        String sql = "SELECT COUNT(*) FROM friends WHERE user_id = ? AND friend_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, friendId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error checking friend status: " + e.getMessage());
        }
        return false;
    }

    /**
     * 사용자 친구 목록 조회
     * @param userId 사용자 ID
     * @return 친구 User 객체 리스트
     */
    public List<User> getFriends(int userId) {
        List<User> friends = new ArrayList<>();
        String sql = "SELECT u.user_id, u.username, u.nickname, u.status FROM friends f JOIN users u ON f.friend_id = u.user_id WHERE f.user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int friendId = rs.getInt("user_id");
                String username = rs.getString("username");
                String nickname = rs.getString("nickname");
                UserStatus status = UserStatus.valueOf(rs.getString("status"));
                friends.add(new User(friendId, username, nickname, status));
            }
        } catch (SQLException e) {
            System.err.println("Error getting friends: " + e.getMessage());
        }
        return friends;
    }
}