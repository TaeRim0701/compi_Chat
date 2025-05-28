package chat.compi.DB;


import chat.compi.Entity.ChatRoom;
import chat.compi.Entity.User;
import chat.compi.Entity.UserStatus;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatRoomDAO {

    /**
     * 채팅방 생성
     * @param roomName 방 이름 (1:1 채팅방일 경우 서버에서 자동으로 이름 지정)
     * @param isGroupChat 그룹 채팅 여부 (true: 그룹 채팅, false: 1:1 채팅)
     * @param creatorId 생성자 ID (필수)
     * @param participantIds 참여자 ID 목록 (생성자 포함)
     * @return 생성된 ChatRoom 객체 또는 null
     */
    public ChatRoom createChatRoom(String roomName, boolean isGroupChat, int creatorId, List<Integer> participantIds) {
        if (!isGroupChat && participantIds.size() != 2) {
            System.err.println("Error: Private chat room must have exactly 2 participants.");
            return null;
        }

        if (!isGroupChat && participantIds.size() == 2) {
            List<Integer> sortedParticipantIds = new ArrayList<>(participantIds);
            Collections.sort(sortedParticipantIds);
            int user1Id = sortedParticipantIds.get(0);
            int user2Id = sortedParticipantIds.get(1);

            ChatRoom existingRoom = getExistingPrivateChatRoom(user1Id, user2Id);
            if (existingRoom != null) {
                System.out.println("Existing private chat room found: " + existingRoom.getRoomName());
                return existingRoom;
            }
            UserDAO userDAO = new UserDAO();
            User user1 = userDAO.getUserByUserId(user1Id);
            User user2 = userDAO.getUserByUserId(user2Id);
            roomName = "DM: " + (user1 != null ? user1.getNickname() : user1Id) + ", " + (user2 != null ? user2.getNickname() : user2Id);
        } else if (isGroupChat && (roomName == null || roomName.trim().isEmpty())) {
            System.err.println("Error: Group chat room must have a name.");
            return null;
        }

        String roomSql = "INSERT INTO chat_rooms (room_name, is_group_chat) VALUES (?, ?)";
        String participantSql = "INSERT INTO room_participants (room_id, user_id) VALUES (?, ?)";
        ChatRoom newRoom = null; // 결과로 반환할 ChatRoom 객체

        Connection conn = null; // 트랜잭션 롤백을 위해 여기서 선언 (finally에서 닫힘)
        try {
            conn = DatabaseConnection.getConnection(); // 새로운 연결 획득
            conn.setAutoCommit(false); // 트랜잭션 시작

            try (PreparedStatement roomPstmt = conn.prepareStatement(roomSql, Statement.RETURN_GENERATED_KEYS)) {
                roomPstmt.setString(1, roomName);
                roomPstmt.setBoolean(2, isGroupChat);
                int affectedRows = roomPstmt.executeUpdate();

                if (affectedRows == 0) {
                    throw new SQLException("Creating chat room failed, no rows affected.");
                }

                int roomId = -1;
                try (ResultSet rs = roomPstmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        roomId = rs.getInt(1);
                    } else {
                        throw new SQLException("Creating chat room failed, no ID obtained.");
                    }
                } // rs 자동 닫힘

                try (PreparedStatement participantPstmt = conn.prepareStatement(participantSql)) {
                    for (int userId : participantIds) {
                        participantPstmt.setInt(1, roomId);
                        participantPstmt.setInt(2, userId);
                        participantPstmt.addBatch();
                    }
                    participantPstmt.executeBatch();
                } // participantPstmt 자동 닫힘

                conn.commit(); // 모든 작업 성공 시 커밋
                newRoom = new ChatRoom(roomId, roomName, LocalDateTime.now(), isGroupChat);

                // 생성된 방의 참여자 목록 로드 (개별 DAO 호출)
                UserDAO userDAO = new UserDAO();
                List<User> participants = new ArrayList<>();
                for (int userId : participantIds) {
                    User user = userDAO.getUserByUserId(userId);
                    if (user != null) {
                        participants.add(user);
                    }
                }
                newRoom.setParticipants(participants);

            } // roomPstmt 자동 닫힘
        } catch (SQLException e) {
            System.err.println("Error creating chat room: " + e.getMessage());
            if (conn != null) {
                try {
                    conn.rollback(); // 오류 발생 시 롤백
                    System.err.println("Chat room creation transaction rolled back.");
                } catch (SQLException ex) {
                    System.err.println("Rollback failed: " + ex.getMessage());
                }
            }
            newRoom = null; // 생성 실패 시 null 반환
        } finally {
            // 이 conn.close()는 DatabaseConnection.getConnection()이 새 연결을 줄 때만 유효
            // try-with-resources로 conn을 선언했으면 이 finally 블록은 필요 없음.
            // 현재는 conn을 try 밖에서 선언했으므로 필요.
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // 오토커밋 모드 복원 (풀링 사용 시 중요)
                    conn.close(); // 연결 닫기
                } catch (SQLException e) {
                    System.err.println("Error closing connection in createChatRoom: " + e.getMessage());
                }
            }
        }
        return newRoom;
    }

    /**
     * 특정 2인 간의 1:1 채팅방이 이미 존재하는지 조회
     * @param user1Id 사용자 1 ID
     * @param user2Id 사용자 2 ID
     * @return 1:1 채팅방 객체 또는 null
     */
    public ChatRoom getExistingPrivateChatRoom(int user1Id, int user2Id) {
        String sql = "SELECT cr.room_id, cr.room_name, cr.created_at, cr.is_group_chat " +
                "FROM chat_rooms cr " +
                "JOIN room_participants rp1 ON cr.room_id = rp1.room_id " +
                "JOIN room_participants rp2 ON cr.room_id = rp2.room_id " +
                "WHERE cr.is_group_chat = FALSE " + // 1:1 채팅방만
                "AND rp1.user_id = ? AND rp2.user_id = ? " +
                "AND (SELECT COUNT(*) FROM room_participants WHERE room_id = cr.room_id) = 2"; // 정확히 2명 참여

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, user1Id);
            pstmt.setInt(2, user2Id);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int roomId = rs.getInt("room_id");
                String roomName = rs.getString("room_name");
                LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                boolean isGroupChat = rs.getBoolean("is_group_chat");
                ChatRoom existingRoom = new ChatRoom(roomId, roomName, createdAt, isGroupChat);
                existingRoom.setParticipants(getParticipantsInRoom(roomId)); // 참여자 정보도 로드
                return existingRoom;
            }
        } catch (SQLException e) {
            System.err.println("Error checking private chat room: " + e.getMessage());
        }
        return null;
    }


    /**
     * 특정 사용자가 참여하고 있는 모든 채팅방 조회
     * @param userId 사용자 ID
     * @return 채팅방 리스트
     */
    public List<ChatRoom> getChatRoomsByUserId(int userId) {
        List<ChatRoom> chatRooms = new ArrayList<>();
        // userId 유효성 검사 추가
        if (userId <= 0) { // 유효한 user_id는 보통 1부터 시작합니다.
            System.err.println("Invalid userId provided to getChatRoomsByUserId: " + userId + ". Returning empty list.");
            return chatRooms; // 유효하지 않은 userId인 경우 빈 리스트 반환
        }

        String sql = "SELECT cr.room_id, cr.room_name, cr.created_at, cr.is_group_chat " +
                "FROM chat_rooms cr JOIN room_participants rp ON cr.room_id = rp.room_id " +
                "WHERE rp.user_id = ? ORDER BY cr.created_at ASC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int roomId = rs.getInt("room_id");
                    String roomName = rs.getString("room_name");
                    LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                    boolean isGroupChat = rs.getBoolean("is_group_chat");

                    // getParticipantsInRoom 호출 시에도 새로운 Connection을 얻도록 DatabaseConnection이 변경되었으므로 문제 없음.
                    List<User> participants = getParticipantsInRoom(roomId);

                    // 1:1 채팅방 이름 재구성 로직
                    if (!isGroupChat) {
                        if (participants.size() == 2) {
                            User user1 = participants.get(0);
                            User user2 = participants.get(1);
                            String otherUserName = "";
                            if(user1.getUserId() == userId) {
                                otherUserName = user2.getNickname();
                            } else {
                                otherUserName = user1.getNickname();
                            }
                            roomName = otherUserName + "님과의 1:1 대화";
                        } else {
                            roomName = "알 수 없는 1:1 대화방 (참여자 수 불일치)"; // 예외 상황 메시지
                        }
                    }

                    ChatRoom room = new ChatRoom(roomId, roomName, createdAt, isGroupChat);
                    room.setParticipants(participants);
                    chatRooms.add(room);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting chat rooms by user ID: " + e.getMessage());
            return new ArrayList<>();
        }
        return chatRooms;
    }

    /**
     * 채팅방에 사용자 초대
     * @param roomId 채팅방 ID
     * @param userId 초대할 사용자 ID
     * @return 성공 여부
     */
    public boolean inviteUserToRoom(int roomId, int userId) {
        // 이미 참여자인지 확인
        String checkSql = "SELECT COUNT(*) FROM room_participants WHERE room_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement checkPstmt = conn.prepareStatement(checkSql)) {
            checkPstmt.setInt(1, roomId);
            checkPstmt.setInt(2, userId);
            ResultSet rs = checkPstmt.executeQuery();
            if (rs.next() && rs.getInt(1) > 0) {
                System.out.println("User " + userId + " is already a participant in room " + roomId);
                return false; // 이미 참여 중
            }
        } catch (SQLException e) {
            System.err.println("Error checking user in room: " + e.getMessage());
            return false;
        }

        String insertSql = "INSERT INTO room_participants (room_id, user_id) VALUES (?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setInt(1, roomId);
            pstmt.setInt(2, userId);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error inviting user to room: " + e.getMessage());
            return false;
        }
    }

    /**
     * 특정 채팅방의 참여자 목록 조회
     * @param roomId 채팅방 ID
     * @return 참여자 User 객체 리스트
     */
    public List<User> getParticipantsInRoom(int roomId) {
        List<User> participants = new ArrayList<>();
        String sql = "SELECT u.user_id, u.username, u.nickname, u.status FROM room_participants rp JOIN users u ON rp.user_id = u.user_id WHERE rp.room_id = ?";

        try (Connection conn = DatabaseConnection.getConnection(); // 여기서 새로운 Connection을 얻음
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            try (ResultSet rs = pstmt.executeQuery()) { // ResultSet도 try-with-resources로 관리
                while (rs.next()) {
                    int userId = rs.getInt("user_id");
                    String username = rs.getString("username");
                    String nickname = rs.getString("nickname");
                    UserStatus status = UserStatus.valueOf(rs.getString("status"));
                    participants.add(new User(userId, username, nickname, status));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting participants in room: " + e.getMessage());
        }
        return participants; // 빈 리스트 반환 (null 아님)
    }

    /**
     * 채팅방 ID로 채팅방 정보 조회
     * @param roomId 채팅방 ID
     * @return ChatRoom 객체 또는 null
     */
    public ChatRoom getChatRoomById(int roomId) {
        String sql = "SELECT room_id, room_name, created_at, is_group_chat FROM chat_rooms WHERE room_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String roomName = rs.getString("room_name");
                LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                boolean isGroupChat = rs.getBoolean("is_group_chat");
                ChatRoom room = new ChatRoom(roomId, roomName, createdAt, isGroupChat);
                room.setParticipants(getParticipantsInRoom(roomId));
                return room;
            }
        } catch (SQLException e) {
            System.err.println("Error getting chat room by ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * 사용자를 채팅방에서 제거합니다.
     * @param roomId 채팅방 ID
     * @param userId 제거할 사용자 ID
     * @return 제거 성공 여부
     */
    public boolean leaveChatRoom(int roomId, int userId) {
        String sql = "DELETE FROM room_participants WHERE room_id = ? AND user_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            pstmt.setInt(2, userId);
            int affectedRows = pstmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("Error leaving chat room: " + e.getMessage());
            return false;
        }
    }
}