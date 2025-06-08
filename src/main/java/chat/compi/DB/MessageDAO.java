// MessageDAO.java
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
        String sql = "INSERT INTO messages (room_id, sender_id, message_type, content, is_notice, notice_expiry_time) VALUES (?, ?, ?, ?, ?, ?)";
        String updateRoomLastMessageSql = "UPDATE chat_rooms SET last_message_at = ? WHERE room_id = ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false); // 트랜잭션 시작

            try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setInt(1, message.getRoomId());
                pstmt.setInt(2, message.getSenderId());
                pstmt.setString(3, message.getMessageType().name());
                pstmt.setString(4, message.getContent());
                pstmt.setBoolean(5, message.isNotice());
                if (message.getNoticeExpiryTime() != null) {
                    pstmt.setTimestamp(6, Timestamp.valueOf(message.getNoticeExpiryTime()));
                } else {
                    pstmt.setNull(6, Types.TIMESTAMP);
                }
                int affectedRows = pstmt.executeUpdate();

                if (affectedRows > 0) {
                    ResultSet rs = pstmt.getGeneratedKeys();
                    if (rs.next()) {
                        message.setMessageId(rs.getInt(1)); // 생성된 메시지 ID 설정
                    }
                } else {
                    conn.rollback();
                    return null;
                }
            }

            try (PreparedStatement updatePstmt = conn.prepareStatement(updateRoomLastMessageSql)) {
                updatePstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
                updatePstmt.setInt(2, message.getRoomId());
                updatePstmt.executeUpdate();
            }

            conn.commit();
            return message;
        } catch (SQLException e) {
            System.err.println("Error saving message and updating chat room last_message_at: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 특정 채팅방의 메시지 조회 (이전 대화 열람 포함)
     * @param roomId 채팅방 ID
     * @return 메시지 리스트
     */
    public List<Message> getMessagesInRoom(int roomId) {
        List<Message> messages = new ArrayList<>();
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice, m.notice_expiry_time " + // 컬럼명 수정
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
                Timestamp expiryTs = rs.getTimestamp("notice_expiry_time"); // 컬럼명 수정
                LocalDateTime noticeExpiryTime = (expiryTs != null) ? expiryTs.toLocalDateTime() : null;

                messages.add(new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice, noticeExpiryTime));
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
    public int markMessageAsReadStatus(int messageId, int userId) {
        String sql = "INSERT IGNORE INTO message_reads (message_id, user_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setInt(2, userId);
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                return 1;
            } else {
                return 0;
            }
        } catch (SQLException e) {
            System.err.println("Error marking message as read: " + e.getMessage());
            e.printStackTrace();
            return -1;
        }
    }

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
        // notice_expiry_time이 현재 시간보다 미래이거나 NULL인 공지만 조회
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice, m.notice_expiry_time " + // 컬럼명 수정
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.is_notice = TRUE AND m.room_id = ? AND (m.notice_expiry_time IS NULL OR m.notice_expiry_time > NOW()) ORDER BY m.sent_at DESC";
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
                    Timestamp expiryTs = rs.getTimestamp("notice_expiry_time"); // 컬럼명 수정
                    LocalDateTime noticeExpiryTime = (expiryTs != null) ? expiryTs.toLocalDateTime() : null;

                    notices.add(new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice, noticeExpiryTime));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting notice messages for room " + roomId + ": " + e.getMessage());
        }
        return notices;
    }

    public Message getMessageById(int messageId) {
        // notice_expiry_time 컬럼 조회에 추가
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice, m.notice_expiry_time " + // 컬럼명 수정
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
                Timestamp expiryTs = rs.getTimestamp("notice_expiry_time"); // 컬럼명 수정
                LocalDateTime noticeExpiryTime = (expiryTs != null) ? expiryTs.toLocalDateTime() : null;

                return new Message(messageId, rId, senderId, senderNickname, messageType, content, sentAt, isNotice, noticeExpiryTime);
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
     * 특정 메시지의 공지 상태 및 만료 시간을 업데이트합니다.
     * @param messageId 메시지 ID
     * @param isNotice 공지 여부 (true: 공지로 설정, false: 공지 해제)
     * @param expiryTime 공지 만료 시간 (isNotice가 true일 때만 유효, null이면 만료 시간 없음)
     * @return 업데이트 성공 여부
     */
    public boolean updateMessageNoticeStatus(int messageId, boolean isNotice, LocalDateTime expiryTime) {
        String sql = "UPDATE messages SET is_notice = ?, notice_expiry_time = ? WHERE message_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setBoolean(1, isNotice);
            if (isNotice && expiryTime != null) {
                pstmt.setTimestamp(2, Timestamp.valueOf(expiryTime));
            } else {
                pstmt.setNull(2, Types.TIMESTAMP); // 공지 해제 시 또는 만료 시간 지정 안 할 시 NULL
            }
            pstmt.setInt(3, messageId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating message notice status for message ID " + messageId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * 만료된 공지 메시지의 is_notice 상태를 해제합니다.
     * 이 메서드는 주기적으로 호출되어야 합니다.
     * @return 상태가 변경된 메시지 수
     */
    public int clearExpiredNotices() {
        String sql = "UPDATE messages SET is_notice = FALSE, notice_expiry_time = NULL WHERE is_notice = TRUE AND notice_expiry_time IS NOT NULL AND notice_expiry_time <= NOW()";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                System.out.println(affectedRows + " expired notices cleared.");
            }
            return affectedRows;
        } catch (SQLException e) {
            System.err.println("Error clearing expired notices: " + e.getMessage());
            return 0;
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
        String sql = "SELECT m.message_id, m.room_id, m.sender_id, u.nickname as sender_nickname, m.message_type, m.content, m.sent_at, m.is_notice, m.notice_expiry_time " + // 컬럼명 수정
                "FROM messages m " +
                "JOIN users u ON m.sender_id = u.user_id " +
                "LEFT JOIN message_reads mr ON m.message_id = mr.message_id AND mr.user_id = ? " +
                "WHERE m.message_type = 'SYSTEM' AND mr.user_id IS NULL AND m.sender_id = ? " +
                "ORDER BY m.sent_at ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, systemUserId);
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
                    Timestamp expiryTs = rs.getTimestamp("notice_expiry_time"); // 컬럼명 수정
                    LocalDateTime noticeExpiryTime = (expiryTs != null) ? expiryTs.toLocalDateTime() : null;
                    unreadSystemMessages.add(new Message(messageId, roomId, senderId, senderNickname, messageType, content, sentAt, isNotice, noticeExpiryTime));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting unread system messages for user " + userId + ": " + e.getMessage());
        }
        return unreadSystemMessages;
    }
}