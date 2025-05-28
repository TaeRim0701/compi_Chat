package chat.compi.DB;


import chat.compi.Entity.TimelineEvent;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TimelineDAO {

    /**
     * 타임라인 이벤트 저장
     * @param roomId 채팅방 ID
     * @param userId 이벤트 발생 사용자 ID
     * @param command 명령어 (예: /start)
     * @param description 작업 내용
     * @return 성공 여부
     */
    public boolean saveTimelineEvent(int roomId, int userId, String command, String description) {
        String sql = "INSERT INTO timeline_events (room_id, user_id, command, description) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, roomId);
            pstmt.setInt(2, userId);
            pstmt.setString(3, command);
            pstmt.setString(4, description);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error saving timeline event: " + e.getMessage());
            return false;
        }
    }

    /**
     * 특정 채팅방의 타임라인 이벤트 조회
     * @param roomId 채팅방 ID
     * @return 타임라인 이벤트 리스트
     */
    public List<TimelineEvent> getTimelineEventsInRoom(int roomId) {
        List<TimelineEvent> events = new ArrayList<>();
        String sql = "SELECT te.event_id, te.room_id, te.user_id, u.username, te.command, te.description, te.event_time " +
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
                String username = rs.getString("username");
                String command = rs.getString("command");
                String description = rs.getString("description");
                LocalDateTime eventTime = rs.getTimestamp("event_time").toLocalDateTime();
                events.add(new TimelineEvent(eventId, rId, uId, username, command, description, eventTime));
            }
        } catch (SQLException e) {
            System.err.println("Error getting timeline events: " + e.getMessage());
        }
        return events;
    }
}