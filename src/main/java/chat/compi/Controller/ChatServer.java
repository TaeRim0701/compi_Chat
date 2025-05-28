package chat.compi.Controller;


import chat.compi.DB.DatabaseConnection;
import chat.compi.Entity.*;
import chat.compi.Dto.ServerResponse;
import chat.compi.DB.ChatRoomDAO;
import chat.compi.DB.MessageDAO;
import chat.compi.DB.UserDAO;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatServer {
    private static final int PORT = 12345;
    private ServerSocket serverSocket;
    // 현재 접속 중인 클라이언트 핸들러 (userId -> ClientHandler)
    private ConcurrentHashMap<Integer, ClientHandler> connectedClients;
    private UserDAO userDAO;
    private MessageDAO messageDAO;
    private ChatRoomDAO chatRoomDAO;
    private ScheduledExecutorService scheduler; // 스케줄러 추가

    public ChatServer() {
        connectedClients = new ConcurrentHashMap<>();
        userDAO = new UserDAO();
        messageDAO = new MessageDAO();
        chatRoomDAO = new ChatRoomDAO();
        scheduler = Executors.newScheduledThreadPool(1); // 단일 스레드 스케줄러

        // 파일 업로드/다운로드 디렉토리 생성
        File uploadDir = new File("server_uploads");
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Chat Server started on port " + PORT);

            // 1시간 미열람 알림 스케줄러 시작 (예: 5분마다 실행)
            scheduler.scheduleAtFixedRate(this::checkUnreadMessages, 5, 5, TimeUnit.MINUTES);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress());
                ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            stop();
        }
    }

    public void stop() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
                System.out.println("Scheduler shut down.");
            }
            System.out.println("Chat Server stopped.");
        } catch (IOException e) {
            System.err.println("Error stopping server: " + e.getMessage());
        }
    }

    // 클라이언트 등록
    public void addClient(int userId, ClientHandler clientHandler) {
        connectedClients.put(userId, clientHandler);
        System.out.println("Client " + userId + " connected. Total clients: " + connectedClients.size());
        // 친구들에게 상태 업데이트 알림
        User user = userDAO.getUserByUserId(userId);
        if (user != null) {
            notifyFriendStatusChange(user);
        }
    }

    // 클라이언트 제거 (로그아웃 또는 연결 끊김)
    public void removeClient(int userId) {
        if (connectedClients.containsKey(userId)) {
            connectedClients.remove(userId);
            userDAO.updateUserStatus(userId, UserStatus.OFFLINE); // DB 상태 업데이트
            System.out.println("Client " + userId + " disconnected. Total clients: " + connectedClients.size());
            // 친구들에게 상태 업데이트 알림
            User user = userDAO.getUserByUserId(userId);
            if (user != null) {
                user.setStatus(UserStatus.OFFLINE); // 로컬 객체 상태도 업데이트
                notifyFriendStatusChange(user);
            }
        }
    }

    // 모든 클라이언트 핸들러 가져오기
    public ConcurrentHashMap<Integer, ClientHandler> getConnectedClients() {
        return connectedClients;
    }

    // 친구 상태 변화 알림
    public void notifyFriendStatusChange(User user) {
        List<User> friends = userDAO.getFriends(user.getUserId());
        for (User friend : friends) {
            ClientHandler friendHandler = connectedClients.get(friend.getUserId());
            if (friendHandler != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("userId", user.getUserId());
                data.put("username", user.getUsername());
                data.put("nickname", user.getNickname());
                data.put("status", user.getStatus().name());
                friendHandler.sendResponse(new ServerResponse(ServerResponse.ResponseType.FRIEND_STATUS_UPDATE, true, "Friend status updated", data));
            }
        }
    }

    // 특정 채팅방의 모든 참여자에게 메시지 브로드캐스트
    public void broadcastMessageToRoom(Message message, int senderUserId) {
        List<User> participants = chatRoomDAO.getParticipantsInRoom(message.getRoomId());

        // 메시지를 먼저 DB에 저장
        Message savedMessage = messageDAO.saveMessage(message);
        if (savedMessage == null) {
            System.err.println("Failed to save message to DB.");
            return;
        }

        // 메시지를 보낸 사람에게는 바로 읽음 처리
        messageDAO.markMessageAsRead(savedMessage.getMessageId(), senderUserId);


        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("message", savedMessage);
                data.put("senderId", senderUserId); // 메시지를 보낸 사용자 ID 추가
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NEW_MESSAGE, true, "New message", data));
            }
        }

        // 읽지 않은 사용자 수 업데이트
        updateUnreadCountsForRoom(message.getRoomId());
    }

    // 특정 채팅방의 참여자들에게 채팅방 목록 업데이트를 알림
    public void notifyRoomParticipantsOfRoomUpdate(int roomId) {
        ChatRoom updatedRoom = chatRoomDAO.getChatRoomById(roomId);
        if (updatedRoom == null) return;

        List<User> participants = chatRoomDAO.getParticipantsInRoom(roomId);
        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("chatRoom", updatedRoom);
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, true, "Chat room updated", data));
            }
        }
    }


    // 1시간 미열람 메시지 확인 및 알림
    private void checkUnreadMessages() {
        System.out.println("Checking for unread messages...");
        // 모든 메시지를 순회하며 1시간 이상 읽지 않은 사용자를 찾습니다.
        // 이 로직은 성능에 영향을 줄 수 있으므로, 실제 구현에서는 최적화가 필요할 수 있습니다.
        // 예시를 위해 모든 메시지에서 시작합니다.

        // 모든 채팅방을 대상으로 하는 것이 더 효율적일 수 있습니다.
        // 일단 모든 active chat rooms를 가져온다고 가정합니다.
        List<ChatRoom> allRooms = chatRoomDAO.getChatRoomsByUserId(-1); // 임시로 -1: 모든 채팅방 가져오는 DAO 함수가 필요
        if (allRooms == null) { // 임시로 모든 채팅방을 가져오는 헬퍼 함수
            allRooms = getAllChatRoomsForUnreadCheck();
        }

        LocalDateTime oneHourAgo = LocalDateTime.now().minus(1, ChronoUnit.HOURS);

        for (ChatRoom room : allRooms) {
            List<User> participants = chatRoomDAO.getParticipantsInRoom(room.getRoomId());
            List<Message> messagesInRoom = messageDAO.getMessagesInRoom(room.getRoomId());

            for (Message message : messagesInRoom) {
                if (message.getSentAt().isBefore(oneHourAgo)) { // 1시간 지난 메시지
                    for (User participant : participants) {
                        // 메시지 보낸 사람은 제외 (자기가 보낸 메시지는 읽은 것으로 간주)
                        if (participant.getUserId() == message.getSenderId()) {
                            continue;
                        }

                        // 해당 메시지를 해당 유저가 읽었는지 확인
                        boolean isRead = isMessageReadByUser(message.getMessageId(), participant.getUserId());

                        if (!isRead) {
                            // 아직 읽지 않았다면 알림 전송
                            sendUnreadNotification(participant.getUserId(), message.getRoomId(), message.getMessageId(), message.getContent());
                        }
                    }
                }
            }
        }
    }

    // 메시지 읽음 여부 확인 헬퍼
    private boolean isMessageReadByUser(int messageId, int userId) {
        // message_reads 테이블에서 확인
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
            System.err.println("Error checking message read status: " + e.getMessage());
        }
        return false;
    }

    // 안 읽은 메시지 알림 전송 (시스템 채팅방으로)
    private void sendUnreadNotification(int targetUserId, int unreadRoomId, int unreadMessageId, String unreadMessageContent) {
        ClientHandler handler = connectedClients.get(targetUserId);
        if (handler != null) {
            // 시스템 알림 메시지를 생성 (예: system_notification_room_id 1번)
            // 실제 시스템 알림방은 미리 생성되어 있어야 합니다.
            int systemRoomId = 1; // 시스템 알림 전용 채팅방 ID (미리 DB에 생성되어 있어야 함)
            String notificationContent = "당신이 참여한 채팅방(ID: " + unreadRoomId + ")에 읽지 않은 메시지가 있습니다: \"" + unreadMessageContent + "\"";
            Message systemMessage = new Message(systemRoomId, -1, "시스템", MessageType.SYSTEM, notificationContent, false); // senderId -1은 시스템

            Map<String, Object> data = new HashMap<>();
            data.put("message", systemMessage);
            data.put("unreadRoomId", unreadRoomId); // 어떤 방에 읽지 않은 메시지가 있는지 클라이언트에게 전달
            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "Unread message notification", data));
        }
    }

    // 방의 안 읽은 메시지 수 업데이트 (클라이언트 UI 표시용)
    public void updateUnreadCountsForRoom(int roomId) {
        List<User> participants = chatRoomDAO.getParticipantsInRoom(roomId);
        List<Message> messagesInRoom = messageDAO.getMessagesInRoom(roomId); // 최신 메시지들을 가져옴

        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                Map<String, Object> roomMessagesData = new HashMap<>();
                List<Message> messagesWithUnread = new ArrayList<>();
                for (Message msg : messagesInRoom) {
                    // 각 메시지별로 읽은 사람 수 계산
                    int readCount = messageDAO.getReadCountForMessage(msg.getMessageId());
                    int totalParticipants = chatRoomDAO.getParticipantsInRoom(roomId).size();
                    msg.setUnreadCount(totalParticipants - readCount); // 안 읽은 사람 수 설정
                    messagesWithUnread.add(msg);
                }

                roomMessagesData.put("roomId", roomId);
                roomMessagesData.put("messages", messagesWithUnread);
                // 이 응답을 클라이언트에게 보낼 때, 클라이언트는 이 데이터로 UI를 갱신
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Messages with unread count updated", roomMessagesData));
            }
        }
    }


    // 임시로 모든 채팅방을 가져오는 헬퍼 함수
    private List<ChatRoom> getAllChatRoomsForUnreadCheck() {
        List<ChatRoom> allRooms = new ArrayList<>();
        // 실제 운영에서는 모든 채팅방을 DB에서 가져오는 효율적인 방법이 필요합니다.
        // 현재 UserDAO.getAllUsers()처럼 모든 chat_rooms를 가져오는 DAO 메서드가 없어서 임시로 이렇게 둡니다.
        // ChatRoomDAO에 모든 채팅방을 가져오는 메서드를 추가하는 것이 좋습니다.
        String sql = "SELECT room_id, room_name, created_at, is_group_chat FROM chat_rooms";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                int roomId = rs.getInt("room_id");
                String roomName = rs.getString("room_name");
                LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
                boolean isGroupChat = rs.getBoolean("is_group_chat");
                ChatRoom room = new ChatRoom(roomId, roomName, createdAt, isGroupChat);
                allRooms.add(room);
            }
        } catch (SQLException e) {
            System.err.println("Error getting all chat rooms for unread check: " + e.getMessage());
        }
        return allRooms;
    }
}