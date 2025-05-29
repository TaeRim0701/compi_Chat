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
                            sendUnreadNotification(participant.getUserId(), message.getRoomId(), message.getMessageId(), message.getContent());
                        }
                    }
                }
            }
        }
    }

    private void sendUnreadNotification(int targetUserId, int unreadRoomId, int unreadMessageId, String unreadMessageContent) {
        ClientHandler handler = connectedClients.get(targetUserId);
        if (handler != null) {
            int systemRoomId = 1;
            String notificationContent = "당신이 참여한 채팅방(ID: " + unreadRoomId + ")에 읽지 않은 메시지가 있습니다: \"" + unreadMessageContent + "\"";
            Message systemMessage = new Message(systemRoomId, this.systemUserId, "시스템", MessageType.SYSTEM, notificationContent, false);

            Map<String, Object> data = new HashMap<>();
            data.put("message", systemMessage);
            data.put("unreadRoomId", unreadRoomId);
            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "Unread message notification", data));
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
}