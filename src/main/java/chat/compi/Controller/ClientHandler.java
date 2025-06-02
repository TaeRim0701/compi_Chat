package chat.compi.Controller;

import chat.compi.Dto.ClientRequest;
import chat.compi.Entity.*;
import chat.compi.Dto.ServerResponse;
import chat.compi.DB.ChatRoomDAO;
import chat.compi.DB.MessageDAO;
import chat.compi.DB.TimelineDAO;
import chat.compi.DB.UserDAO;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private ChatServer server;
    private ObjectInputStream in;
    private ObjectOutputStream out;
    private int userId = -1;

    private UserDAO userDAO;
    private MessageDAO messageDAO;
    private ChatRoomDAO chatRoomDAO;
    private TimelineDAO timelineDAO;

    public ClientHandler(Socket clientSocket, ChatServer server) {
        this.clientSocket = clientSocket;
        this.server = server;
        this.userDAO = new UserDAO();
        this.messageDAO = new MessageDAO();
        this.chatRoomDAO = new ChatRoomDAO();
        this.timelineDAO = new TimelineDAO();
        try {
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            in = new ObjectInputStream(clientSocket.getInputStream());
        } catch (IOException e) {
            System.err.println("Error creating streams: " + e.getMessage());
            closeConnection();
        }
    }

    @Override
    public void run() {
        try {
            while (clientSocket.isConnected()) {
                ClientRequest request = (ClientRequest) in.readObject();
                handleRequest(request);
            }
        } catch (EOFException e) {
            System.out.println("Client " + (userId != -1 ? userId : "unknown") + " disconnected gracefully.");
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Client " + (userId != -1 ? userId : "unknown") + " handler error: " + e.getMessage());
        } finally {
            if (userId != -1) {
                server.removeClient(userId);
            }
            closeConnection();
        }
    }

    private void handleRequest(ClientRequest request) {
        Map<String, Object> responseData = new HashMap<>();
        ServerResponse response = null;

        switch (request.getType()) {
            case LOGIN:
                String username = (String) request.getData().get("username");
                String password = (String) request.getData().get("password");
                User loggedInUser = userDAO.loginUser(username, password);
                if (loggedInUser != null) {
                    this.userId = loggedInUser.getUserId();
                    server.addClient(userId, this);
                    responseData.put("user", loggedInUser);
                    response = new ServerResponse(ServerResponse.ResponseType.LOGIN_SUCCESS, true, "Login successful", responseData);
                    sendResponse(response);
                    sendFriendList();
                    sendChatRoomList();
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Invalid username or password", null);
                    sendResponse(response);
                }
                break;

            case REGISTER:
                String regUsername = (String) request.getData().get("username");
                String regPassword = (String) request.getData().get("password");
                String regNickname = (String) request.getData().get("nickname");
                if (userDAO.registerUser(regUsername, regPassword, regNickname)) {
                    response = new ServerResponse(ServerResponse.ResponseType.REGISTER_SUCCESS, true, "Registration successful", null);
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Registration failed (username might exist)", null);
                }
                sendResponse(response);
                break;

            case LOGOUT:
                server.removeClient(this.userId);
                userDAO.updateUserStatus(this.userId, UserStatus.OFFLINE);
                this.userId = -1;
                response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Logout successful", null);
                sendResponse(response);
                closeConnection();
                break;

            case GET_FRIEND_LIST:
                sendFriendList();
                break;

            case ADD_FRIEND:
                String friendUsername = (String) request.getData().get("friendUsername");
                User friendToAdd = userDAO.getUserByUsername(friendUsername);
                if (friendToAdd != null) {
                    if (userDAO.addFriend(this.userId, friendToAdd.getUserId())) {
                        response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Friend added successfully", null);
                        sendFriendList();
                        server.notifyFriendStatusChange(userDAO.getUserByUserId(this.userId));
                        server.notifyFriendStatusChange(friendToAdd);
                    } else {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to add friend (already friends or database error)", null);
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Friend username not found", null);
                }
                sendResponse(response);
                break;

            case CREATE_CHAT_ROOM:
                String roomName = (String) request.getData().get("roomName");
                boolean isGroupChatRequest = (boolean) request.getData().get("isGroupChat");
                List<Integer> invitedUserIds = (List<Integer>) request.getData().get("invitedUserIds");

                if (invitedUserIds == null || invitedUserIds.isEmpty()) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Invalid participant list.", null);
                    sendResponse(response);
                    break;
                }

                ChatRoom createdOrFoundRoom = null;

                if (!isGroupChatRequest) {
                    if (invitedUserIds.size() != 2) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Private chat requires exactly 2 participants.", null);
                        sendResponse(response);
                        break;
                    }
                    List<Integer> sortedUserIds = invitedUserIds.stream().sorted().collect(Collectors.toList());
                    createdOrFoundRoom = chatRoomDAO.getExistingPrivateChatRoom(sortedUserIds.get(0), sortedUserIds.get(1));

                    if (createdOrFoundRoom == null) {
                        createdOrFoundRoom = chatRoomDAO.createChatRoom("", false, this.userId, invitedUserIds);
                    }
                } else {
                    if (roomName == null || roomName.trim().isEmpty()) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Group chat room name is required.", null);
                        sendResponse(response);
                        break;
                    }
                    createdOrFoundRoom = chatRoomDAO.createChatRoom(roomName, true, this.userId, invitedUserIds);
                }

                if (createdOrFoundRoom != null) {
                    responseData.put("chatRoom", createdOrFoundRoom);
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Chat room created", responseData);
                    sendResponse(response);

                    List<User> currentParticipantsOfRoom = chatRoomDAO.getParticipantsInRoom(createdOrFoundRoom.getRoomId());
                    String participantNames = currentParticipantsOfRoom.stream()
                            .map(User::getNickname)
                            .collect(Collectors.joining(", "));
                    String creationMessageContent = participantNames + " 님이 입장했습니다.";
                    Message creationSystemMessage = new Message(createdOrFoundRoom.getRoomId(), server.getSystemUserId(), "시스템", MessageType.SYSTEM, creationMessageContent, false);
                    server.broadcastMessageToRoom(creationSystemMessage, server.getSystemUserId());

                    for (User participant : currentParticipantsOfRoom) {
                        ClientHandler participantHandler = server.getConnectedClients().get(participant.getUserId());
                        if (participantHandler != null) {
                            participantHandler.sendChatRoomList();
                        }
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to create chat room", null);
                    sendResponse(response);
                }
                break;

            case GET_CHAT_ROOMS:
                sendChatRoomList();
                break;

            case GET_MESSAGES_IN_ROOM:
                int roomIdToGetMessages = (int) request.getData().get("roomId");
                List<Message> messages = messageDAO.getMessagesInRoom(roomIdToGetMessages);
                for (Message msg : messages) {
                    List<User> readers = messageDAO.getReadersForMessage(msg.getMessageId());
                    msg.setReaders(readers);
                    int totalParticipants = chatRoomDAO.getParticipantsInRoom(roomIdToGetMessages).size();
                    msg.setUnreadCount(totalParticipants - readers.size());
                }
                responseData.put("roomId", roomIdToGetMessages);
                responseData.put("messages", messages);
                response = new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Messages loaded", responseData);
                sendResponse(response);
                break;

            case SEND_MESSAGE:
                int currentRoomId = (int) request.getData().get("roomId");
                String messageContent = (String) request.getData().get("content");
                MessageType messageType = MessageType.valueOf((String) request.getData().get("messageType"));
                boolean isNotice = (boolean) request.getData().get("isNotice"); // 현재는 항상 false로 클라이언트에서 보냄

                if (this.userId == -1) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Not logged in. Cannot send message.", null);
                    sendResponse(response);
                    break;
                }

                User sender = userDAO.getUserByUserId(this.userId);
                if (sender == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Sender not found", null);
                    sendResponse(response);
                    break;
                }

                Message newMessage = new Message(currentRoomId, this.userId, sender.getNickname(), messageType, messageContent, isNotice);
                server.broadcastMessageToRoom(newMessage, this.userId);
                break;

            case READ_MESSAGE:
                int messageIdToRead = (int) request.getData().get("messageId");
                if (messageDAO.markMessageAsRead(messageIdToRead, this.userId)) {
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_READ_CONFIRM, true, "Message marked as read", null);
                    Message readMsg = messageDAO.getMessageById(messageIdToRead);
                    if (readMsg != null) {
                        server.updateUnreadCountsForRoom(readMsg.getRoomId());
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to mark message as read", null);
                }
                sendResponse(response);
                break;

            case INVITE_USER_TO_ROOM:
                int roomIdToInvite = (int) request.getData().get("roomId");
                int userIdToInvite = (int) request.getData().get("userId");
                if (chatRoomDAO.inviteUserToRoom(roomIdToInvite, userIdToInvite)) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "User invited to room", null);
                    sendResponse(response);

                    User invitedUser = userDAO.getUserByUserId(userIdToInvite);
                    if (invitedUser != null) {
                        String inviteMessageContent = invitedUser.getNickname() + " 님이 입장했습니다.";
                        Message inviteSystemMessage = new Message(roomIdToInvite, server.getSystemUserId(), "시스템", MessageType.SYSTEM, inviteMessageContent, false);
                        server.broadcastMessageToRoom(inviteSystemMessage, server.getSystemUserId());
                    }

                    ClientHandler invitedHandler = server.getConnectedClients().get(userIdToInvite);
                    if (invitedHandler != null) {
                        invitedHandler.sendChatRoomList();
                        List<Message> previousMessages = messageDAO.getMessagesInRoom(roomIdToInvite);
                        for (Message msg : previousMessages) {
                            List<User> readers = messageDAO.getReadersForMessage(msg.getMessageId());
                            msg.setReaders(readers);
                            int totalParticipants = chatRoomDAO.getParticipantsInRoom(roomIdToInvite).size();
                            msg.setUnreadCount(totalParticipants - readers.size());
                        }
                        Map<String, Object> roomMessagesData = new HashMap<>();
                        roomMessagesData.put("roomId", roomIdToInvite);
                        roomMessagesData.put("messages", previousMessages);
                        invitedHandler.sendResponse(new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Previous messages for invited room", roomMessagesData));
                    }
                    server.notifyRoomParticipantsOfRoomUpdate(roomIdToInvite);
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to invite user to room", null);
                }
                sendResponse(response);
                break;

            case MARK_AS_NOTICE: // 새로 추가된 요청 타입 처리
                int messageIdToMark = (int) request.getData().get("messageId");
                boolean markAsNotice = (boolean) request.getData().get("isNotice");
                int roomIdForNotice = (int) request.getData().get("roomId"); // 클라이언트에서 전송된 roomId
                if (messageDAO.updateMessageNoticeStatus(messageIdToMark, markAsNotice)) {
                    responseData.put("messageId", messageIdToMark);
                    responseData.put("isNotice", markAsNotice);
                    responseData.put("roomId", roomIdForNotice); // roomId를 응답에 포함
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_MARKED_AS_NOTICE_SUCCESS, true, "메시지 공지 상태가 업데이트되었습니다.", responseData);
                    sendResponse(response);

                    // 공지 상태 변경 후 해당 채팅방의 메시지 목록을 갱신하도록 요청 (UI 재렌더링)
                    // server.updateUnreadCountsForRoom(roomIdForNotice); // 이 메서드는 읽음 수 갱신 목적이므로 부적절할 수 있음.
                    // 대신 모든 참여자에게 NEW_MESSAGE를 보내서 메시지 자체를 업데이트하거나,
                    // ROOM_MESSAGES_UPDATE를 다시 보내도록 유도해야 함.
                    // 현재는 MESSAGE_MARKED_AS_NOTICE_SUCCESS 응답을 받은 클라이언트가 스스로 새로고침하도록 유도.

                    // 모든 참여자에게 공지 목록 갱신을 요청 (NoticeFrame용)
                    List<User> participantsInRoom = chatRoomDAO.getParticipantsInRoom(roomIdForNotice);
                    for (User participant : participantsInRoom) {
                        ClientHandler handler = server.getConnectedClients().get(participant.getUserId());
                        if (handler != null) {
                            // 클라이언트가 NOTICE_LIST_UPDATE를 받으면, 다시 getNoticeMessages를 호출하도록 유도
                            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Notice list needs refresh due to update", null));
                        }
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "메시지 공지 상태 업데이트에 실패했습니다.", null);
                    sendResponse(response);
                }
                break;

            case GET_NOTICE_MESSAGES:
                int noticeRoomId = (int) request.getData().get("roomId");
                List<Message> noticeMessages = messageDAO.getNoticeMessagesInRoom(noticeRoomId);
                responseData.put("noticeMessages", noticeMessages);
                response = new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Notice messages loaded", responseData);
                sendResponse(response);
                break;

            case GET_TIMELINE_EVENTS:
                int roomIdForTimeline = (int) request.getData().get("roomId");
                List<TimelineEvent> timelineEvents = timelineDAO.getTimelineEventsInRoom(roomIdForTimeline);
                responseData.put("roomId", roomIdForTimeline);
                responseData.put("timelineEvents", timelineEvents);
                response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline events loaded", responseData);
                sendResponse(response);
                break;

            case UPLOAD_FILE:
                try {
                    String fileName = (String) request.getData().get("fileName");
                    byte[] fileBytes = (byte[]) request.getData().get("fileBytes");
                    int roomIdForFile = (int) request.getData().get("roomId");
                    String filePath = "server_uploads/" + fileName;

                    Files.write(Paths.get(filePath), fileBytes);

                    User senderFile = userDAO.getUserByUserId(this.userId);
                    if (senderFile == null) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Sender not found", null);
                        sendResponse(response);
                        break;
                    }
                    Message fileMessage = new Message(roomIdForFile, this.userId, senderFile.getNickname(),
                            MessageType.FILE, filePath, false);
                    server.broadcastMessageToRoom(fileMessage, this.userId);

                    response = new ServerResponse(ServerResponse.ResponseType.FILE_UPLOAD_SUCCESS, true, "File uploaded successfully", null);
                } catch (IOException e) {
                    System.err.println("File upload failed: " + e.getMessage());
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "File upload failed: " + e.getMessage(), null);
                }
                sendResponse(response);
                break;

            case DOWNLOAD_FILE:
                try {
                    String filePath = (String) request.getData().get("filePath");
                    File file = new File(filePath);
                    if (file.exists()) {
                        byte[] fileBytes = Files.readAllBytes(file.toPath());
                        responseData.put("fileName", file.getName());
                        responseData.put("fileBytes", fileBytes);
                        response = new ServerResponse(ServerResponse.ResponseType.FILE_DOWNLOAD_SUCCESS, true, "File downloaded successfully", responseData);
                    } else {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "File not found on server", null);
                    }
                } catch (IOException e) {
                    System.err.println("File download failed: " + e.getMessage());
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "File download failed: " + e.getMessage(), null);
                }
                sendResponse(response);
                break;

            case SET_AWAY_STATUS:
                boolean isAway = (boolean) request.getData().get("isAway");
                UserStatus newStatus = isAway ? UserStatus.AWAY : UserStatus.ONLINE;
                if (userDAO.updateUserStatus(this.userId, newStatus)) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Status updated to " + newStatus, null);
                    User currentUser = userDAO.getUserByUserId(this.userId);
                    if (currentUser != null) {
                        currentUser.setStatus(newStatus);
                        server.notifyFriendStatusChange(currentUser);
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to update status", null);
                }
                sendResponse(response);
                break;

            case LEAVE_CHAT_ROOM:
                if (this.userId == -1) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Not logged in. Cannot leave chat room.", null);
                    sendResponse(response);
                    break;
                }

                int roomIdToLeave = (int) request.getData().get("roomId");
                boolean success = chatRoomDAO.leaveChatRoom(roomIdToLeave, this.userId);

                if (success) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Left chat room successfully.", null);
                    sendResponse(response);

                    server.notifyRoomParticipantsOfRoomUpdate(roomIdToLeave);
                    sendChatRoomList();
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to leave chat room.", null);
                }
                sendResponse(response);
                break;

            default:
                response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Unknown request type: " + request.getType(), null);
                sendResponse(response);
                break;
        }
    }

    public synchronized void sendResponse(ServerResponse response) {
        try {
            out.writeObject(response);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error sending response to client " + userId + ": " + e.getMessage());
            server.removeClient(userId);
        }
    }

    private void sendFriendList() {
        List<User> friends = userDAO.getFriends(this.userId);
        Map<String, Object> data = new HashMap<>();
        data.put("friends", friends);
        sendResponse(new ServerResponse(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, true, "Friend list updated", data));
    }

    public void sendChatRoomList() {
        if (this.userId != -1) {
            List<ChatRoom> chatRooms = chatRoomDAO.getChatRoomsByUserId(this.userId);
            Map<String, Object> data = new HashMap<>();
            data.put("chatRooms", chatRooms);
            sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, true, "Chat room list updated", data));
        } else {
            System.err.println("Attempted to send chat room list for unauthenticated user.");
            sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, false, "Not authenticated to get chat rooms.", new HashMap<>()));
        }
    }

    private void closeConnection() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (clientSocket != null) clientSocket.close();
        } catch (IOException e) {
            System.err.println("Error closing client handler resources: " + e.getMessage());
        }
    }
}