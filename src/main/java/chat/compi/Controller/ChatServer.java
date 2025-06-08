// ChatServer.java
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

    public ChatRoom ensureUserSystemChatRoom(int userId) {
        if (userId == systemUserId) {
            return null;
        }
        return chatRoomDAO.getOrCreateSystemChatRoomForUser(userId, systemUserId);
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Chat Server started on port " + PORT);

            // 서버 시작 시 모든 기존 사용자에게 시스템 채팅방이 있는지 확인 및 생성
            System.out.println("Ensuring system chat rooms for all existing users...");
            List<User> allUsers = userDAO.getAllUsers();
            for (User user : allUsers) {
                if (user.getUserId() != systemUserId) {
                    ensureUserSystemChatRoom(user.getUserId());
                }
            }
            System.out.println("System chat room setup complete for existing users.");

            // 주기적인 안 읽은 메시지 확인
            scheduler.scheduleAtFixedRate(this::checkUnreadMessages, 5, 5, TimeUnit.MINUTES);

            // 주기적인 만료된 공지 확인 및 정리 (주기를 1분으로 단축)
            scheduler.scheduleAtFixedRate(this::clearExpiredNotices, 0, 1, TimeUnit.MINUTES); // 서버 시작 즉시 한 번 실행 후 1분마다


            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    List<ChatRoom> allActiveRooms = getAllChatRoomsForUnreadCheck();

                    for (ChatRoom room : allActiveRooms) {
                        List<User> participants = chatRoomDAO.getParticipantsInRoom(room.getRoomId());
                        for (User participant : participants) {
                            int userId = participant.getUserId();
                            if (userId == getSystemUserId()) {
                                continue;
                            }
                            int unreadCount = messageDAO.getUnreadMessageCount(room.getRoomId(), userId);
                            if (unreadCount > 0) {
                                String notificationContent = String.format(
                                        "채팅방 '%s'에 안 읽은 메시지가 %d개 있습니다.",
                                        room.getRoomName(),
                                        unreadCount
                                );
                                Message systemNotification = new Message(
                                        room.getRoomId(),
                                        getSystemUserId(),
                                        "시스템",
                                        MessageType.SYSTEM,
                                        notificationContent,
                                        false
                                );
                                sendMessageToUser(userId, systemNotification);
                            }
                        }
                    }
                }
            }, 3600000, 3600000);
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
            ensureUserSystemChatRoom(userId);
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
        } else if (message.getMessageType() == MessageType.SYSTEM && senderUserId == getSystemUserId()) {
            messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId());
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
                if (savedMessage.getMessageType() == MessageType.SYSTEM) {
                    data.put("unreadRoomId", savedMessage.getRoomId());
                }
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
                if (message.getMessageType() == MessageType.SYSTEM || message.getSentAt().isAfter(oneHourAgo)) {
                    continue;
                }

                for (User participant : participants) {
                    if (participant.getUserId() == message.getSenderId()) {
                        continue;
                    }

                    boolean isRead = messageDAO.isMessageReadByUser(message.getMessageId(), participant.getUserId());

                    if (!isRead) {
                        String notificationContent = String.format(
                                "채팅방 '%s'에서 '%s'님이 보낸 메시지를 아직 읽지 않으셨습니다: \"%s\"",
                                room.getRoomName(),
                                message.getSenderNickname(),
                                message.getContent()
                        );
                        Message systemNotification = new Message(
                                room.getRoomId(),
                                this.systemUserId,
                                "시스템",
                                MessageType.SYSTEM,
                                notificationContent,
                                false
                        );
                        sendMessageToUser(participant.getUserId(), systemNotification);
                    }
                }
            }
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

    public void sendMessageToUser(int targetUserId, Message message) {
        ClientHandler handler = connectedClients.get(targetUserId);

        if (message.getMessageType() == MessageType.SYSTEM) {
            ChatRoom systemChatRoom = ensureUserSystemChatRoom(targetUserId);
            if (systemChatRoom == null) {
                System.err.println("Failed to get or create system chat room for user " + targetUserId + ". Cannot send system message.");
                return;
            }
            message.setRoomId(systemChatRoom.getRoomId());
        }

        Message savedMessage = messageDAO.saveMessage(message);
        if (savedMessage == null) {
            System.err.println("Failed to save message to DB. Not sending notification to client " + targetUserId);
            return;
        }

        if (message.getMessageType() == MessageType.SYSTEM) {
            messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId());
        }


        if (handler != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("message", savedMessage);
            data.put("senderId", savedMessage.getSenderId());

            if (savedMessage.getMessageType() == MessageType.SYSTEM) {
                data.put("unreadRoomId", savedMessage.getRoomId());
            }

            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System Notification", data));
            System.out.println("Sent system notification (real-time) to user " + targetUserId + " for room " + savedMessage.getRoomId());
        } else {
            System.out.println("User " + targetUserId + " is not connected. System notification saved to DB.");
        }
    }

    // 새로운 메서드: 만료된 공지를 정리하고, 관련 채팅방 클라이언트에게 공지 목록 업데이트를 요청
    private void clearExpiredNotices() {
        System.out.println("Checking and clearing expired notices...");
        int clearedCount = messageDAO.clearExpiredNotices();
        if (clearedCount > 0) {
            System.out.println("Cleared " + clearedCount + " expired notices from DB. Notifying clients.");
            // 모든 연결된 클라이언트에게 공지 목록을 새로고침하도록 요청
            for (ClientHandler handler : connectedClients.values()) {
                // handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Expired notices cleared, refresh notice list", null));
                // 특정 채팅방의 공지 목록만 업데이트하도록 변경해야 함.
                // 만료된 공지가 발생한 방들의 ID를 가져와서 해당 방의 참가자들에게만 업데이트를 요청하는 것이 효율적.
                // 현재 clearExpiredNotices는 어떤 방의 공지가 만료되었는지 알 수 없으므로,
                // 일단 모든 클라이언트에게 "공지 목록을 새로고침하라"는 일반적인 알림을 보냄.
                // ChatClientGUI에서는 이 알림을 받으면, 열려있는 모든 채팅방의 공지 목록을 새로 가져와야 함.

                // 하지만 더 정확한 방법은, 만료된 공지의 room_id를 MessageDAO.clearExpiredNotices에서 반환하도록 하고,
                // 그 room_id에 해당하는 방의 참가자들에게만 NOTICE_LIST_UPDATE를 보내는 것.
                // 여기서는 모든 클라이언트에게 보냅니다.
                // 이 방법은 효율적이지 않지만, 기능 구현을 위해 우선 사용합니다.
                // ClientHandler에게 모든 채팅방의 공지 목록을 다시 요청하도록 유도
                handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Expired notices cleared, refresh notice list", null));
                // 또는 ChatClientGUI에서 열려있는 모든 ChatRoomDialog에 대해 getNoticeMessages(roomId)를 호출하도록 처리
            }
        }
    }
}