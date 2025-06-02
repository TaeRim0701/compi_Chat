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
    private ConcurrentHashMap<Integer, ClientHandler> connectedClients;
    private UserDAO userDAO;
    private MessageDAO messageDAO;
    private ChatRoomDAO chatRoomDAO;
    private ScheduledExecutorService scheduler;
    private Map<Integer, ChatRoom> chatRooms;

    private static final String SYSTEM_USERNAME = "system_bot";
    private int systemUserId;

    public ChatServer() {
        connectedClients = new ConcurrentHashMap<>();
        userDAO = new UserDAO();
        messageDAO = new MessageDAO();
        chatRoomDAO = new ChatRoomDAO();
        scheduler = Executors.newScheduledThreadPool(1);

        File uploadDir = new File("server_uploads");
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        ensureSystemUserExists();
    }

    private void ensureSystemUserExists() {
        User systemUser = userDAO.getUserByUsername(SYSTEM_USERNAME);
        if (systemUser == null) {
            System.out.println("System user '" + SYSTEM_USERNAME + "' not found. Registering new system user.");
            // UserStatus.OFFLINE 인자 제거
            boolean registered = userDAO.registerUser(SYSTEM_USERNAME, UUID.randomUUID().toString(), "시스템");
            if (!registered) {
                System.err.println("CRITICAL ERROR: Failed to register system user. System messages might fail.");
            }
            systemUser = userDAO.getUserByUsername(SYSTEM_USERNAME);
        }

        if (systemUser != null) {
            this.systemUserId = systemUser.getUserId();
            System.out.println("System messages will use user ID: " + this.systemUserId + " (username: " + SYSTEM_USERNAME + ")");
        } else {
            System.err.println("System user could not be found or created. System messages will likely fail due to foreign key constraint.");
            this.systemUserId = -1;
        }
    }

    public int getSystemUserId() {
        return systemUserId;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Chat Server started on port " + PORT);

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

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // 모든 채팅방을 순회하며 안 읽은 메시지가 있는 사용자에게 알림을 보냅니다.
                // 이 chatRooms 맵은 ChatServer 클래스에 필드로 선언되어 있지만, 초기화되지 않고 사용되고 있습니다.
                // 아래 로직이 제대로 동작하려면 chatRooms 필드를 올바르게 초기화해야 합니다.
                // getAllChatRoomsForUnreadCheck()를 호출하여 현재 활성화된 채팅방 목록을 가져오는 것이 더 안전합니다.
                List<ChatRoom> allActiveRooms = getAllChatRoomsForUnreadCheck(); // 모든 채팅방을 DB에서 가져옴

                for (ChatRoom room : allActiveRooms) { // allActiveRooms 사용
                    List<User> participants = chatRoomDAO.getParticipantsInRoom(room.getRoomId()); // 해당 방 참여자 목록 조회
                    for (User participant : participants) {
                        int userId = participant.getUserId();
                        int unreadCount = messageDAO.getUnreadMessageCount(room.getRoomId(), userId); // roomId 사용
                        if (unreadCount > 0) {
                            String notificationContent = String.format(
                                    "채팅방 '%s'에 안 읽은 메시지가 %d개 있습니다.",
                                    room.getRoomName(), // 채팅방 이름 사용
                                    unreadCount
                            );
                            // Message 객체 생성하여 전달
                            Message systemNotification = new Message(
                                    room.getRoomId(), // 해당 채팅방 ID
                                    getSystemUserId(),
                                    "시스템",
                                    MessageType.SYSTEM,
                                    notificationContent,
                                    false
                            );
                            sendMessageToUser(userId, systemNotification); // Message 객체 전달
                        }
                    }
                }
            }
        }, 3600000, 3600000); // 1시간(3600000 밀리초)마다 실행
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

    public void addClient(int userId, ClientHandler clientHandler) {
        connectedClients.put(userId, clientHandler);
        System.out.println("Client " + userId + " connected. Total clients: " + connectedClients.size());
        User user = userDAO.getUserByUserId(userId);
        if (user != null) {
            notifyFriendStatusChange(user);
        }
    }

    public void removeClient(int userId) {
        if (connectedClients.containsKey(userId)) {
            connectedClients.remove(userId);
            userDAO.updateUserStatus(userId, UserStatus.OFFLINE);
            System.out.println("Client " + userId + " disconnected. Total clients: " + connectedClients.size());
            User user = userDAO.getUserByUserId(userId);
            if (user != null) {
                user.setStatus(UserStatus.OFFLINE);
                notifyFriendStatusChange(user);
            }
        }
    }

    public ConcurrentHashMap<Integer, ClientHandler> getConnectedClients() {
        return connectedClients;
    }

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

    public void broadcastMessageToRoom(Message message, int senderUserId) {
        List<User> participants = chatRoomDAO.getParticipantsInRoom(message.getRoomId());

        Message savedMessage = messageDAO.saveMessage(message);
        if (savedMessage == null) {
            System.err.println("Failed to save message to DB.");
            return;
        }

        if (message.getMessageType() != MessageType.SYSTEM && senderUserId != -1) {
            messageDAO.markMessageAsRead(savedMessage.getMessageId(), senderUserId);
        }

        List<User> readers = messageDAO.getReadersForMessage(savedMessage.getMessageId());
        savedMessage.setReaders(readers);

        int totalParticipantsInRoom = chatRoomDAO.getParticipantsInRoom(message.getRoomId()).size();
        savedMessage.setUnreadCount(totalParticipantsInRoom - savedMessage.getReaders().size());


        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                Map<String, Object> data = new HashMap<>();
                data.put("message", savedMessage);
                data.put("senderId", senderUserId);
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NEW_MESSAGE, true, "New message", data));
            }
        }

        if (message.getMessageType() != MessageType.SYSTEM) {
            updateUnreadCountsForRoom(message.getRoomId());
        }
    }

    public void notifyRoomParticipantsOfRoomUpdate(int roomId) {
        ChatRoom updatedRoom = chatRoomDAO.getChatRoomById(roomId);
        if (updatedRoom == null) return;

        List<User> participants = chatRoomDAO.getParticipantsInRoom(roomId);
        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                handler.sendChatRoomList();
            }
        }
    }

    private void checkUnreadMessages() {
        System.out.println("Checking for unread messages...");
        List<ChatRoom> allRooms = getAllChatRoomsForUnreadCheck();
        LocalDateTime oneHourAgo = LocalDateTime.now().minus(1, ChronoUnit.HOURS);

        for (ChatRoom room : allRooms) {
            List<User> participants = chatRoomDAO.getParticipantsInRoom(room.getRoomId());
            List<Message> messagesInRoom = messageDAO.getMessagesInRoom(room.getRoomId());

            for (Message message : messagesInRoom) {
                if (message.getMessageType() == MessageType.SYSTEM) {
                    continue;
                }

                if (message.getSentAt().isBefore(oneHourAgo)) {
                    for (User participant : participants) {
                        if (participant.getUserId() == message.getSenderId()) {
                            continue;
                        }

                        boolean isRead = messageDAO.isMessageReadByUser(message.getMessageId(), participant.getUserId());

                        if (!isRead) {
                            // 여기에서 Message 객체를 생성하여 sendUnreadNotification에 전달
                            String notificationContent = String.format(
                                    "채팅방 '%s'에 읽지 않은 메시지가 있습니다: \"%s\"",
                                    room.getRoomName(), // 채팅방 이름 추가
                                    message.getContent()
                            );
                            Message systemNotification = new Message(
                                    message.getRoomId(), // 이 메시지가 속한 채팅방 ID
                                    this.systemUserId,
                                    "시스템",
                                    MessageType.SYSTEM,
                                    notificationContent,
                                    false
                            );
                            sendUnreadNotification(participant.getUserId(), systemNotification); // Message 객체 전달
                        }
                    }
                }
            }
        }
    }

    // sendUnreadNotification 메서드 시그니처 및 내부 로직 변경
    private void sendUnreadNotification(int targetUserId, Message systemMessage) { // String -> Message 변경
        ClientHandler handler = connectedClients.get(targetUserId);
        if (handler != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("message", systemMessage);
            data.put("unreadRoomId", systemMessage.getRoomId()); // 알림 메시지에서 roomId를 가져와 전달
            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "Unread message notification", data));
            System.out.println("Sent system notification to user " + targetUserId + " for room " + systemMessage.getRoomId());
        } else {
            System.out.println("User " + targetUserId + " is not connected. Cannot send system notification.");
        }
    }

    public void updateUnreadCountsForRoom(int roomId) {
        List<User> participants = chatRoomDAO.getParticipantsInRoom(roomId);

        for (User participant : participants) {
            ClientHandler handler = connectedClients.get(participant.getUserId());
            if (handler != null) {
                List<Message> messagesWithReadInfo = new ArrayList<>();
                List<Message> messagesInRoom = messageDAO.getMessagesInRoom(roomId);

                for (Message msg : messagesInRoom) {
                    if (msg.getMessageType() == MessageType.SYSTEM) {
                        messagesWithReadInfo.add(msg);
                        continue;
                    }

                    List<User> readers = messageDAO.getReadersForMessage(msg.getMessageId());
                    msg.setReaders(readers);
                    int totalParticipants = chatRoomDAO.getParticipantsInRoom(roomId).size();
                    msg.setUnreadCount(totalParticipants - readers.size());
                    messagesWithReadInfo.add(msg);
                }

                Map<String, Object> roomMessagesData = new HashMap<>();
                roomMessagesData.put("roomId", roomId);
                roomMessagesData.put("messages", messagesWithReadInfo);
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Messages with unread count updated", roomMessagesData));
            }
        }
    }

    private List<ChatRoom> getAllChatRoomsForUnreadCheck() {
        List<ChatRoom> allRooms = new ArrayList<>();
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
                room.setParticipants(chatRoomDAO.getParticipantsInRoom(roomId));
                allRooms.add(room);
            }
        } catch (SQLException e) {
            System.err.println("Error getting all chat rooms for unread check: " + e.getMessage());
        }
        return allRooms;
    }

    // ChatServer.java

    public void sendMessageToUser(int targetUserId, Message message) {
        ClientHandler handler = connectedClients.get(targetUserId);

        // 시스템 메시지도 DB에 저장
        // message.setRoomId()가 시스템 메시지용 가상의 방 ID (예: 1)를 가지도록 설계되어 있다면 문제 없음
        Message savedMessage = messageDAO.saveMessage(message); // <-- 시스템 메시지 DB 저장
        if (savedMessage == null) {
            System.err.println("Failed to save system notification message to DB.");
            return;
        }

        // 시스템 메시지를 보낸 후에는 발신자(시스템 봇)가 읽은 것으로 처리 (선택 사항이지만 일관성을 위해)
        messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId());


        if (handler != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("message", savedMessage); // 저장된 메시지 객체 사용
            data.put("senderId", getSystemUserId());
            data.put("unreadRoomId", savedMessage.getRoomId()); // 메시지의 roomId를 사용
            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System Notification", data));
            System.out.println("Sent system notification (real-time) to user " + targetUserId + " for room " + savedMessage.getRoomId());
        } else {
            System.out.println("User " + targetUserId + " is not connected. System notification saved to DB.");
            // 오프라인 사용자에게는 실시간으로 보내지 못했지만, DB에 저장되었으므로 나중에 볼 수 있음.
        }
    }
}