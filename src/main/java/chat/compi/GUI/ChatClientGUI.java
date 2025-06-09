// ChatClientGUI.java
package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ClientRequest;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.AttributeSet;
import javax.swing.event.HyperlinkEvent;

@SuppressWarnings("unchecked")
public class ChatClientGUI extends JFrame {
    private ChatClient chatClient;
    private User currentUser;

    private DefaultListModel<User> friendListModel;
    private JList<User> friendList;
    private DefaultListModel<ChatRoom> chatRoomListModel;
    private JList<ChatRoom> chatRoomList;

    private JButton addFriendButton;
    private JButton createChatRoomButton;
    private JToggleButton settingsToggleButton;

    private ChatRoom currentChatRoom;

    private Map<Integer, ChatRoomDialog> openChatRoomDialogs;

    private int pendingPrivateChatUserId = -1;

    private JFrame noticeFrame;
    private JEditorPane noticeArea;
    private HTMLEditorKit noticeEditorKit;
    private HTMLDocument noticeDoc;

    private JFrame timelineFrame;
    private JEditorPane timelineArea; // JTextArea -> JEditorPane 변경
    private HTMLEditorKit timelineEditorKit; // 새로 추가
    private HTMLDocument timelineDoc; // 새로 추가

    private JList<String> projectList;
    private DefaultListModel<String> projectListModel;
    private List<TimelineEvent> allTimelineEvents;

    private List<Message> currentNoticeMessages;

    public JFrame getNoticeFrame() {
        return noticeFrame;
    }

    public JFrame getTimelineFrame() {
        return timelineFrame;
    }

    public ChatClientGUI(ChatClient client) {
        this.chatClient = client;
        this.currentUser = client.getCurrentUser();
        setTitle("Java Chat Client - " + currentUser.getNickname() + "(" + currentUser.getUsername() + ")");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(null);

        openChatRoomDialogs = new HashMap<>();
        currentNoticeMessages = new ArrayList<>();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int confirm = JOptionPane.showConfirmDialog(ChatClientGUI.this, "정말로 종료하시겠습니까? 로그아웃됩니다.", "종료 확인", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    chatClient.logout();
                    chatClient.disconnect();
                    for (ChatRoomDialog dialog : openChatRoomDialogs.values()) {
                        dialog.dispose();
                    }
                    System.exit(0);
                }
            }
        });

        setupResponseListeners();
        initComponents();

        chatClient.getFriendList();
        chatClient.getChatRooms();

        initNoticeFrame();
        initTimelineFrame();
    }

    private void handleMessageReadConfirm(ServerResponse response) {
        System.out.println("DEBUG: Message read confirmed from server. Message ID: " + response.getData().get("messageId"));
    }

    private void handleMessageAlreadyRead(ServerResponse response) {
        System.out.println("DEBUG: Message " + response.getData().get("messageId") + " was already read. No action needed.");
    }

    private void handleTimelineEventUpdatedSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "수정 성공", JOptionPane.INFORMATION_MESSAGE);
            // 타임라인 업데이트는 TIMELINE_UPDATE 응답으로 자동 처리됨
        });
    }

    private void handleTimelineEventUpdatedFail(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "타임라인 이벤트 수정 실패: " + response.getMessage(), "수정 실패", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void setupResponseListeners() {
        chatClient.setResponseListener(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, this::handleFriendListUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.FRIEND_STATUS_UPDATE, this::handleFriendStatusUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.CHAT_ROOMS_UPDATE, this::handleChatRoomsUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.NEW_MESSAGE, this::handleNewMessage);
        chatClient.setResponseListener(ServerResponse.ResponseType.FAIL, this::handleServerFailure);
        chatClient.setResponseListener(ServerResponse.ResponseType.NOTICE_LIST_UPDATE, this::handleNoticeListUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.TIMELINE_UPDATE, this::handleTimelineUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.FILE_UPLOAD_SUCCESS, this::handleFileUploadSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.FILE_DOWNLOAD_SUCCESS, this::handleFileDownloadSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.SYSTEM_NOTIFICATION, this::handleSystemNotification);
        chatClient.setResponseListener(ServerResponse.ResponseType.ROOM_MESSAGES_UPDATE, this::handleRoomMessagesUpdate);
        chatClient.setResponseListener(ServerResponse.ResponseType.SUCCESS, this::handleGeneralSuccessResponse);
        chatClient.setResponseListener(ServerResponse.ResponseType.MESSAGE_MARKED_AS_NOTICE_SUCCESS, this::handleMessageMarkedAsNoticeSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.MESSAGE_READ_CONFIRM, this::handleMessageReadConfirm);
        chatClient.setResponseListener(ServerResponse.ResponseType.MESSAGE_ALREADY_READ, this::handleMessageAlreadyRead);
        chatClient.setResponseListener(ServerResponse.ResponseType.TIMELINE_EVENT_DELETED_SUCCESS, this::handleTimelineEventDeletedSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.TIMELINE_EVENT_DELETE_FAIL, this::handleTimelineEventDeleteFail);
        chatClient.setResponseListener(ServerResponse.ResponseType.TIMELINE_EVENT_UPDATED_SUCCESS, this::handleTimelineEventUpdatedSuccess); // 새로 추가
        chatClient.setResponseListener(ServerResponse.ResponseType.TIMELINE_EVENT_UPDATED_FAIL, this::handleTimelineEventUpdatedFail); // 새로 추가
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        JPanel topPanel = new JPanel(new BorderLayout());
        JLabel welcomeLabel = new JLabel("환영합니다, " + currentUser.getNickname() + "님!");
        welcomeLabel.setHorizontalAlignment(SwingConstants.LEFT);
        welcomeLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        topPanel.add(welcomeLabel, BorderLayout.WEST);

        settingsToggleButton = new JToggleButton("설정");
        settingsToggleButton.addActionListener(e -> toggleSettingsPanel());
        topPanel.add(settingsToggleButton, BorderLayout.EAST);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 0));

        JPanel friendPanel = new JPanel(new BorderLayout());
        friendPanel.setBorder(new TitledBorder("친구 목록"));
        friendListModel = new DefaultListModel<>();
        friendList = new JList<>(friendListModel);
        friendList.setCellRenderer(new FriendListCellRenderer());
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendPanel.add(new JScrollPane(friendList), BorderLayout.CENTER);

        addFriendButton = new JButton("친구 추가");
        addFriendButton.addActionListener(e -> showAddFriendDialog());
        friendPanel.add(addFriendButton, BorderLayout.SOUTH);
        centerPanel.add(friendPanel);

        friendList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    User selectedUser = friendList.getSelectedValue();
                    if (selectedUser != null) {
                        pendingPrivateChatUserId = selectedUser.getUserId();
                        List<Integer> participants = new ArrayList<>();
                        participants.add(currentUser.getUserId());
                        participants.add(selectedUser.getUserId());
                        chatClient.createChatRoom("", false, participants);
                    }
                }
            }
        });

        friendList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (!friendList.isSelectionEmpty()) {
                    chatRoomList.clearSelection();
                }
            }
        });

        JPanel chatRoomPanel = new JPanel(new BorderLayout());
        chatRoomPanel.setBorder(new TitledBorder("채팅방 목록"));
        chatRoomListModel = new DefaultListModel<>();
        chatRoomList = new JList<>(chatRoomListModel);
        chatRoomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatRoomPanel.add(new JScrollPane(chatRoomList), BorderLayout.CENTER);

        createChatRoomButton = new JButton("채팅방 생성");
        createChatRoomButton.addActionListener(e -> showCreateChatRoomDialog());
        chatRoomPanel.add(createChatRoomButton, BorderLayout.SOUTH);

        centerPanel.add(chatRoomPanel);
        mainPanel.add(centerPanel, BorderLayout.CENTER);

        chatRoomList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    ChatRoom selectedRoom = chatRoomList.getSelectedValue();
                    if (selectedRoom != null) {
                        currentChatRoom = selectedRoom;
                        openChatRoomDialog(selectedRoom);
                    }
                }
            }
        });

        chatRoomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (!chatRoomList.isSelectionEmpty()) {
                    friendList.clearSelection();
                }
            }
        });

        add(mainPanel);
    }

    private void openChatRoomDialog(ChatRoom room) {
        openChatRoomDialog(room, -1);
    }

    private void openChatRoomDialog(ChatRoom room, int targetMessageId) {
        System.out.println("openChatRoomDialog called for room ID: " + room.getRoomId() + ", name: " + room.getRoomName() + ", targetMessageId: " + targetMessageId);
        ChatRoomDialog dialog = openChatRoomDialogs.get(room.getRoomId());
        if (dialog == null) {
            System.out.println("  Dialog for room " + room.getRoomId() + " not found in map. Creating new dialog.");
            dialog = new ChatRoomDialog(this, chatClient, room);
            openChatRoomDialogs.put(room.getRoomId(), dialog);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    openChatRoomDialogs.remove(room.getRoomId());
                    chatRoomList.clearSelection();
                    System.out.println("  ChatRoomDialog for room " + room.getRoomId() + " closed and removed from map. Map size: " + openChatRoomDialogs.size());
                }
            });
        } else {
            System.out.println("  Dialog for room " + room.getRoomId() + " found in map. Re-using existing dialog.");
            dialog.updateChatRoomInfo(room);
        }
        dialog.setVisible(true);
        dialog.toFront();
        dialog.loadMessages(targetMessageId);
        System.out.println("Opened chat room dialog for room ID: " + room.getRoomId() + " (" + room.getRoomName() + ")");
    }

    private void handleFriendListUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            List<User> friends = (List<User>) response.getData().get("friends");
            friendListModel.clear();
            if (friends != null) {
                for (User friend : friends) {
                    friendListModel.addElement(friend);
                }
            }
        });
    }

    private void handleFriendStatusUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int userId = (int) response.getData().get("userId");
            String status = (String) response.getData().get("status");

            for (int i = 0; i < friendListModel.size(); i++) {
                User friend = friendListModel.getElementAt(i);
                if (friend.getUserId() == userId) {
                    friend.setStatus(UserStatus.valueOf(status));
                    friendList.repaint();
                    break;
                }
            }
        });
    }

    private void handleChatRoomCreationSuccessLogic(ServerResponse response) {
        ChatRoom createdOrFoundRoom = (ChatRoom) response.getData().get("chatRoom");
        System.out.println("Chat room operation successful: " + createdOrFoundRoom.getRoomName() + ", Room ID: " + createdOrFoundRoom.getRoomId());
        System.out.println("  Is Group Chat: " + createdOrFoundRoom.isGroupChat());
        System.out.println("  Participants: " + createdOrFoundRoom.getParticipants().stream().map(User::getNickname).collect(Collectors.joining(", ")));

        SwingUtilities.invokeLater(() -> {
            System.out.println("Attempting to open chat room for newly created/found room: " + createdOrFoundRoom.getRoomId());
            openChatRoomDialog(createdOrFoundRoom);

            for (int i = 0; i < chatRoomListModel.size(); i++) {
                if (chatRoomListModel.getElementAt(i).getRoomId() == createdOrFoundRoom.getRoomId()) {
                    chatRoomList.setSelectedIndex(i);
                    chatRoomList.ensureIndexIsVisible(i);
                    System.out.println("  Selected chat room in list: " + createdOrFoundRoom.getRoomName());
                    break;
                }
            }
        });

        pendingPrivateChatUserId = -1;
        System.out.println("  pendingPrivateChatUserId reset to: " + pendingPrivateChatUserId);
    }

    private void handleChatRoomsUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            List<ChatRoom> updatedChatRooms = (List<ChatRoom>) response.getData().get("chatRooms");

            if (updatedChatRooms == null) {
                System.err.println("Received null chatRooms list from server. Returning empty list.");
                updatedChatRooms = new ArrayList<>();
            }

            List<Integer> currentRoomIdsInModel = updatedChatRooms.stream()
                    .map(ChatRoom::getRoomId)
                    .collect(Collectors.toList());
            List<Integer> dialogsToClose = new ArrayList<>();
            for (Integer roomId : openChatRoomDialogs.keySet()) {
                if (!currentRoomIdsInModel.contains(roomId)) {
                    dialogsToClose.add(roomId);
                }
            }
            for (Integer roomId : dialogsToClose) {
                ChatRoomDialog dialog = openChatRoomDialogs.remove(roomId);
                if (dialog != null) {
                    dialog.dispose();
                    System.out.println("Closed chat room dialog for room ID: " + roomId + " as it's no longer in the updated list.");
                }
            }

            chatRoomListModel.clear();
            for (ChatRoom room : updatedChatRooms) {
                chatRoomListModel.addElement(room);
            }

            for (ChatRoom updatedRoom : updatedChatRooms) {
                ChatRoomDialog dialog = openChatRoomDialogs.get(updatedRoom.getRoomId());
                if (dialog != null) {
                    dialog.updateChatRoomInfo(updatedRoom);
                }
            }

            if (currentChatRoom != null && openChatRoomDialogs.containsKey(currentChatRoom.getRoomId())) {
                int index = -1;
                for (int i = 0; i < chatRoomListModel.size(); i++) {
                    if (chatRoomListModel.getElementAt(i).getRoomId() == currentChatRoom.getRoomId()) {
                        index = i;
                        break;
                    }
                }
                if (index != -1) {
                    chatRoomList.setSelectedIndex(index);
                    chatRoomList.ensureIndexIsVisible(index);
                }
            }
        });
    }

    private void handleNewMessage(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            Message newMessage = (Message) response.getData().get("message");
            Integer unreadRoomIdInteger = (Integer) response.getData().get("unreadRoomId");
            int messageRoomId = (unreadRoomIdInteger != null) ? unreadRoomIdInteger.intValue() : newMessage.getRoomId();

            ChatRoomDialog dialog = openChatRoomDialogs.get(messageRoomId);
            if (dialog != null) {
                System.out.println("Appending new message to existing dialog for room: " + messageRoomId);
                dialog.appendMessageToChatArea(newMessage);
            } else {
                System.out.println("New message received for room " + messageRoomId + ", dialog not open. Content: " + newMessage.getContent());
                chatClient.getChatRooms();
                if (newMessage.getMessageType() == MessageType.SYSTEM) {
                    ChatRoom systemChatRoom = null;
                    for (int i = 0; i < chatRoomListModel.size(); i++) {
                        ChatRoom room = chatRoomListModel.getElementAt(i);
                        if (room.getRoomId() == messageRoomId) {
                            systemChatRoom = room;
                            break;
                        }
                    }
                    if (systemChatRoom != null) {
                        openChatRoomDialog(systemChatRoom);
                    } else {
                        System.err.println("System chat room with ID " + messageRoomId + " not found in chatRoomListModel after chatRooms update.");
                    }
                }
            }
            if (newMessage.getMessageType() != MessageType.SYSTEM) {
                chatClient.getChatRooms();
            }
        });
    }

    private void handleRoomMessagesUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int roomId = (int) response.getData().get("roomId");
            ChatRoomDialog dialog = openChatRoomDialogs.get(roomId);
            if (dialog != null) {
                List<Message> messages = (List<Message>) response.getData().get("messages");
                System.out.println("Updating messages in dialog for room: " + roomId + ", message count: " + (messages != null ? messages.size() : 0));
                dialog.displayMessages(messages);
            }
        });
    }

    private void handleServerFailure(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "서버 오류: " + response.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void handleNoticeListUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            System.out.println("Received response: NOTICE_LIST_UPDATE"); // 로그 추가

            Integer updatedRoomId = (Integer) response.getData().get("roomId");
            List<Message> notices = (List<Message>) response.getData().get("noticeMessages"); // 이 데이터는 서버가 getNoticeMessages 요청에 대한 응답으로만 보냄

            // 1. 공지 프레임이 열려있다면 업데이트:
            if (noticeFrame.isVisible()) {
                // 서버가 이미 공지 메시지 목록을 직접 보내줬다면 그 데이터를 사용
                if (notices != null) {
                    currentNoticeMessages = notices; // 리스트 저장
                    updateNoticeArea(notices);
                } else {
                    // notices가 null이라면 (서버가 단순히 만료 알림만 보낸 경우)
                    // 현재 열려있는 공지 프레임이 어떤 방의 공지를 보여주는지 알 수 없으므로,
                    // 열려있는 첫 번째 채팅방의 공지 목록을 다시 요청하여 업데이트를 유도
                    if (updatedRoomId != null) { // 서버가 특정 방 ID를 알려준 경우
                        chatClient.getNoticeMessages(updatedRoomId); // 이 요청에 대한 응답이 다시 handleNoticeListUpdate로 들어와 notices를 채울 것임
                    } else if (!openChatRoomDialogs.isEmpty()) { // 특정 방 ID 없이 일반적인 알림이 왔을 때
                        int firstRoomId = openChatRoomDialogs.keySet().iterator().next();
                        chatClient.getNoticeMessages(firstRoomId); // 열려있는 첫 번째 방의 공지를 요청
                    } else { // 열린 채팅방이 없는 경우 공지 프레임 비움
                        currentNoticeMessages = new ArrayList<>();
                        updateNoticeArea(new ArrayList<>());
                    }
                }
            }

            // 2. 열려있는 채팅방 다이얼로그 업데이트:
            // 만료된 공지든, 새로 설정된 공지든, 해당 채팅방 다이얼로그의 메시지 목록을 새로 로드해야 합니다.
            // 이렇게 해야 메시지들의 공지 상태 (isNotice)가 화면에 정확히 반영됩니다.
            if (updatedRoomId != null) {
                ChatRoomDialog targetDialog = openChatRoomDialogs.get(updatedRoomId);
                if (targetDialog != null) {
                    targetDialog.loadMessages(); // 해당 방의 모든 메시지를 다시 로드하여 공지 상태 반영
                }
            }
            // else: updatedRoomId가 null인 경우는 모든 열린 다이얼로그에 대해 명시적으로 loadMessages를 호출하지 않습니다.
            //       이런 경우에는 사용자가 직접 채팅방을 열거나 새로운 메시지가 도착했을 때 업데이트됩니다.
        });
    }

    private void handleTimelineUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int roomId = (int) response.getData().get("roomId");
            List<TimelineEvent> timelineEvents = (List<TimelineEvent>) response.getData().get("timelineEvents");
            updateTimelineArea(roomId, timelineEvents);
        });
    }

    private void handleFileUploadSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "파일 업로드 성공", "알림", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void handleFileDownloadSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            try {
                String fileName = (String) response.getData().get("fileName");
                byte[] fileBytes = (byte[]) response.getData().get("fileBytes");

                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setSelectedFile(new File(fileName));
                int userSelection = fileChooser.showSaveDialog(this);

                if (userSelection == JFileChooser.APPROVE_OPTION) {
                    File fileToSave = fileChooser.getSelectedFile();
                    Files.write(fileToSave.toPath(), fileBytes);
                    JOptionPane.showMessageDialog(this, "파일이 성공적으로 다운로드되었습니다:\n" + fileToSave.getAbsolutePath(), "다운로드 성공", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "파일 다운로드 중 오류 발생: " + e.getMessage(), " 다운로드 오류", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void handleSystemNotification(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            Message notificationMessage = (Message) response.getData().get("message");
            Integer unreadRoomIdInteger = (Integer) response.getData().get("unreadRoomId");
            int messageRoomId = (unreadRoomIdInteger != null) ? unreadRoomIdInteger.intValue() : notificationMessage.getRoomId();

            if (notificationMessage == null) {
                System.err.println("Error: Received SYSTEM_NOTIFICATION with null message object.");
                return;
            }

            ChatRoomDialog dialog = openChatRoomDialogs.get(messageRoomId);
            if (dialog != null) {
                System.out.println("Appending system message to existing dialog for room: " + messageRoomId);
                dialog.appendMessageToChatArea(notificationMessage);
                if (notificationMessage.getMessageId() != 0) {
                    chatClient.markMessageAsRead(notificationMessage.getMessageId());
                }
            } else {
                System.out.println("New system message received for room " + messageRoomId + ", dialog not open. Attempting to open.");
                ChatRoom systemChatRoom = null;
                for (int i = 0; i < chatRoomListModel.size(); i++) {
                    ChatRoom room = chatRoomListModel.getElementAt(i);
                    if (room.getRoomId() == messageRoomId) {
                        systemChatRoom = room;
                        break;
                    }
                }

                if (systemChatRoom != null) {
                    openChatRoomDialog(systemChatRoom);
                } else {
                    System.err.println("System chat room with ID " + messageRoomId + " not found in chatRoomListModel. Requesting chat rooms update.");
                    chatClient.getChatRooms();
                }
            }
        });
    }

    private void handleGeneralSuccessResponse(ServerResponse response) {
        System.out.println("handleGeneralSuccessResponse called. Message: " + response.getMessage() + ", Data keys: " + (response.getData() != null ? response.getData().keySet() : "none"));

        if (response.getData() != null && response.getData().containsKey("chatRoom")) {
            handleChatRoomCreationSuccessLogic(response);
        } else if ("Friend added successfully".equals(response.getMessage())) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "친구가 추가되었습니다.", "성공", JOptionPane.INFORMATION_MESSAGE));
        } else if (response.getMessage().equals("프로젝트 타임라인에 내용이 추가되었습니다.")) {
            // Nothing specific to do here, as timeline update is handled by TIMELINE_UPDATE response
        } else if (response.getMessage().equals("프로젝트가 종료되었습니다.")) {
            // Nothing specific to do here, as timeline update is handled by TIMELINE_UPDATE response
        } else if (response.getMessage().contains("expired notices cleared")) {
            System.out.println(response.getMessage());
        }
    }

    private void handleMessageMarkedAsNoticeSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int messageId = (int) response.getData().get("messageId");
            boolean isNotice = (boolean) response.getData().get("isNotice");
            int roomId = (int) response.getData().get("roomId");

            String statusText = isNotice ? "공지로 설정되었습니다." : "공지에서 해제되었습니다.";
            JOptionPane.showMessageDialog(this, "메시지가 " + statusText, "알림", JOptionPane.INFORMATION_MESSAGE);

            // 해당 채팅방의 공지 목록을 새로고침 요청
            chatClient.getNoticeMessages(roomId); // 이 요청에 대한 응답이 handleNoticeListUpdate로 다시 올 것.

            // 채팅방 다이얼로그가 열려 있다면 메시지 상태를 새로고침 (공지 여부 표시 변경)
            ChatRoomDialog dialog = openChatRoomDialogs.get(roomId);
            if (dialog != null) {
                dialog.loadMessages(); // 모든 메시지를 다시 로드하여 isNotice 상태 반영
            }
        });
    }

    private void handleTimelineEventDeletedSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            // 프로젝트 단위 삭제와 단일 이벤트 삭제 모두 이 핸들러를 사용
            String projectName = (String) response.getData().get("projectName"); // 프로젝트 단위 삭제 시
            Integer eventId = (Integer) response.getData().get("eventId"); // 단일 이벤트 삭제 시

            String message;
            if (projectName != null) {
                int deletedCount = (int) response.getData().get("deletedCount");
                message = "'" + projectName + "' 프로젝트의 타임라인 이벤트 " + deletedCount + "개가 삭제되었습니다.";
            } else if (eventId != null) {
                message = "타임라인 이벤트 ID " + eventId + "가 성공적으로 삭제되었습니다.";
            } else {
                message = "타임라인 이벤트 삭제 성공 (상세 정보 없음).";
            }
            JOptionPane.showMessageDialog(this, message, "삭제 성공", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    private void handleTimelineEventDeleteFail(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, "타임라인 이벤트 삭제 실패: " + response.getMessage(), "삭제 실패", JOptionPane.ERROR_MESSAGE);
        });
    }


    private void initNoticeFrame() {
        noticeFrame = new JFrame("공지 사항");
        noticeFrame.setSize(400, 500);
        noticeFrame.setLocationRelativeTo(this);
        noticeFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        noticeArea = new JEditorPane();
        noticeEditorKit = new HTMLEditorKit();
        noticeArea.setEditorKit(noticeEditorKit);
        noticeDoc = (HTMLDocument) noticeArea.getDocument();

        noticeArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(noticeArea);
        noticeFrame.add(scrollPane);

        noticeArea.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                String description = e.getDescription();
                if (description != null && description.startsWith("notice://")) {
                    try {
                        String[] parts = description.substring("notice://".length()).split("/");
                        int roomId = Integer.parseInt(parts[0]);
                        int messageId = Integer.parseInt(parts[1]);

                        System.out.println("Clicked notice: Room ID " + roomId + ", Message ID " + messageId);

                        ChatRoom targetRoom = null;
                        for (int i = 0; i < chatRoomListModel.size(); i++) {
                            ChatRoom room = chatRoomListModel.getElementAt(i);
                            if (room.getRoomId() == roomId) {
                                targetRoom = room;
                                break;
                            }
                        }

                        if (targetRoom != null) {
                            openChatRoomDialog(targetRoom, messageId);
                            noticeFrame.setVisible(false);
                        } else {
                            JOptionPane.showMessageDialog(noticeFrame, "해당 공지가 속한 채팅방을 찾을 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
                            chatClient.getChatRooms();
                        }

                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                        System.err.println("Invalid notice link format: " + description + " - " + ex.getMessage());
                    }
                }
            }
        });
    }

    private void updateNoticeArea(List<Message> notices) {
        currentNoticeMessages = notices;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        StringBuilder htmlContent = new StringBuilder("<html><body>");

        if (notices == null || notices.isEmpty()) {
            htmlContent.append("<p>공지사항이 없습니다.</p>");
        } else {
            for (Message notice : notices) {
                String link = "notice://" + notice.getRoomId() + "/" + notice.getMessageId();
                String expiryInfo = "";
                if (notice.getNoticeExpiryTime() != null) {
                    expiryInfo = " (만료: " + notice.getNoticeExpiryTime().format(formatter) + ")";
                }

                htmlContent.append(String.format(
                        "<p style='margin-bottom: 10px;'>" +
                                "<a href='%s' style='text-decoration: none; color: black;'>" +
                                "<span style='font-size: 0.9em; color: #888;'>[%s] %s: </span>" +
                                "<span style='font-weight: bold;'>%s</span>%s" +
                                "</a></p>",
                        link,
                        notice.getSentAt().format(formatter),
                        notice.getSenderNickname(),
                        notice.getContent(),
                        expiryInfo
                ));
            }
        }
        htmlContent.append("</body></html>");

        SwingUtilities.invokeLater(() -> {
            try {
                noticeDoc.remove(0, noticeDoc.getLength());
                noticeEditorKit.insertHTML(noticeDoc, noticeDoc.getLength(), htmlContent.toString(), 0, 0, null);
                noticeArea.setCaretPosition(0);
            } catch (BadLocationException | IOException e) {
                System.err.println("Error updating notice area: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void initTimelineFrame() {
        timelineFrame = new JFrame("타임라인");
        timelineFrame.setSize(700, 600);
        timelineFrame.setLocationRelativeTo(this);
        timelineFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        JPanel mainTimelinePanel = new JPanel(new BorderLayout(10, 0));

        JPanel projectPanel = new JPanel(new BorderLayout());
        projectPanel.setBorder(new TitledBorder("프로젝트 목록"));
        projectListModel = new DefaultListModel<>();
        projectList = new JList<>(projectListModel);
        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                filterTimelineEvents();
            }
        });
        JScrollPane projectScrollPane = new JScrollPane(projectList);
        projectScrollPane.setPreferredSize(new Dimension(150, 0));
        projectPanel.add(projectScrollPane, BorderLayout.WEST);
        mainTimelinePanel.add(projectPanel, BorderLayout.WEST);

        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBorder(new TitledBorder("이벤트 내용"));
        timelineArea = new JEditorPane(); // JTextArea -> JEditorPane 변경
        timelineEditorKit = new HTMLEditorKit(); // 새로 추가
        timelineArea.setEditorKit(timelineEditorKit); // 새로 추가
        timelineDoc = (HTMLDocument) timelineArea.getDocument(); // 새로 추가
        timelineArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(timelineArea);
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        mainTimelinePanel.add(contentPanel, BorderLayout.CENTER);

        // JEditorPane에 마우스 리스너 추가
        timelineArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int pos = timelineArea.viewToModel(e.getPoint());
                    HTMLDocument doc = (HTMLDocument) timelineArea.getDocument();
                    Element element = doc.getCharacterElement(pos);

                    Integer eventId = null;

                    // data-event-id 속성을 가진 부모 Element 찾기
                    Element currentElement = element;
                    while (currentElement != null && eventId == null) {
                        AttributeSet attrs = currentElement.getAttributes();
                        String eventIdStr = (String) attrs.getAttribute("data-event-id");
                        if (eventIdStr != null) {
                            try {
                                eventId = Integer.parseInt(eventIdStr);
                            } catch (NumberFormatException ex) {
                                System.err.println("Invalid event ID in HTML: " + eventIdStr);
                            }
                        }
                        currentElement = currentElement.getParentElement();
                    }

                    if (eventId != null) {
                        final int finalEventId = eventId;
                        // 클릭된 이벤트 내용을 가져오기 위해 allTimelineEvents에서 찾음
                        TimelineEvent clickedEvent = allTimelineEvents.stream()
                                .filter(ev -> ev.getEventId() == finalEventId)
                                .findFirst()
                                .orElse(null);

                        if (clickedEvent != null) {
                            JPopupMenu popupMenu = new JPopupMenu();

                            JMenuItem editItem = new JMenuItem("수정");
                            editItem.addActionListener(ev -> {
                                showEditTimelineEventDialog(clickedEvent);
                            });
                            popupMenu.add(editItem);

                            JMenuItem deleteItem = new JMenuItem("삭제");
                            deleteItem.addActionListener(ev -> {
                                int confirm = JOptionPane.showConfirmDialog(timelineFrame,
                                        "정말로 이 타임라인 이벤트를 삭제하시겠습니까?\n" + clickedEvent.getDescription(),
                                        "이벤트 삭제 확인", JOptionPane.YES_NO_OPTION);
                                if (confirm == JOptionPane.YES_OPTION) {
                                    chatClient.deleteTimelineEventById(finalEventId);
                                }
                            });
                            popupMenu.add(deleteItem);

                            popupMenu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }
            }
        });

        timelineFrame.add(mainTimelinePanel);
    }

    private void updateTimelineArea(int roomId, List<TimelineEvent> events) {
        this.allTimelineEvents = events;

        Set<String> projectNames = new TreeSet<>();
        for (TimelineEvent event : events) {
            if (("PROJECT_START".equals(event.getEventType()) || "PROJECT_CONTENT".equals(event.getEventType()) || "PROJECT_END".equals(event.getEventType()))
                    && event.getEventName() != null && !event.getEventName().isEmpty()) {
                projectNames.add(event.getEventName());
            }
        }
        SwingUtilities.invokeLater(() -> {
            projectListModel.clear();
            projectListModel.addElement("모든 이벤트");
            for (String projectName : projectNames) {
                projectListModel.addElement(projectName);
            }
            projectList.setSelectedIndex(0);
            filterTimelineEvents(); // HTML 렌더링은 여기서
        });
    }

    private void filterTimelineEvents() {
        String selectedProject = projectList.getSelectedValue();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        StringBuilder htmlContent = new StringBuilder("<html><body>"); // HTML 시작

        List<TimelineEvent> filteredEvents = new ArrayList<>();
        if ("모든 이벤트".equals(selectedProject)) {
            filteredEvents.addAll(allTimelineEvents);
        } else {
            for (TimelineEvent event : allTimelineEvents) {
                if (selectedProject != null && selectedProject.equals(event.getEventName())) {
                    filteredEvents.add(event);
                }
            }
        }

        filteredEvents.sort(Comparator.comparing(TimelineEvent::getEventTime));

        if (filteredEvents.isEmpty()) {
            htmlContent.append("<p style='text-align: center; color: gray;'>타임라인 이벤트가 없습니다.</p>");
        } else {
            for (TimelineEvent event : filteredEvents) {
                String displayContent;
                String textColor = "black";
                String fontWeight = "normal";

                // 이벤트 타입에 따른 스타일 및 내용 구성
                if ("PROJECT_START".equals(event.getEventType())) {
                    displayContent = String.format("%s 시작 / %s", event.getEventName(), event.getSenderNickname());
                    textColor = "#0056b3"; // 파란색
                    fontWeight = "bold";
                } else if ("PROJECT_CONTENT".equals(event.getEventType())) {
                    displayContent = String.format("%s / %s", event.getDescription(), event.getSenderNickname());
                    textColor = "#333333"; // 진한 회색
                } else if ("PROJECT_END".equals(event.getEventType())) {
                    displayContent = String.format("%s 종료 / %s", event.getEventName(), event.getSenderNickname());
                    textColor = "#d9534f"; // 빨간색
                    fontWeight = "bold";
                } else { // 기타 (예: MESSAGE)
                    displayContent = event.getDescription(); // 원본 description 사용
                }

                // HTML 형식으로 메시지 추가 (data-event-id 속성 추가)
                htmlContent.append(String.format(
                        "<div data-event-id='%d' style='margin-bottom: 10px; padding: 5px; border: 1px solid #ddd; border-radius: 5px;'>" +
                                "<p style='margin: 0; font-size: 0.9em; color: #888;'>[%s]</p>" +
                                "<p style='margin: 0; font-weight: %s; color: %s;'>%s</p>" +
                                "</div>",
                        event.getEventId(), // 여기에서 eventId를 data 속성으로 추가
                        event.getEventTime().format(formatter),
                        fontWeight, textColor,
                        displayContent
                ));
            }
        }
        htmlContent.append("</body></html>");

        SwingUtilities.invokeLater(() -> {
            try {
                timelineDoc.remove(0, timelineDoc.getLength());
                timelineEditorKit.insertHTML(timelineDoc, timelineDoc.getLength(), htmlContent.toString(), 0, 0, null);
                timelineArea.setCaretPosition(0);
            } catch (BadLocationException | IOException e) {
                System.err.println("Error updating timeline area: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void showEditTimelineEventDialog(TimelineEvent eventToEdit) {
        JDialog editDialog = new JDialog(timelineFrame, "타임라인 이벤트 수정", true);
        editDialog.setSize(400, 200);
        editDialog.setLocationRelativeTo(timelineFrame);
        editDialog.setLayout(new BorderLayout(10, 10));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JTextArea descriptionArea = new JTextArea(eventToEdit.getDescription());
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(descriptionArea);
        inputPanel.add(new JLabel("새로운 내용:"), BorderLayout.NORTH);
        inputPanel.add(scrollPane, BorderLayout.CENTER);
        editDialog.add(inputPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("저장");
        JButton cancelButton = new JButton("취소");

        saveButton.addActionListener(e -> {
            String newDescription = descriptionArea.getText().trim();
            if (newDescription.isEmpty()) {
                JOptionPane.showMessageDialog(editDialog, "내용을 입력해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                return;
            }
            chatClient.updateTimelineEvent(eventToEdit.getEventId(), newDescription);
            editDialog.dispose();
        });

        cancelButton.addActionListener(e -> editDialog.dispose());

        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);
        editDialog.add(buttonPanel, BorderLayout.SOUTH);

        editDialog.setVisible(true);
    }

    private void toggleSettingsPanel() {
        JPopupMenu settingsMenu = new JPopupMenu();

        JMenuItem setAwayStatusItem = new JMenuItem("자리비움 설정");
        setAwayStatusItem.addActionListener(e -> {
            boolean isAway = JOptionPane.showConfirmDialog(this, "자리비움 상태로 변경하시겠습니까?", "자리비움", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            chatClient.setAwayStatus(isAway);
        });
        settingsMenu.add(setAwayStatusItem);

        JMenuItem logoutItem = new JMenuItem("로그아웃");
        logoutItem.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(ChatClientGUI.this, "로그아웃 하시겠습니까?", "로그아웃 확인", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                chatClient.logout();
                chatClient.disconnect();
                for (ChatRoomDialog dialog : openChatRoomDialogs.values()) {
                    dialog.dispose();
                }
                new LoginGUI().setVisible(true);
                this.dispose();
            }
        });
        settingsMenu.add(logoutItem);

        settingsMenu.show(settingsToggleButton, 0, settingsToggleButton.getHeight());
    }

    private void showAddFriendDialog() {
        String friendUsername = JOptionPane.showInputDialog(this, "추가할 친구의 아이디를 입력하세요:");
        if (friendUsername != null && !friendUsername.trim().isEmpty()) {
            if (friendUsername.equals(currentUser.getUsername())) {
                JOptionPane.showMessageDialog(this, "자기 자신은 친구로 추가할 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
                return;
            }
            boolean alreadyFriend = java.util.Collections.list(friendListModel.elements()).stream()
                    .anyMatch(f -> f.getUsername().equals(friendUsername));
            if (alreadyFriend) {
                JOptionPane.showMessageDialog(this, "이미 친구인 사용자입니다.", "안내", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            chatClient.addFriend(friendUsername);
        }
    }

    private void showCreateChatRoomDialog() {
        JDialog createRoomDialog = new JDialog(this, "채팅방 생성", true);
        createRoomDialog.setSize(400, 500);
        createRoomDialog.setLocationRelativeTo(this);
        createRoomDialog.setLayout(new BorderLayout());

        JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField roomNameField = new JTextField(20);
        namePanel.add(new JLabel("채팅방 이름 (그룹):"));
        namePanel.add(roomNameField);

        DefaultListModel<User> allUsersModel = new DefaultListModel<>();
        JList<User> allUsersList = new JList<>(allUsersModel);
        allUsersList.setCellRenderer(new ChatClientGUI.FriendListCellRenderer());
        allUsersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        Consumer<ServerResponse> originalFriendListUpdateListener = chatClient.getResponseListener(ServerResponse.ResponseType.FRIEND_LIST_UPDATE);
        chatClient.setResponseListener(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, res -> {
            SwingUtilities.invokeLater(() -> {
                List<User> friends = (List<User>) res.getData().get("friends");
                allUsersModel.clear();
                if (friends != null) {
                    friends.stream()
                            .filter(user -> user.getUserId() != currentUser.getUserId())
                            .forEach(allUsersModel::addElement);
                }
            });
            chatClient.setResponseListener(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, originalFriendListUpdateListener);
        });
        chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.GET_FRIEND_LIST, null));


        JScrollPane userScrollPane = new JScrollPane(allUsersList);
        userScrollPane.setBorder(new TitledBorder("초대할 친구 선택 (Ctrl/Shift/Shift 클릭)"));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton createButton = new JButton("생성");
        JButton cancelButton = new JButton("취소");

        createButton.addActionListener(e -> {
            List<User> selectedUsers = allUsersList.getSelectedValuesList();
            List<Integer> invitedUserIds = selectedUsers.stream()
                    .map(User::getUserId)
                    .collect(Collectors.toList());
            invitedUserIds.add(currentUser.getUserId());

            boolean isGroupChat;
            String finalRoomName = roomNameField.getText().trim();

            if (selectedUsers.isEmpty()) {
                JOptionPane.showMessageDialog(createRoomDialog, "채팅방에 초대할 친구를 선택해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                return;
            } else if (selectedUsers.size() == 1) {
                isGroupChat = false;
                finalRoomName = "";
            } else {
                if (finalRoomName.isEmpty()) {
                    JOptionPane.showMessageDialog(createRoomDialog, "그룹 채팅방 이름을 입력해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                isGroupChat = true;
            }

            chatClient.createChatRoom(finalRoomName, isGroupChat, invitedUserIds);
            createRoomDialog.dispose();
        });

        cancelButton.addActionListener(e -> createRoomDialog.dispose());

        buttonPanel.add(createButton);
    }

    public static class FriendListCellRenderer extends JLabel implements ListCellRenderer<User> {
        @Override
        public Component getListCellRendererComponent(JList<? extends User> list, User user, int index, boolean isSelected, boolean cellHasFocus) {
            setText(user.getNickname() + " (" + user.getUsername() + ")");
            setOpaque(true);

            Color statusColor;
            switch (user.getStatus()) {
                case ONLINE:
                    statusColor = Color.GREEN.darker();
                    break;
                case OFFLINE:
                    statusColor = Color.RED.darker();
                    break;
                case AWAY:
                    statusColor = Color.ORANGE.darker();
                    break;
                default:
                    statusColor = Color.GRAY;
                    break;
            }

            ImageIcon statusIcon = new ImageIcon(createCircleIcon(statusColor, 10));
            setIcon(statusIcon);
            setIconTextGap(5);

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }

        private Image createCircleIcon(Color color, int size) {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(0, 0, size, size);
            g2.dispose();
            return image;
        }
    }

    public DefaultListModel<User> getFriendListModel() {
        return friendListModel;
    }

}