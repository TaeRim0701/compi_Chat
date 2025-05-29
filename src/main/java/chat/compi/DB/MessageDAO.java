package chat.compi.DB;


import chat.compi.Entity.Message;
import chat.compi.Entity.MessageType;
import chat.compi.Entity.User;
import chat.compi.Entity.UserStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MessageDAO {

    /**
     * 메시지 저장
     * @param message 저장할 Message 객체
     * @return 저장 성공 여부
     */
    public Message saveMessage(Message message) {
        String sql = "INSERT INTO messages (room_id, sender_id, message_type, content, is_notice) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, message.getRoomId());
            pstmt.setInt(2, message.getSenderId());
            pstmt.setString(3, message.getMessageType().name());
            pstmt.setString(4, message.getContent());
            pstmt.setBoolean(5, message.isNotice());
            int affectedRows = pstmt.executeUpdate();

            if (affectedRows > 0) {
                ResultSet rs = pstmt.getGeneratedKeys();
                if (rs.next()) {
                    message.setMessageId(rs.getInt(1)); // 생성된 메시지 ID 설정
                    return message;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error saving message: " + e.getMessage());
        }
        return null;
    }

    /**
     * 특정 채팅방의 메시지 조회 (이전 대화 열람 포함)
     * @param roomId 채팅방 ID
     * @return 메시지 리스트
     */
    public List<Message> getMessagesInRoom(int roomId) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice " +
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.room_id = ? ORDER BY m.sent_at ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int messageId = rs.getInt("message_id");
                int rId = rs.getInt("room_id");
                int senderId = rs.getInt("sender_id");
                String senderNickname = rs.getString("sender_nickname");
                MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
                String content = rs.getString("content");
                LocalDateTime sentAt = rs.getTimestamp("sent_at").toLocalDateTime();
                boolean isNotice = rs.getBoolean("is_notice");
                messages.add(new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice));
            }
        } catch (SQLException e) {
            System.err.println("Error getting messages in room: " + e.getMessage());
        }
        return messages;
    }

    /**
     * 메시지 읽음 처리
     * @param messageId 메시지 ID
     * @param userId 읽은 사용자 ID
     * @return 성공 여부
     */
    public boolean markMessageAsRead(int messageId, int userId) {
        String sql = "INSERT IGNORE INTO message_reads (message_id, user_id) VALUES (?, ?)"; // IGNORE를 사용하여 중복 방지
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setInt(2, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error marking message as read: " + e.getMessage());
            return false;
        }
    }

    /**
     * 특정 메시지를 읽은 사용자 수 조회
     * @param messageId 메시지 ID
     * @return 읽은 사용자 수
     */
    public int getReadCountForMessage(int messageId) {
        String sql = "SELECT COUNT(*) FROM message_reads WHERE message_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error getting read count for message: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 특정 채팅방에서 아직 읽지 않은 메시지 수 조회
     * @param roomId 채팅방 ID
     * @param userId 사용자 ID
     * @return 안 읽은 메시지 수
     */
    public int getUnreadMessageCount(int roomId, int userId) {
        String sql = "SELECT COUNT(DISTINCT m.message_id) FROM messages m " +
                "LEFT JOIN message_reads mr ON m.message_id = mr.message_id AND mr.user_id = ? " +
                "WHERE m.room_id = ? AND mr.message_id IS NULL";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, roomId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread message count: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 특정 채팅방의 공지 메시지 조회
     * @param roomId 공지 메시지를 조회할 채팅방 ID
     * @return 공지 메시지 리스트
     */
    public List<Message> getNoticeMessagesInRoom(int roomId) { // 메서드 이름 변경 및 roomId 파라미터 추가
        List<Message> notices = new ArrayList<>();
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice " +
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.is_notice = TRUE AND m.room_id = ? ORDER BY m.sent_at DESC"; // room_id 조건 추가
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId); // roomId 값 설정
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int messageId = rs.getInt("message_id");
                    int rId = rs.getInt("room_id");
                    int senderId = rs.getInt("sender_id");
                    String senderNickname = rs.getString("sender_nickname");
                    MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
                    String content = rs.getString("content");
                    LocalDateTime sentAt = rs.getTimestamp("sent_at").toLocalDateTime();
                    boolean isNotice = rs.getBoolean("is_notice");
                    notices.add(new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting notice messages for room " + roomId + ": " + e.getMessage());
        }
        return notices;
    }

    public Message getMessageById(int messageId) {
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice " +
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.message_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int rId = rs.getInt("room_id");
                int senderId = rs.getInt("sender_id");
                String senderNickname = rs.getString("sender_nickname");
                MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
                String content = rs.getString("content");
                LocalDateTime sentAt = rs.getTimestamp("sent_at").toLocalDateTime();
                boolean isNotice = rs.getBoolean("is_notice");
                return new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice);
            }
        } catch (SQLException e) {
            System.err.println("Error getting message by ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * 특정 메시지를 특정 사용자가 읽었는지 여부 확인
     * @param messageId 메시지 ID
     * @param userId 사용자 ID
     * @return 읽었으면 true, 아니면 false
     */
    public boolean isMessageReadByUser(int messageId, int userId) {
        String sql = "SELECT COUNT(*) FROM message_reads WHERE message_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setInt(2, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            System.err.println("Error checking if message is read by user: " + e.getMessage());
        }
        return false;
    }

    /**
     * 특정 메시지를 읽은 사용자 목록 조회
     * @param messageId 메시지 ID
     * @return 읽은 사용자 User 객체 리스트
     */
    public List<User> getReadersForMessage(int messageId) {
        List<User> readers = new ArrayList<>();
        String sql = "SELECT u.user_id, u.username, u.nickname, u.status " +
                "FROM message_reads mr JOIN users u ON mr.user_id = u.user_id " +
                "WHERE mr.message_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int userId = rs.getInt("user_id");
                    String username = rs.getString("username");
                    String nickname = rs.getString("nickname");
                    UserStatus status = UserStatus.valueOf(rs.getString("status"));
                    readers.add(new User(userId, username, nickname, status));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting readers for message " + messageId + ": " + e.getMessage());
        }
        return readers;
    }
}