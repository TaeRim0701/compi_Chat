// ClientHandler.java
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        boolean success;

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
                    ChatRoom systemChatRoom = server.ensureUserSystemChatRoom(this.userId);
                    if (systemChatRoom != null) {
                        Map<String, Object> systemChatData = new HashMap<>();
                        systemChatData.put("chatRooms", chatRoomDAO.getChatRoomsByUserId(this.userId));
                        sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, true, "System chat room added", systemChatData));
                    }
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
                    User newUser = userDAO.getUserByUsername(regUsername);
                    if (newUser != null) {
                        server.ensureUserSystemChatRoom(newUser.getUserId());
                    }
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
                boolean isNewRoomCreated = false;

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
                        isNewRoomCreated = true;
                    }
                } else {
                    if (roomName == null || roomName.trim().isEmpty()) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Group chat room name is required.", null);
                        sendResponse(response);
                        break;
                    }
                    createdOrFoundRoom = chatRoomDAO.createChatRoom(roomName, true, this.userId, invitedUserIds);
                    isNewRoomCreated = true;
                }

                if (createdOrFoundRoom != null) {
                    responseData.put("chatRoom", createdOrFoundRoom);
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Chat room created", responseData);
                    sendResponse(response);

                    if (isNewRoomCreated) {
                        List<User> currentParticipantsOfRoom = chatRoomDAO.getParticipantsInRoom(createdOrFoundRoom.getRoomId());
                        String participantNames = currentParticipantsOfRoom.stream()
                                .map(User::getNickname)
                                .collect(Collectors.joining(", "));
                        String creationMessageContent = participantNames + " 님이 입장했습니다.";
                        Message creationSystemMessage = new Message(createdOrFoundRoom.getRoomId(), server.getSystemUserId(), "시스템", MessageType.SYSTEM, creationMessageContent, false);
                        server.broadcastMessageToRoom(creationSystemMessage, server.getSystemUserId());
                    }

                    for (User participant : chatRoomDAO.getParticipantsInRoom(createdOrFoundRoom.getRoomId())) {
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
                boolean isNotice = (boolean) request.getData().get("isNotice");

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

                if (messageContent.trim().equalsIgnoreCase("/h")) {
                    String systemMessageContent = getTimelineHelpMessage(); // 아래에서 정의할 헬퍼 메서드
                    server.sendMessageToUser(this.userId, new Message(
                            -1, // roomId는 sendMessageToUser에서 시스템 채팅방으로 설정될 것임
                            server.getSystemUserId(), // 시스템 봇 ID
                            "시스템", // 발신자 닉네임
                            MessageType.SYSTEM, // 메시지 타입
                            systemMessageContent, // 내용
                            false // 공지 아님
                    ));
                    // 사용자 채팅창에도 "명령어를 보냈습니다" 같은 피드백을 줄 수 있지만, 여기서는 시스템 메시지로 충분
                    sendResponse(new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "타임라인 도움말이 시스템 채팅방으로 전송되었습니다.", null));
                    break; // /h 명령어를 처리했으므로 일반 메시지 전송 로직은 건너뜀
                }

                Message newMessage = new Message(currentRoomId, this.userId, sender.getNickname(), messageType, messageContent, isNotice);
                server.broadcastMessageToRoom(newMessage, this.userId);
                break;

            case GET_TIMELINE_EVENTS:
                int roomIdForTimeline = (int) request.getData().get("roomId");
                List<TimelineEvent> timelineEvents = timelineDAO.getTimelineEventsInRoom(roomIdForTimeline);
                responseData.put("roomId", roomIdForTimeline);
                responseData.put("timelineEvents", timelineEvents);
                response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline events loaded", responseData);
                sendResponse(response);
                break;

            case ADD_TIMELINE_EVENT: // PROJECT_START 이벤트
                int timelineRoomId = (int) request.getData().get("roomId");
                String command = (String) request.getData().get("command"); // "/s"
                String description = (String) request.getData().get("description"); // "프로젝트 시작" (고정)
                String eventType = (String) request.getData().get("eventType"); // "PROJECT_START"
                String eventName = (String) request.getData().get("eventName"); // (프로젝트 이름)

                User eventSender = userDAO.getUserByUserId(this.userId);
                if (eventSender == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "이벤트 생성자 정보를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                // 타임라인에 기록될 실제 description: "(프로젝트 이름) 시작 / (닉네임)"
                String finalDescription = String.format("%s 시작 / %s", eventName, eventSender.getNickname());

                if (timelineDAO.saveTimelineEvent(timelineRoomId, this.userId, command, finalDescription, eventType, eventName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Timeline event added successfully", null);
                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(timelineRoomId);
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", timelineRoomId);
                    timelineData.put("timelineEvents", updatedTimelineEvents);
                    sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated", timelineData));

                    String chatMessageContent = String.format("'%s' 프로젝트가 시작되었습니다.", eventName);
                    Message projectStartChatMessage = new Message(timelineRoomId, server.getSystemUserId(), "시스템", MessageType.SYSTEM, chatMessageContent, false);
                    server.broadcastMessageToRoom(projectStartChatMessage, server.getSystemUserId());

                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to add timeline event", null);
                }
                sendResponse(response);
                break;

            case DELETE_TIMELINE_EVENT:
                int delRoomId = (int) request.getData().get("roomId");
                String delProjectName = (String) request.getData().get("projectName");

                // 프로젝트 존재 여부 확인
                if (!timelineDAO.isProjectNameExist(delRoomId, delProjectName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETE_FAIL, false, "현재 존재하지 않는 프로젝트입니다.", null);
                    sendResponse(response);
                    break;
                }

                int deletedRows = timelineDAO.deleteTimelineEventsByProjectName(delRoomId, delProjectName);

                if (deletedRows > 0) {
                    responseData.put("roomId", delRoomId);
                    responseData.put("projectName", delProjectName);
                    responseData.put("deletedCount", deletedRows);
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETED_SUCCESS, true, deletedRows + "개의 타임라인 이벤트가 삭제되었습니다.", responseData);
                    sendResponse(response);

                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(delRoomId);
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", delRoomId);
                    timelineData.put("timelineEvents", updatedTimelineEvents);
                    sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated after deletion", timelineData));

                    String chatMessageContent = String.format("'%s' 프로젝트 타임라인이 삭제되었습니다.", delProjectName);
                    Message projectDeleteChatMessage = new Message(delRoomId, server.getSystemUserId(), "시스템", MessageType.SYSTEM, chatMessageContent, false);
                    server.broadcastMessageToRoom(projectDeleteChatMessage, server.getSystemUserId());

                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETE_FAIL, false, "프로젝트 '" + delProjectName + "'에 해당하는 타임라인 이벤트를 찾을 수 없거나 삭제에 실패했습니다.", null);
                    sendResponse(response);
                }
                break;

            case ADD_PROJECT_CONTENT_TO_TIMELINE:
                int contentRoomId = (int) request.getData().get("roomId");
                String contentProjectName = (String) request.getData().get("projectName");
                String contentMessage = (String) request.getData().get("content");

                // 프로젝트 존재 여부 확인
                if (!timelineDAO.isProjectNameExist(contentRoomId, contentProjectName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "현재 존재하지 않는 프로젝트입니다.", null);
                    sendResponse(response);
                    break;
                }

                User contentSender = userDAO.getUserByUserId(this.userId);
                if (contentSender == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "이벤트 생성자 정보를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                String contentDescription = String.format("%s", contentMessage);

                if (timelineDAO.saveTimelineEvent(contentRoomId, this.userId, "/c", contentDescription, "PROJECT_CONTENT", contentProjectName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "프로젝트 타임라인에 내용이 추가되었습니다.", null);
                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(contentRoomId);
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", contentRoomId);
                    timelineData.put("timelineEvents", updatedTimelineEvents);
                    sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated with new project content", timelineData));

                    String chatMessageContent = String.format("'%s' 프로젝트에 새 내용이 추가되었습니다.", contentProjectName);
                    Message projectContentChatMessage = new Message(contentRoomId, server.getSystemUserId(), "시스템", MessageType.SYSTEM, chatMessageContent, false);
                    server.broadcastMessageToRoom(projectContentChatMessage, server.getSystemUserId());

                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "프로젝트 타임라인에 내용을 추가하는 데 실패했습니다.", null);
                }
                sendResponse(response);
                break;

            case END_PROJECT_TO_TIMELINE: // PROJECT_END 이벤트
                int endRoomId = (int) request.getData().get("roomId");
                String endProjectName = (String) request.getData().get("projectName");

                // 프로젝트 존재 여부 확인
                if (!timelineDAO.isProjectNameExist(endRoomId, endProjectName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "현재 존재하지 않는 프로젝트입니다.", null);
                    sendResponse(response);
                    break;
                }

                User endSender = userDAO.getUserByUserId(this.userId);
                if (endSender == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "이벤트 생성자 정보를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                String endDescription = String.format("%s 종료 / %s", endProjectName, endSender.getNickname());

                if (timelineDAO.saveTimelineEvent(endRoomId, this.userId, "/d", endDescription, "PROJECT_END", endProjectName)) {
                    response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "프로젝트가 종료되었습니다.", null);
                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(endRoomId);
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", endRoomId);
                    timelineData.put("timelineEvents", updatedTimelineEvents);
                    sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated with project end event", timelineData));

                    String chatMessageContent = String.format("'%s' 프로젝트가 종료되었습니다.", endProjectName);
                    Message projectEndChatMessage = new Message(endRoomId, server.getSystemUserId(), "시스템", MessageType.SYSTEM, chatMessageContent, false);
                    server.broadcastMessageToRoom(projectEndChatMessage, server.getSystemUserId());

                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "프로젝트 종료 이벤트를 추가하는 데 실패했습니다.", null);
                }
                sendResponse(response);
                break;


            case READ_MESSAGE:
                int messageIdToRead = (int) request.getData().get("messageId");
                int readStatus = messageDAO.markMessageAsReadStatus(messageIdToRead, this.userId);

                if (readStatus == 1) {
                    responseData.put("messageId", messageIdToRead);
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_READ_CONFIRM, true, "Message marked as read", responseData);
                    sendResponse(response);
                    Message readMsg = messageDAO.getMessageById(messageIdToRead);
                    if (readMsg != null) {
                        server.updateUnreadCountsForRoom(readMsg.getRoomId());
                    }
                } else if (readStatus == 0) {
                    responseData.put("messageId", messageIdToRead);
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_ALREADY_READ, true, "Message was already marked as read", responseData);
                    sendResponse(response);
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to mark message as read due to DB error", null);
                    sendResponse(response);
                }
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

            case MARK_AS_NOTICE:
                int messageIdToMark = (int) request.getData().get("messageId");
                boolean markAsNotice = (boolean) request.getData().get("isNotice");
                // LocalDateTime 처리: 클라이언트에서 LocalDateTime 객체로 보낼 것.
                LocalDateTime expiryTime = (LocalDateTime) request.getData().get("expiryTime");
                int roomIdForNotice = (int) request.getData().get("roomId");

                if (messageDAO.updateMessageNoticeStatus(messageIdToMark, markAsNotice, expiryTime)) {
                    responseData.put("messageId", messageIdToMark);
                    responseData.put("isNotice", markAsNotice);
                    responseData.put("roomId", roomIdForNotice);
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_MARKED_AS_NOTICE_SUCCESS, true, "메시지 공지 상태가 업데이트되었습니다.", responseData);
                    sendResponse(response);

                    List<User> participantsInRoom = chatRoomDAO.getParticipantsInRoom(roomIdForNotice);
                    for (User participant : participantsInRoom) {
                        ClientHandler handler = server.getConnectedClients().get(participant.getUserId());
                        if (handler != null) {
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

                ChatRoom roomToLeave = chatRoomDAO.getChatRoomById(roomIdToLeave);
                if (roomToLeave != null) {
                    // ChatServer에서 systemUserId를 가져옵니다.
                    int systemUserId = server.getSystemUserId();
                    List<User> participants = chatRoomDAO.getParticipantsInRoom(roomIdToLeave);

                    boolean isSystemRoom = false;
                    if (!roomToLeave.isGroupChat() && participants.size() == 2) {
                        // 1:1 채팅이고 참여자가 2명일 때, 그 중 한 명이 시스템 봇인지 확인
                        boolean containsCurrentUser = false;
                        boolean containsSystemUser = false;
                        for (User p : participants) {
                            if (p.getUserId() == this.userId) {
                                containsCurrentUser = true;
                            }
                            if (p.getUserId() == systemUserId) {
                                containsSystemUser = true;
                            }
                        }
                        if (containsCurrentUser && containsSystemUser) {
                            isSystemRoom = true;
                        }
                    }

                    if (isSystemRoom) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "시스템 채팅방은 나갈 수 없습니다.", null);
                        sendResponse(response);
                        break;
                    }
                }

                success = chatRoomDAO.leaveChatRoom(roomIdToLeave, this.userId);
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

            case RESEND_NOTIFICATION:
                int renotifyRoomId = (int) request.getData().get("roomId"); // 원본 메시지가 있는 채팅방 ID
                int renotifyMessageId = (int) request.getData().get("messageId"); // 원본 메시지 ID

                Message originalMessage = messageDAO.getMessageById(renotifyMessageId); // 원본 메시지 조회
                if (originalMessage == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "원본 메시지를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                User senderUser = userDAO.getUserByUserId(this.userId); // 재알림을 요청한 사용자
                if (senderUser != null) {
                    String senderConfirmContent = "재알림이 발송됐습니다: 원본 메시지 (\"" + originalMessage.getContent() + "\")";
                    Message senderConfirmationMessage = new Message(
                            -1, // roomId는 sendMessageToUser에서 시스템 채팅방으로 설정될 것임.
                            server.getSystemUserId(), // 시스템 봇 ID
                            "시스템", // 발신자 닉네임
                            MessageType.SYSTEM, // 메시지 타입
                            senderConfirmContent, // 내용
                            false // 공지 아님
                    );
                    server.sendMessageToUser(this.userId, senderConfirmationMessage); // 재알림 요청자에게 확인 메시지 전송
                }

                // 원본 메시지가 있었던 방의 참여자 목록을 가져옵니다.
                List<User> roomParticipants = chatRoomDAO.getParticipantsInRoom(renotifyRoomId);

                for (User participant : roomParticipants) {
                    // 재알림 요청자이거나, 이미 메시지를 읽은 사용자에게는 알림을 보내지 않습니다.
                    if (participant.getUserId() == this.userId || messageDAO.isMessageReadByUser(originalMessage.getMessageId(), participant.getUserId())) {
                        continue;
                    }

                    // 메시지를 아직 읽지 않은 사용자에게만 알림을 보냅니다.
                    String notificationContent = String.format(
                            "채팅방 '%s'에서 '%s'님이 보낸 메시지를 아직 읽지 않으셨습니다: \"%s\"", // 이 부분이 요청하신 메시지 형식입니다.
                            chatRoomDAO.getChatRoomById(renotifyRoomId).getRoomName(), // 원본 메시지 방 이름
                            originalMessage.getSenderNickname(), // 원본 메시지 발신자 닉네임
                            originalMessage.getContent() // 원본 메시지 내용
                    );

                    Message systemNotificationToUnreadUser = new Message(
                            -1, // roomId는 sendMessageToUser에서 시스템 채팅방으로 설정될 것임.
                            server.getSystemUserId(), // 시스템 봇 ID
                            "시스템", // 발신자 닉네임
                            MessageType.SYSTEM, // 메시지 타입
                            notificationContent, // 내용
                            false // 공지 아님
                    );
                    // 이 메시지는 해당 사용자의 시스템 채팅방으로 전송됩니다.
                    server.sendMessageToUser(participant.getUserId(), systemNotificationToUnreadUser);
                }
                response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "재알림 요청 처리 완료", null);
                sendResponse(response);
                break;

            case GET_UNREAD_SYSTEM_NOTIFICATIONS:
                if (this.userId == -1) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Not logged in. Cannot get unread system notifications.", null);
                    sendResponse(response);
                    break;
                }
                ChatRoom systemRoom = server.ensureUserSystemChatRoom(this.userId);
                if (systemRoom == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "System chat room not found for user.", null);
                    sendResponse(response);
                    break;
                }
                List<Message> systemChatMessages = messageDAO.getMessagesInRoom(systemRoom.getRoomId());

                responseData.put("messages", systemChatMessages);
                responseData.put("unreadRoomId", systemRoom.getRoomId());
                response = new ServerResponse(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, true, "System chat messages loaded", responseData);
                sendResponse(response);
                break;

            case CLEAR_EXPIRED_NOTICES: // 새로운 요청 타입 처리
            // messageDAO.clearExpiredNotices()는 이제 Set<Integer>를 반환함
            Set<Integer> affectedRoomIds = messageDAO.clearExpiredNotices(); // int clearedCount 대신 Set<Integer>로 받음
            System.out.println("Cleared notices in rooms: " + affectedRoomIds);

            // 만료된 공지가 정리된 각 방의 클라이언트에게 공지 목록 업데이트 알림을 보냄
            // (ChatServer의 clearExpiredNotices 로직과 동일하게 동작하도록 복사)
            if (!affectedRoomIds.isEmpty()) {
                for (int roomId : affectedRoomIds) {
                    List<User> participantsInRoom = chatRoomDAO.getParticipantsInRoom(roomId);
                    for (User participant : participantsInRoom) {
                        ClientHandler handler = server.getConnectedClients().get(participant.getUserId());
                        if (handler != null) {
                            Map<String, Object> data = new HashMap<>();
                            data.put("roomId", roomId); // 어떤 방의 공지가 업데이트되었는지 클라이언트에게 알려줌
                            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, true, "Expired notices cleared in room " + roomId + ", please refresh notice list", data));
                        }
                    }
                }
            }

            // 요청한 클라이언트에게는 단순히 성공 응답만 보냄 (clearedCount 정보는 필수는 아님)
            response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Expired notices scan complete.", null);
            sendResponse(response);
            break;

            case UPDATE_TIMELINE_EVENT:
                int eventIdToUpdate = (int) request.getData().get("eventId");
                String newDescription = (String) request.getData().get("newDescription");

                // 수정될 이벤트의 roomId를 가져오기 위해 먼저 조회
                TimelineEvent eventToModify = timelineDAO.getTimelineEventById(eventIdToUpdate);
                if (eventToModify == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_UPDATED_FAIL, false, "수정할 타임라인 이벤트를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                if (timelineDAO.updateTimelineEventDescription(eventIdToUpdate, newDescription)) {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_UPDATED_SUCCESS, true, "타임라인 이벤트가 성공적으로 수정되었습니다.", null);
                    sendResponse(response); // 요청한 클라이언트에게 먼저 응답

                    // 해당 방의 모든 참가자에게 타임라인 업데이트 알림
                    // 수정된 이벤트의 roomId를 사용하여 최신 타임라인 이벤트 목록을 가져옴
                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(eventToModify.getRoomId());
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", eventToModify.getRoomId());
                    timelineData.put("timelineEvents", updatedTimelineEvents);

                    List<User> participants = chatRoomDAO.getParticipantsInRoom(eventToModify.getRoomId());
                    for (User participant : participants) {
                        ClientHandler handler = server.getConnectedClients().get(participant.getUserId());
                        if (handler != null) {
                            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated after event modification", timelineData));
                        }
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_UPDATED_FAIL, false, "타임라인 이벤트 수정에 실패했습니다.", null);
                    sendResponse(response);
                }
                break;

            case DELETE_SINGLE_TIMELINE_EVENT:
                int eventIdToDelete = (int) request.getData().get("eventId");

                // 삭제하기 전에 해당 이벤트가 속한 room_id를 알아내야 함
                TimelineEvent eventToDelete = timelineDAO.getTimelineEventById(eventIdToDelete);
                if (eventToDelete == null) {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETE_FAIL, false, "삭제할 타임라인 이벤트를 찾을 수 없습니다.", null);
                    sendResponse(response);
                    break;
                }

                int deletedSingleEventRows = timelineDAO.deleteTimelineEventById(eventIdToDelete);
                if (deletedSingleEventRows > 0) {
                    responseData.put("roomId", eventToDelete.getRoomId());
                    responseData.put("eventId", eventIdToDelete);
                    responseData.put("deletedCount", deletedSingleEventRows);
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETED_SUCCESS, true, "타임라인 이벤트가 성공적으로 삭제되었습니다.", responseData);
                    sendResponse(response); // 요청한 클라이언트에게 먼저 응답

                    // 해당 방의 모든 참가자에게 타임라인 업데이트 알림
                    List<TimelineEvent> updatedTimelineEvents = timelineDAO.getTimelineEventsInRoom(eventToDelete.getRoomId());
                    Map<String, Object> timelineData = new HashMap<>();
                    timelineData.put("roomId", eventToDelete.getRoomId());
                    timelineData.put("timelineEvents", updatedTimelineEvents);

                    List<User> participants = chatRoomDAO.getParticipantsInRoom(eventToDelete.getRoomId());
                    for (User participant : participants) {
                        ClientHandler handler = server.getConnectedClients().get(participant.getUserId());
                        if (handler != null) {
                            handler.sendResponse(new ServerResponse(ServerResponse.ResponseType.TIMELINE_UPDATE, true, "Timeline updated after event deletion", timelineData));
                        }
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.TIMELINE_EVENT_DELETE_FAIL, false, "타임라인 이벤트 삭제에 실패했습니다.", null);
                    sendResponse(response);
                }
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

    private String getTimelineHelpMessage() {
        return "/s [프로젝트명]: 새로운 프로젝트를 시작하고 타임라인에 기록합니다.(start)<br>" +
                "/c [프로젝트명]/[내용]: 특정 프로젝트에 대한 진행 내용이나 업데이트 사항을 추가합니다.(comment)<br>" +
                "/d [프로젝트명]: 특정 프로젝트를 종료하고 타임라인에 PROJECT_END 이벤트를 기록합니다.(done)<br>" +
                "/del [프로젝트명]: 특정 프로젝트와 관련된 모든 타임라인 이벤트(시작, 내용, 종료)를 영구적으로 삭제합니다.(delete)<br>" +
                "/h: 타임라인 명령어 사용법 및 설명을 다시 표시합니다.<br><br>" + // 두 번 줄바꿈
                "타임라인 팝업창(메뉴 -> 타임라인)에서 현재 진행 중인 프로젝트 목록을 확인하고, 각 이벤트 내용을 우클릭하여 수정하거나 삭제할 수 있습니다.";
    }
}