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

    // 수정된 ensureUserSystemChatRoom 메서드
    public ChatRoom ensureUserSystemChatRoom(int userId) {
        if (userId == systemUserId) {
            return null;
        }
        ChatRoom systemChatRoom = chatRoomDAO.getOrCreateSystemChatRoomForUser(userId, systemUserId);

        if (systemChatRoom != null) {
            List<Message> existingMessages = messageDAO.getMessagesInRoom(systemChatRoom.getRoomId());
            if (existingMessages.isEmpty()) {
                String systemMessageContent = getTimelineHelpMessage();
                Message systemIntroMessage = new Message(
                        systemChatRoom.getRoomId(), // 시스템 채팅방 ID
                        this.systemUserId, // 시스템 봇 ID
                        "시스템", // 발신자 닉네임
                        MessageType.SYSTEM, // 메시지 타입
                        systemMessageContent, // 내용
                        false // 공지 아님
                );
                // 중요: 여기서는 sendMessageToUser를 직접 호출하지 않고,
                // 메시지를 DB에 저장만 하고, 만약 해당 사용자가 연결되어 있다면
                // ClientHandler를 통해 직접 보내도록 로직을 분리해야 합니다.
                // 현재 sendMessageToUser가 ensureUserSystemChatRoom을 재귀 호출하는 문제를 해결하기 위해
                // 메시지 저장만 먼저 수행하고, 클라이언트가 연결된 경우에만 전송합니다.

                Message savedMessage = messageDAO.saveMessage(systemIntroMessage); // 메시지 저장만
                if (savedMessage != null) {
                    ClientHandler handler = connectedClients.get(userId); // 사용자에게 연결된 핸들러를 가져옵니다.
                    if (handler != null) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("message", savedMessage);
                        data.put("senderId", savedMessage.getSenderId());
                        data.put("unreadRoomId", savedMessage.getRoomId()); // 시스템 메시지임을 알림
                        handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System Notification", data));
                        messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId()); // 시스템 봇이 보낸 메시지는 시스템 봇에 의해 읽음 처리
                        System.out.println("Sent initial system help message to user " + userId + " in room " + systemChatRoom.getRoomId());
                    } else {
                        System.out.println("User " + userId + " is not connected. Initial system message saved to DB.");
                    }
                } else {
                    System.err.println("Failed to save initial system message for user " + userId);
                }
            }
        }
        return systemChatRoom;
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Chat Server started on port " + PORT);

            System.out.println("Ensuring system chat rooms for all existing users...");
            List<User> allUsers = userDAO.getAllUsers();
            for (User user : allUsers) {
                if (user.getUserId() != systemUserId) {
                    // 여기에선 ensureUserSystemChatRoom만 호출하여 방이 생성되는지 확인합니다.
                    // 초기 메시지 전송 로직은 ensureUserSystemChatRoom 내부에서 처리되므로,
                    // 무한 재귀를 피하려면 여기서는 별도로 메시지를 보내지 않습니다.
                    ensureUserSystemChatRoom(user.getUserId());
                }
            }
            System.out.println("System chat room setup complete for existing users.");

            scheduler.scheduleAtFixedRate(this::checkUnreadMessages, 5, 5, TimeUnit.MINUTES);
            scheduler.scheduleAtFixedRate(this::clearExpiredNotices, 0, 1, TimeUnit.MINUTES);

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
                                        room.getRoomId(), // 여기서는 실제 채팅방 ID를 사용합니다.
                                        getSystemUserId(),
                                        "시스템",
                                        MessageType.SYSTEM,
                                        notificationContent,
                                        false
                                );
                                // 이 메시지는 사용자에게 직접 보내져야 합니다.
                                // sendMessageToUser는 시스템 채팅방을 보장하고 메시지를 저장합니다.
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
            // 클라이언트가 연결될 때 시스템 채팅방을 보장하고 초기 메시지를 보냅니다.
            // 이 시점에는 클라이언트 핸들러가 connectedClients 맵에 있으므로,
            // ensureUserSystemChatRoom 내부에서 바로 메시지 전송을 시도할 수 있습니다.
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
                    data.put("unreadRoomId", savedMessage.getRoomId()); // 시스템 메시지는 unreadRoomId로 처리
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
        // 메시지 저장 로직은 무조건 수행
        Message savedMessage = messageDAO.saveMessage(message);
        if (savedMessage == null) {
            System.err.println("Failed to save message to DB. Not sending notification to client " + targetUserId);
            return;
        }

        // 시스템 메시지는 시스템 봇에 의해 읽음 처리
        if (message.getMessageType() == MessageType.SYSTEM) {
            messageDAO.markMessageAsRead(savedMessage.getMessageId(), getSystemUserId());
        }

        // 클라이언트가 연결되어 있는 경우에만 실시간으로 전송
        ClientHandler handler = connectedClients.get(targetUserId);
        if (handler != null) {
            Map<String, Object> data = new HashMap<>();
            data.put("message", savedMessage);
            data.put("senderId", savedMessage.getSenderId());
            data.put("unreadRoomId", savedMessage.getRoomId()); // 시스템 메시지임을 알림 (클라이언트 측에서 시스템 채팅방 로드에 활용)
            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System Notification", data));
            System.out.println("Sent system notification (real-time) to user " + targetUserId + " for room " + savedMessage.getRoomId());
        } else {
            System.out.println("User " + targetUserId + " is not connected. System notification saved to DB.");
        }
    }


    private void clearExpiredNotices() {
        System.out.println("Checking and clearing expired notices...");
        Set<Integer> affectedRoomIds = messageDAO.clearExpiredNotices();

        if (!affectedRoomIds.isEmpty()) {
            System.out.println("Cleared notices in rooms: " + affectedRoomIds + ". Notifying clients.");
            for (int roomId : affectedRoomIds) {
                List<User> participantsInRoom = chatRoomDAO.getParticipantsInRoom(roomId);
                for (User participant : participantsInRoom) {
                    ClientHandler handler = connectedClients.get(participant.getUserId());
                    if (handler != null) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("roomId", roomId);
                        handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Expired notices cleared in room " + roomId + ", please refresh notice list", data));
                    }
                }
            }
        }
    }

    private String getTimelineHelpMessage() {
        return "/s [프로젝트명]: 새로운 프로젝트를 시작하고 타임라인에 기록합니다.(start)<br>" +
                "/c [프로젝트명]/[내용]: 특정 프로젝트에 대한 진행 내용이나 업데이트 사항을 추가합니다.(comment)<br>" +
                "/d [프로젝트명]: 특정 프로젝트를 종료하고 타임라인에 PROJECT_END 이벤트를 기록합니다.(done)<br>" +
                "/del [프로젝트명]: 특정 프로젝트와 관련된 모든 타임라인 이벤트(시작, 내용, 종료)를 영구적으로 삭제합니다.(delete)<br>" +
                "/h: 타임라인 명령어 사용법 및 설명을 다시 표시합니다.<br><br>" +
                "타임라인 팝업창(메뉴 -> 타임라인)에서 현재 진행 중인 프로젝트 목록을 확인하고, 각 이벤트 내용을 우클릭하여 수정하거나 삭제할 수 있습니다.";
    }
}