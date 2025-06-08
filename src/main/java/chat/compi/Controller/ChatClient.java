// ChatClient.java
package chat.compi.Controller;

import chat.compi.Dto.ClientRequest;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.MessageType;
import chat.compi.Entity.User;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class ChatClient {
    private static final String SERVER_IP = "localhost";
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private User currentUser;

    private final Map<ServerResponse.ResponseType, Consumer<ServerResponse>> responseListeners = new HashMap<>();

    // 메시지 큐 (비동기 처리를 위해)
    private final BlockingQueue<ServerResponse> responseQueue = new LinkedBlockingQueue<>();

    public ChatClient() {
        // 응답 리스너 초기화 (각 GUI 클래스에서 setResponseListener를 통해 등록)
    }

    // 서버 연결
    public boolean connect() {
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            System.out.println("Connected to chat server.");

            // 서버 응답을 지속적으로 수신하는 스레드 시작
            new Thread(this::receiveResponses).start();
            return true;
        } catch (IOException e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            return false;
        }
    }

    // 서버로 요청 전송
    public synchronized void sendRequest(ClientRequest request) {
        try {
            out.writeObject(request);
            out.flush();
        } catch (IOException e) {
            System.err.println("Error sending request to server: " + e.getMessage());
            disconnect(); // 연결 끊김 처리 추가
        }
    }

    // 서버로부터 응답 수신
    private void receiveResponses() {
        try {
            while (socket.isConnected()) {
                ServerResponse response = (ServerResponse) in.readObject();
                responseQueue.put(response); // 'put' 메서드 사용
                System.out.println("Received response: " + response.getType());
            }
        } catch (IOException | ClassNotFoundException | InterruptedException e) {
            System.err.println("Error receiving response from server: " + e.getMessage());
            disconnect();
        }
    }

    // 큐에서 응답을 꺼내 처리하는 메서드 (GUI 스레드에서 호출)
    public void processResponses() {
        while (true) {
            try {
                ServerResponse response = responseQueue.take(); // 'take' 메서드 사용
                Consumer<ServerResponse> listener = responseListeners.get(response.getType());
                if (listener != null) {
                    listener.accept(response);
                } else {
                    System.out.println("No listener for response type: " + response.getType());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Response processing interrupted: " + e.getMessage());
                break;
            }
        }
    }

    // 응답 리스너 등록
    public void setResponseListener(ServerResponse.ResponseType type, Consumer<ServerResponse> listener) {
        responseListeners.put(type, listener);
    }

    // 새로운 메서드 추가: 특정 ResponseType에 등록된 리스너를 가져오는 메서드
    public Consumer<ServerResponse> getResponseListener(ServerResponse.ResponseType type) {
        return responseListeners.get(type);
    }

    // 현재 로그인 사용자 설정
    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    // 현재 로그인 사용자 가져오기
    public User getCurrentUser() {
        return currentUser;
    }

    // 연결 종료
    public void disconnect() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("Disconnected from chat server.");
            }
        } catch (IOException e) {
            System.err.println("Error disconnecting from server: " + e.getMessage());
        }
    }

    // --- 요청 전송 헬퍼 메서드 ---

    public void login(String username, String password) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("password", password);
        sendRequest(new ClientRequest(ClientRequest.RequestType.LOGIN, data));
    }

    public void register(String username, String password, String nickname) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        data.put("password", password);
        data.put("nickname", nickname);
        sendRequest(new ClientRequest(ClientRequest.RequestType.REGISTER, data));
    }

    public void logout() {
        sendRequest(new ClientRequest(ClientRequest.RequestType.LOGOUT, null));
    }

    public void getFriendList() {
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_FRIEND_LIST, null));
    }

    public void addFriend(String friendUsername) {
        Map<String, Object> data = new HashMap<>();
        data.put("friendUsername", friendUsername);
        sendRequest(new ClientRequest(ClientRequest.RequestType.ADD_FRIEND, data));
    }

    public void createChatRoom(String roomName, boolean isGroupChat, List<Integer> invitedUserIds) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomName", roomName);
        data.put("isGroupChat", isGroupChat);
        data.put("invitedUserIds", invitedUserIds);
        sendRequest(new ClientRequest(ClientRequest.RequestType.CREATE_CHAT_ROOM, data));
    }

    public void getChatRooms() {
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_CHAT_ROOMS, null));
    }

    public void getMessagesInRoom(int roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_MESSAGES_IN_ROOM, data));
    }

    public void sendMessage(int roomId, String content, MessageType messageType, boolean isNotice) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("content", content);
        data.put("messageType", messageType.name());
        data.put("isNotice", isNotice);
        sendRequest(new ClientRequest(ClientRequest.RequestType.SEND_MESSAGE, data));
    }

    public void markMessageAsRead(int messageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("messageId", messageId);
        sendRequest(new ClientRequest(ClientRequest.RequestType.READ_MESSAGE, data));
    }

    public void inviteUserToRoom(int roomId, int userIdToInvite) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("userId", userIdToInvite);
        sendRequest(new ClientRequest(ClientRequest.RequestType.INVITE_USER_TO_ROOM, data));
    }

    public void getNoticeMessages(int roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_NOTICE_MESSAGES, data));
    }

    public void getTimelineEvents(int roomId) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_TIMELINE_EVENTS, data));
    }

    public void uploadFile(int roomId, String fileName, byte[] fileBytes) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("fileName", fileName);
        data.put("fileBytes", fileBytes);
        sendRequest(new ClientRequest(ClientRequest.RequestType.UPLOAD_FILE, data));
    }

    public void downloadFile(String filePath) {
        Map<String, Object> data = new HashMap<>();
        data.put("filePath", filePath);
        sendRequest(new ClientRequest(ClientRequest.RequestType.DOWNLOAD_FILE, data));
    }

    public void setAwayStatus(boolean isAway) {
        Map<String, Object> data = new HashMap<>();
        data.put("isAway", isAway);
        sendRequest(new ClientRequest(ClientRequest.RequestType.SET_AWAY_STATUS, data));
    }

    public void getUnreadSystemNotifications() {
        sendRequest(new ClientRequest(ClientRequest.RequestType.GET_UNREAD_SYSTEM_NOTIFICATIONS, null));
    }

    public void addTimelineEvent(int roomId, String command, String description, String eventType, String eventName) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("command", command);
        data.put("description", description);
        data.put("eventType", eventType);
        data.put("eventName", eventName);
        sendRequest(new ClientRequest(ClientRequest.RequestType.ADD_TIMELINE_EVENT, data));
    }

    public void deleteTimelineEvent(int roomId, String projectName) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("projectName", projectName);
        sendRequest(new ClientRequest(ClientRequest.RequestType.DELETE_TIMELINE_EVENT, data));
    }

    public void addProjectContentToTimeline(int roomId, String projectName, String content) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("projectName", projectName);
        data.put("content", content);
        sendRequest(new ClientRequest(ClientRequest.RequestType.ADD_PROJECT_CONTENT_TO_TIMELINE, data));
    }

    public void endProjectToTimeline(int roomId, String projectName) {
        Map<String, Object> data = new HashMap<>();
        data.put("roomId", roomId);
        data.put("projectName", projectName);
        sendRequest(new ClientRequest(ClientRequest.RequestType.END_PROJECT_TO_TIMELINE, data));
    }

    // 새로운 markMessageAsNotice 헬퍼 메서드 (expiryTime 포함)
    public void markMessageAsNotice(int messageId, boolean isNotice, LocalDateTime expiryTime) {
        Map<String, Object> data = new HashMap<>();
        data.put("messageId", messageId);
        data.put("isNotice", isNotice);
        data.put("expiryTime", expiryTime); // 만료 시간 추가
        sendRequest(new ClientRequest(ClientRequest.RequestType.MARK_AS_NOTICE, data));
    }
}