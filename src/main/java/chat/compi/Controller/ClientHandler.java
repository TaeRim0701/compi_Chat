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
    private int userId = -1; // 현재 핸들러에 연결된 사용자 ID

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
            // 출력 스트림을 먼저 생성해야 데드락 방지
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
                server.removeClient(userId); // 서버에서 클라이언트 제거 및 상태 업데이트
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
                    this.userId = loggedInUser.getUserId(); // 사용자 ID 저장
                    server.addClient(userId, this); // 서버에 클라이언트 등록
                    responseData.put("user", loggedInUser);
                    response = new ServerResponse(ServerResponse.ResponseType.LOGIN_SUCCESS, true, "Login successful", responseData);
                    sendFriendList();
                    sendChatRoomList(); // 로그인 성공 시 채팅방 목록 전송
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Invalid username or password", null);
                }
                sendResponse(response);
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
                server.removeClient(this.userId); // 서버에서 클라이언트 제거
                userDAO.updateUserStatus(this.userId, UserStatus.OFFLINE); // DB 상태 업데이트
                this.userId = -1; // 사용자 ID 초기화
                response = new ServerResponse(ServerResponse.ResponseType.SUCCESS, true, "Logout successful", null);
                sendResponse(response);
                closeConnection(); // 로그아웃 후 연결 종료
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
                        sendFriendList(); // 본인 친구 목록 업데이트
                        server.notifyFriendStatusChange(userDAO.getUserByUserId(this.userId)); // 본인 상태 변화 알림 (친구에게)
                        server.notifyFriendStatusChange(friendToAdd); // 친구 상태 변화 알림 (본인에게)
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

                ChatRoom newRoom = null;

                if (!isGroupChatRequest) { // 1:1 대화
                    if (invitedUserIds.size() != 2) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Private chat requires exactly 2 participants.", null);
                        sendResponse(response);
                        break;
                    }
                    List<Integer> sortedUserIds = invitedUserIds.stream().sorted().collect(Collectors.toList());
                    newRoom = chatRoomDAO.getExistingPrivateChatRoom(sortedUserIds.get(0), sortedUserIds.get(1));

                    if (newRoom == null) {
                        newRoom = chatRoomDAO.createChatRoom("", false, this.userId, invitedUserIds);
                    }
                } else { // 그룹 채팅
                    if (roomName == null || roomName.trim().isEmpty()) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Group chat room name is required.", null);
                        sendResponse(response);
                        break;
                    }
                    newRoom = chatRoomDAO.createChatRoom(roomName, true, this.userId, invitedUserIds);
                }

                if (newRoom != null) {
                    responseData.put("chatRoom", newRoom);
                    response = new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, true, "Chat room created", responseData);
                    sendResponse(response); // 요청한 클라이언트에게 먼저 응답

                    // 새로 생성/로딩된 방에 참여한 모든 사용자에게 채팅방 목록 업데이트 알림
                    // getParticipantsInRoom을 통해 최신 참여자 목록을 다시 가져오는 것이 안전합니다.
                    List<User> currentParticipantsOfNewRoom = chatRoomDAO.getParticipantsInRoom(newRoom.getRoomId());
                    for (User participant : currentParticipantsOfNewRoom) {
                        ClientHandler participantHandler = server.getConnectedClients().get(participant.getUserId());
                        if (participantHandler != null) {
                            participantHandler.sendChatRoomList(); // 각 참여자에게 최신 채팅방 목록을 보냅니다.
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

            case GET_MESSAGES_IN_ROOM: // 이 부분이 올바르게 존재하는지 확인합니다.
                int roomIdToGetMessages = (int) request.getData().get("roomId");
                List<Message> messages = messageDAO.getMessagesInRoom(roomIdToGetMessages);
                // 각 메시지에 대해 읽지 않은 사용자 수 계산 및 설정
                for (Message msg : messages) {
                    int readCount = messageDAO.getReadCountForMessage(msg.getMessageId());
                    // 현재 사용자가 참여하고 있는 채팅방의 총 참여자 수를 정확히 가져와야 합니다.
                    // ChatRoomDAO.getParticipantsInRoom(roomIdToGetMessages).size()가 정확한지 확인.
                    int totalParticipants = chatRoomDAO.getParticipantsInRoom(roomIdToGetMessages).size();
                    msg.setUnreadCount(totalParticipants - readCount);
                }
                responseData.put("roomId", roomIdToGetMessages);
                responseData.put("messages", messages);
                response = new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Messages loaded", responseData);
                sendResponse(response);
                break; // GET_MESSAGES_IN_ROOM 케이스 끝

            case SEND_MESSAGE:
                // ... (기존 SEND_MESSAGE 로직) ...
                int currentRoomId = (int) request.getData().get("roomId");
                String messageContent = (String) request.getData().get("content");
                MessageType messageType = MessageType.valueOf((String) request.getData().get("messageType"));
                boolean isNotice = (boolean) request.getData().get("isNotice");

                // this.userId가 유효한지 확인 (로그인되지 않은 상태에서 메시지 보내는 것 방지)
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
                // 서버가 메시지를 저장하고 브로드캐스트합니다. 이 내부에서 NEW_MESSAGE 응답이 처리됩니다.
                server.broadcastMessageToRoom(newMessage, this.userId);
                // 여기서는 별도의 SUCCESS 응답을 보내지 않아도 됩니다. (NEW_MESSAGE로 충분)
                break;

            case READ_MESSAGE:
                int messageIdToRead = (int) request.getData().get("messageId");
                if (messageDAO.markMessageAsRead(messageIdToRead, this.userId)) {
                    response = new ServerResponse(ServerResponse.ResponseType.MESSAGE_READ_CONFIRM, true, "Message marked as read", null);
                    // 해당 메시지가 속한 채팅방의 메시지들 다시 보내서 읽음 상태 업데이트
                    Message readMsg = messageDAO.getMessagesInRoom(messageDAO.getMessagesInRoom(messageIdToRead).get(0).getRoomId()) // 메시지 ID로 채팅방 ID 찾기
                            .stream().filter(m -> m.getMessageId() == messageIdToRead)
                            .findFirst().orElse(null);
                    if (readMsg != null) {
                        server.updateUnreadCountsForRoom(readMsg.getRoomId()); // 해당 방의 모든 메시지 읽음 상태 갱신
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
                    // 초대받은 사용자에게 채팅방 목록 업데이트 알림
                    ClientHandler invitedHandler = server.getConnectedClients().get(userIdToInvite);
                    if (invitedHandler != null) {
                        invitedHandler.sendChatRoomList();
                        // 초대받은 사용자에게 이전 대화 내용 전송
                        List<Message> previousMessages = messageDAO.getMessagesInRoom(roomIdToInvite);
                        Map<String, Object> roomMessagesData = new HashMap<>();
                        roomMessagesData.put("roomId", roomIdToInvite);
                        roomMessagesData.put("messages", previousMessages);
                        invitedHandler.sendResponse(new ServerResponse(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, true, "Previous messages for invited room", roomMessagesData));
                    }
                    // 기존 참여자들에게도 업데이트 알림
                    server.notifyRoomParticipantsOfRoomUpdate(roomIdToInvite);
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to invite user to room", null);
                }
                sendResponse(response);
                break;

            case GET_NOTICE_MESSAGES:
                List<Message> noticeMessages = messageDAO.getAllNoticeMessages();
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
                    String filePath = "server_uploads/" + fileName; // 서버에 저장될 경로

                    Files.write(Paths.get(filePath), fileBytes);

                    // 파일 경로를 메시지 내용으로 저장
                    User senderFile = userDAO.getUserByUserId(this.userId);
                    if (senderFile == null) {
                        response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Sender not found", null);
                        sendResponse(response);
                        break;
                    }
                    Message fileMessage = new Message(roomIdForFile, this.userId, senderFile.getNickname(),
                            MessageType.FILE, filePath, false); // isNotice는 false
                    server.broadcastMessageToRoom(fileMessage, this.userId); // 파일 메시지 브로드캐스트

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
                    // 본인의 상태 변경을 친구들에게 알림
                    User currentUser = userDAO.getUserByUserId(this.userId);
                    if (currentUser != null) {
                        currentUser.setStatus(newStatus); // 로컬 객체 상태도 업데이트
                        server.notifyFriendStatusChange(currentUser);
                    }
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to update status", null);
                }
                sendResponse(response);
                break;


            case LEAVE_CHAT_ROOM: // <-- 이 새로운 케이스를 추가합니다.
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

                    // 해당 채팅방의 모든 참여자에게 채팅방 업데이트 알림 (참여자 수 변경)
                    server.notifyRoomParticipantsOfRoomUpdate(roomIdToLeave);

                    // 나간 사용자에게 채팅방 목록을 다시 전송하여 나간 방이 목록에서 사라지도록 합니다.
                    sendChatRoomList();
                } else {
                    response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Failed to leave chat room.", null);
                    sendResponse(response);
                }
                break;

            default:
                response = new ServerResponse(ServerResponse.ResponseType.FAIL, false, "Unknown request type: " + request.getType(), null);
                sendResponse(response);
                break;
        }
    }

    // 클라이언트로 응답 전송
    public synchronized void sendResponse(ServerResponse response) {
        try {
            out.writeObject(response);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error sending response to client " + userId + ": " + e.getMessage());
            // 연결 끊김 처리 (선택 사항)
            server.removeClient(userId);
        }
    }

    // 현재 클라이언트의 친구 목록을 전송
    private void sendFriendList() {
        List<User> friends = userDAO.getFriends(this.userId);
        Map<String, Object> data = new HashMap<>();
        data.put("friends", friends);
        sendResponse(new ServerResponse(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, true, "Friend list updated", data));
    }

    // 현재 클라이언트의 채팅방 목록을 전송
    private void sendChatRoomList() {
        // userId가 유효한 경우에만 요청 (로그인 전에는 userId가 -1일 수 있음)
        if (this.userId != -1) {
            List<ChatRoom> chatRooms = chatRoomDAO.getChatRoomsByUserId(this.userId);
            Map<String, Object> data = new HashMap<>();
            data.put("chatRooms", chatRooms);
            sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, true, "Chat room list updated", data));
        } else {
            System.err.println("Attempted to send chat room list for unauthenticated user.");
            // 로그인되지 않은 사용자에게는 빈 목록 또는 실패 응답을 보낼 수 있습니다.
            sendResponse(new ServerResponse(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, false, "Not authenticated to get chat rooms.", new HashMap<>()));
        }
    }

    // 특정 메시지가 속한 방의 ID를 찾아주는 헬퍼 메서드 (효율을 위해 DAO에 추가하는 것이 좋음)
    private ChatRoom getChatRoomOfMessage(int messageId) {
        String sql = "SELECT room_id FROM messages WHERE message_id = ?";
        try (Connection conn = chat.compi.DB.DatabaseConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return chatRoomDAO.getChatRoomById(rs.getInt("room_id"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting chat room of message: " + e.getMessage());
        }
        return null;
    }

    // 연결 종료
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