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
    private Map<Integer, ChatRoom> chatRooms; // 이 필드는 현재 사용되지 않는 것으로 보입니다. 삭제해도 무방합니다.

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

    // 새롭게 추가된 메서드: 사용자의 시스템 채팅방을 확보
    public ChatRoom ensureUserSystemChatRoom(int userId) {
        if (userId == systemUserId) { // 시스템 유저 자신은 시스템 채팅방을 가질 필요 없음
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

            // 주기적인 안 읽은 메시지 확인 (이전 로직과 동일)
            scheduler.scheduleAtFixedRate(this::checkUnreadMessages, 5, 5, TimeUnit.MINUTES);

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
                                // 이 부분은 이제 sendMessageToUser가 시스템 채팅방으로 보내도록 변경되므로,
                                // 별도의 시스템 채팅방 메시지로 처리될 것임
                                // 기존 sendMessageToUser 호출은 그대로 유지하되, 내부 로직이 시스템 채팅방을 찾도록 변경됨
                                Message systemNotification = new Message(
                                        room.getRoomId(), // 이 roomId는 원본 채팅방 ID이지만, sendMessageToUser 내부에서 시스템 채팅방 ID로 대체될 것임
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
            ensureUserSystemChatRoom(userId); // 로그인 시 시스템 채팅방 확인 및 생성
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
            // 시스템 메시지의 경우, 시스템 봇 자신이 메시지를 보낸 것으로 간주하여 읽음 처리 (DB에 저장 시)
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
                // 시스템 메시지는 해당 채팅방의 ID를 그대로 사용하도록 함.
                // ChatClientGUI에서 SYSTEM_NOTIFICATION 처리 시 이 roomId를 기반으로 해당 채팅방 다이얼로그를 열 것임.
                if (savedMessage.getMessageType() == MessageType.SYSTEM) {
                    data.put("unreadRoomId", savedMessage.getRoomId()); // 시스템 메시지일 경우, 해당 채팅방 ID를 unreadRoomId로 전달
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
                        // 주기적인 미열람 알림은 해당 사용자의 시스템 채팅방으로 보내도록 수정
                        Message systemNotification = new Message(
                                room.getRoomId(), // 이 roomId는 원본 채팅방 ID로 전달. sendMessageToUser에서 시스템 채팅방 ID로 대체됨
                                this.systemUserId,
                                "시스템",
                                MessageType.SYSTEM,
                                notificationContent,
                                false
                        );
                        sendMessageToUser(participant.getUserId(), systemNotification); //
                    }
                }
            }
        }
    }

    // 이 메서드는 이제 사용되지 않거나 sendMessageToUser로 통합될 수 있습니다.
    // 기존 sendUnreadNotification 로직을 sendMessageToUser에 통합하여 더 일반적인 메시지 전송 처리로 만듭니다.
    // private void sendUnreadNotification(int targetUserId, Message systemMessage) { ... }

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

    // sendMessageToUser 메서드 수정: 시스템 메시지인 경우 대상 사용자의 시스템 채팅방으로 보내도록
    public void sendMessageToUser(int targetUserId, Message message) {
        ClientHandler handler = connectedClients.get(targetUserId);

        // 메시지가 시스템 메시지인 경우, 특정 사용자의 시스템 채팅방 ID를 찾아 설정
        if (message.getMessageType() == MessageType.SYSTEM) {
            ChatRoom systemChatRoom = ensureUserSystemChatRoom(targetUserId); // 시스템 채팅방 확보
            if (systemChatRoom == null) {
                System.err.println("Failed to get or create system chat room for user " + targetUserId + ". Cannot send system message.");
                return;
            }
            message.setRoomId(systemChatRoom.getRoomId()); // 메시지의 roomId를 시스템 채팅방 ID로 설정
        }

        // 메시지를 먼저 DB에 저장하고, 저장된 Message 객체를 사용합니다.
        Message savedMessage = messageDAO.saveMessage(message);
        if (savedMessage == null) {
            System.err.println("Failed to save message to DB. Not sending notification to client " + targetUserId);
            return; // DB 저장 실패 시 클라이언트에게 보내지 않고 종료
        }

        // 시스템 메시지의 경우, 시스템 봇 자신이 보낸 메시지로 간주하여 읽음 처리
        if (message.getMessageType() == MessageType.SYSTEM) {
            messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId()); // 시스템 메시지 발신자(시스템 유저)가 읽음 처리
        }


        if (handler != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("message", savedMessage); // 저장된 메시지 객체를 전달
            data.put("senderId", savedMessage.getSenderId()); // 메시지의 실제 발신자 ID (시스템봇이 보냈으면 시스템봇 ID)

            // 시스템 메시지인 경우 unreadRoomId를 해당 시스템 채팅방 ID로 설정하여 클라이언트에 전송
            if (savedMessage.getMessageType() == MessageType.SYSTEM) {
                data.put("unreadRoomId", savedMessage.getRoomId());
            }

            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System Notification", data));
            System.out.println("Sent system notification (real-time) to user " + targetUserId + " for room " + savedMessage.getRoomId());
        } else {
            System.out.println("User " + targetUserId + " is not connected. System notification saved to DB.");
        }
    }
}