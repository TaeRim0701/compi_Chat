package chat.compi.DB;

import chat.compi.Entity.TimelineEvent;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TimelineDAO {

    /**
     * 타임라인 이벤트 저장 (새로운 오버로드 메서드)
     * @param roomId 채팅방 ID
     * @param userId 이벤트 발생 사용자 ID
     * @param command 명령어 (예: /start)
     * @param description 작업 내용
     * @param eventType 이벤트 타입 (예: PROJECT_START)
     * @param eventName 이벤트 이름 (예: 프로젝트 이름)
     * @return 성공 여부
     */
    public boolean saveTimelineEvent(int roomId, int userId, String command, String description, String eventType, String eventName) { // 수정된 부분: String String eventName -> String eventName
        String sql = "INSERT INTO timeline_events (room_id, user_id, command, description, event_type, event_name) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            pstmt.setInt(2, userId);
            pstmt.setString(3, command);
            pstmt.setString(4, description);
            pstmt.setString(5, eventType);
            pstmt.setString(6, eventName);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error saving timeline event with type and name: " + e.getMessage());
            return false;
        }
    }

    /**
     * 특정 채팅방의 타임라인 이벤트 조회 (event_type, event_name 필드 추가)
     * @param roomId 채팅방 ID
     * @return 타임라인 이벤트 리스트
     */
    public List<TimelineEvent> getTimelineEventsInRoom(int roomId) {
        List<TimelineEvent> events = new ArrayList<>();
        // username 대신 nickname을 가져오도록 쿼리 변경
        String sql = "SELECT te.event_id, te.room_id, te.user_id, u.nickname, te.command, te.description, te.event_time, te.event_type, te.event_name " +
                "FROM timeline_events te JOIN users u ON te.user_id = u.user_id " +
                "WHERE te.room_id = ? ORDER BY te.event_time ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int eventId = rs.getInt("event_id");
                int rId = rs.getInt("room_id");
                int uId = rs.getInt("user_id");
                String nickname = rs.getString("nickname");
                String command = rs.getString("command");
                String description = rs.getString("description");
                LocalDateTime eventTime = rs.getTimestamp("event_time").toLocalDateTime();
                String eventType = rs.getString("event_type");
                String eventName = rs.getString("event_name");
                // TimelineEvent 생성자에 nickname 전달
                events.add(new TimelineEvent(eventId, rId, uId, nickname, command, description, eventTime, eventType, eventName));
            }
        } catch (SQLException e) {
            System.err.println("Error getting timeline events: " + e.getMessage());
        }
        return events;
    }
}