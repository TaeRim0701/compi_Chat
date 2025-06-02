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
            // senderId가 0(시스템 사용자)인 경우에도 문제 없이 저장되도록 함
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
    // MessageDAO.java
    public int markMessageAsReadStatus(int messageId, int userId) {
        String sql = "INSERT IGNORE INTO message_reads (message_id, user_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setInt(2, userId);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                return 1; // 성공적으로 삽입됨
            } else {
                // affectedRows가 0인 경우: INSERT IGNORE에 의해 중복 키 삽입 무시됨
                return 0; // 이미 존재하거나 삽입되지 않음
            }
        } catch (SQLException e) {
            System.err.println("Error marking message as read: " + e.getMessage());
            e.printStackTrace(); // 여전히 상세 에러 로그를 남깁니다.
            return -1; // DB 오류 발생
        }
    }

    // 기존 markMessageAsRead는 이 새로운 메서드를 기반으로 수정하거나 제거
    public boolean markMessageAsRead(int messageId, int userId) {
        return markMessageAsReadStatus(messageId, userId) > 0;
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
        String sql = "SELECT COUNT(*) FROM messages m LEFT JOIN message_reads mr ON m.message_id = mr.message_id WHERE m.room_id = ? AND mr.user_id IS NULL AND m.sender_id != ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            pstmt.setInt(2, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread message count: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 특정 채팅방의 공지 메시지 조회
     * @param roomId 공지 메시지를 조회할 채팅방 ID
     * @return 공지 메시지 리스트
     */
    public List<Message> getNoticeMessagesInRoom(int roomId) {
        List<Message> notices = new ArrayList<>();
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice " +
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.is_notice = TRUE AND m.room_id = ? ORDER BY m.sent_at DESC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
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

    /**
     * 특정 메시지의 공지 상태를 업데이트합니다.
     * @param messageId 메시지 ID
     * @param isNotice 공지 여부 (true: 공지로 설정, false: 공지 해제)
     * @return 업데이트 성공 여부
     */
    public boolean updateMessageNoticeStatus(int messageId, boolean isNotice) {
        String sql = "UPDATE messages SET is_notice = ? WHERE message_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setBoolean(1, isNotice);
            pstmt.setInt(2, messageId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating message notice status for message ID " + messageId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 특정 사용자가 아직 읽지 않은 시스템 메시지 조회
     * (시스템 메시지는 sender_id가 ChatServer의 systemUserId로 가정)
     * @param userId 사용자 ID
     * @param systemUserId ChatServer의 시스템 사용자 ID
     * @return 미열람 시스템 메시지 리스트
     */
    public List<Message> getUnreadSystemMessagesForUser(int userId, int systemUserId) {
        List<Message> unreadSystemMessages = new ArrayList<>();
        // m.sender_id = ? (systemUserId) : 시스템이 보낸 메시지
        // mr.user_id IS NULL : 해당 사용자가 읽지 않은 메시지
        // mr.message_id IS NULL : 해당 메시지가 message_reads에 전혀 없는 경우 (새로운 메시지)
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice " +
                "FROM messages m " +
                "JOIN users u ON m.sender_id = u.user_id " +
                "LEFT JOIN message_reads mr ON m.message_id = mr.message_id AND mr.user_id = ? " + // 해당 사용자가 읽었는지
                "WHERE m.message_type = 'SYSTEM' AND mr.user_id IS NULL " + // 시스템 메시지이고, 해당 사용자가 읽지 않은 경우
                "ORDER BY m.sent_at ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int messageId = rs.getInt("message_id");
                    int roomId = rs.getInt("room_id");
                    int senderId = rs.getInt("sender_id");
                    String senderNickname = rs.getString("sender_nickname");
                    MessageType messageType = MessageType.valueOf(rs.getString("message_type"));
                    String content = rs.getString("content");
                    LocalDateTime sentAt = rs.getTimestamp("sent_at").toLocalDateTime();
                    boolean isNotice = rs.getBoolean("is_notice");
                    unreadSystemMessages.add(new Message(messageId, roomId, senderId, senderNickname, messageType, content, sentAt, isNotice));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread system messages for user " + userId + ": " + e.getMessage());
        }
        return unreadSystemMessages;
    }
}

