// src/main/java/com/chat/client/ChatClientGUI.java
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@SuppressWarnings("unchecked")
public class ChatClientGUI extends JFrame {
    private ChatClient chatClient;
    private User currentUser;

    // UI 컴포넌트 - 메인 화면용
    private DefaultListModel<User> friendListModel;
    private JList<User> friendList;
    private DefaultListModel<ChatRoom> chatRoomListModel;
    private JList<ChatRoom> chatRoomList;

    private JButton addFriendButton;
    private JButton createChatRoomButton;
    private JToggleButton settingsToggleButton;

    // 현재 활성화된 채팅방 (메인 GUI에서는 선택된 방 정보만 가짐)
    private ChatRoom currentChatRoom;

    // 열려 있는 채팅방 다이얼로그를 관리하는 맵 (roomId -> ChatRoomDialog)
    private Map<Integer, ChatRoomDialog> openChatRoomDialogs;

    // 친구 더블클릭으로 1:1 채팅방을 열 때, 어떤 친구와의 방을 열어야 하는지 임시로 저장
    private int pendingPrivateChatUserId = -1; //

    // 별도 창들
    private JFrame noticeFrame;
    private JTextArea noticeArea;
    private JFrame timelineFrame;
    private JTextArea timelineArea;
    private JFrame unreadNotificationFrame;
    private JTextArea unreadNotificationArea;

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
        initUnreadNotificationFrame();
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
        // CREATE_CHAT_ROOM 요청에 대한 성공 응답 처리 리스너 추가
        chatClient.setResponseListener(ServerResponse.ResponseType.SUCCESS, this::handleChatRoomCreationSuccess); //
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

        // 좌측: 친구 목록
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

        // 친구 목록 더블클릭 이벤트 추가 (1:1 대화방 생성 및 열기)
        friendList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    User selectedUser = friendList.getSelectedValue();
                    if (selectedUser != null) {
                        // 친구 더블클릭 시, 열고자 하는 1:1 채팅 상대방의 ID를 저장
                        pendingPrivateChatUserId = selectedUser.getUserId(); //
                        List<Integer> participants = new ArrayList<>();
                        participants.add(currentUser.getUserId());
                        participants.add(selectedUser.getUserId());
                        chatClient.createChatRoom("", false, participants);
                    }
                }
            }
        });


        // 우측: 채팅방 목록
        JPanel chatRoomPanel = new JPanel(new BorderLayout());
        chatRoomPanel.setBorder(new TitledBorder("채팅방 목록"));
        chatRoomListModel = new DefaultListModel<>();
        chatRoomList = new JList<>(chatRoomListModel);
        chatRoomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatRoomPanel.add(new JScrollPane(chatRoomList), BorderLayout.CENTER);

        createChatRoomButton = new JButton("채팅방 생성 / 친구 초대");
        createChatRoomButton.addActionListener(e -> showCreateChatRoomDialog());
        chatRoomPanel.add(createChatRoomButton, BorderLayout.SOUTH);

        centerPanel.add(chatRoomPanel);
        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // 채팅방 목록 선택 시 이벤트 처리 (채팅방 다이얼로그 열기)
        chatRoomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ChatRoom selectedRoom = chatRoomList.getSelectedValue();
                if (selectedRoom != null) {
                    currentChatRoom = selectedRoom;
                    openChatRoomDialog(selectedRoom);
                }
            }
        });

        add(mainPanel);
    }

    // --- 채팅방 다이얼로그 관리 ---
    private void openChatRoomDialog(ChatRoom room) {
        ChatRoomDialog dialog = openChatRoomDialogs.get(room.getRoomId());
        if (dialog == null) {
            dialog = new ChatRoomDialog(this, chatClient, room);
            openChatRoomDialogs.put(room.getRoomId(), dialog);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    openChatRoomDialogs.remove(room.getRoomId());
                    chatRoomList.clearSelection();
                }
            });
        }
        dialog.setVisible(true);
        dialog.toFront();
        dialog.loadMessages();
    }


    // --- 서버 응답 핸들러 ---

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

    private void handleChatRoomCreationSuccess(ServerResponse response) { //
        // CREATE_CHAT_ROOM 요청에 대한 SUCCESS 응답 처리
        // 이 응답에는 새로 생성되거나 찾아진 ChatRoom 객체가 포함되어 있어야 함
        if (response.isSuccess() && response.getData() != null && response.getData().containsKey("chatRoom")) { //
            ChatRoom createdOrFoundRoom = (ChatRoom) response.getData().get("chatRoom"); //
            System.out.println("Chat room operation successful: " + createdOrFoundRoom.getRoomName() + ", Room ID: " + createdOrFoundRoom.getRoomId()); //

            // 친구 더블클릭으로 1:1 채팅방이 생성/선택되었을 경우
            if (pendingPrivateChatUserId != -1 && !createdOrFoundRoom.isGroupChat()) { //
                // 생성되거나 찾아진 1:1 채팅방의 참여자 중 상대방의 ID가 일치하는지 확인
                boolean foundTargetFriend = createdOrFoundRoom.getParticipants().stream() //
                        .anyMatch(p -> p.getUserId() == pendingPrivateChatUserId && p.getUserId() != currentUser.getUserId()); //

                if (foundTargetFriend) { //
                    SwingUtilities.invokeLater(() -> { //
                        System.out.println("Attempting to open chat room for pending user: " + pendingPrivateChatUserId); //
                        openChatRoomDialog(createdOrFoundRoom); //
                        // 채팅방 목록에서 해당 방을 선택 상태로 만듦
                        for (int i = 0; i < chatRoomListModel.size(); i++) { //
                            if (chatRoomListModel.getElementAt(i).getRoomId() == createdOrFoundRoom.getRoomId()) { //
                                chatRoomList.setSelectedIndex(i); //
                                chatRoomList.ensureIndexIsVisible(i); //
                                break; //
                            }
                        }
                    }); //
                }
                pendingPrivateChatUserId = -1; // 처리 후 초기화
            }
            // 일반적인 CHAT_ROOMS_UPDATE는 handleChatRoomsUpdate에서 처리되므로,
            // 여기서는 친구 더블클릭으로 인한 특정 1:1 채팅방 열기만 담당합니다.
            // 서버에서 CHAT_ROOMS_UPDATE를 항상 보내주므로, 목록 갱신 및 다른 다이얼로그 닫기는 그곳에서 처리됩니다.
        } else if (!response.isSuccess() && "Failed to create chat room".equals(response.getMessage())) { //
            // 채팅방 생성 실패 시 사용자에게 알림
            SwingUtilities.invokeLater(() -> { //
                JOptionPane.showMessageDialog(this, "채팅방 생성/열기 실패: " + response.getMessage(), "오류", JOptionPane.ERROR_MESSAGE); //
            }); //
            pendingPrivateChatUserId = -1; // 실패 시에도 초기화
        }
    }


    private void handleChatRoomsUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            List<ChatRoom> updatedChatRooms = (List<ChatRoom>) response.getData().get("chatRooms");

            if (updatedChatRooms == null) {
                System.err.println("Received null chatRooms list from server. Returning empty list.");
                updatedChatRooms = new ArrayList<>();
            }

            // 1. 현재 열려있는 채팅방 다이얼로그 중 업데이트된 목록에 없는 것은 닫기
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

            // 2. 채팅방 목록 모델 업데이트
            // 새로운 ChatRoom 객체로 갱신하여 participant 정보 등이 최신이 되도록 함
            chatRoomListModel.clear();
            for (ChatRoom room : updatedChatRooms) {
                chatRoomListModel.addElement(room);
            }

            // 3. (옵션) 이전에 선택되었던 방이 있다면 다시 선택
            // (친구 더블클릭 시 자동 열기는 handleChatRoomCreationSuccess에서 처리)
            if (currentChatRoom != null && openChatRoomDialogs.containsKey(currentChatRoom.getRoomId())) { // 열려있는 다이얼로그에 해당하는 경우
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
            int senderId = (int) response.getData().get("senderId");

            ChatRoomDialog dialog = openChatRoomDialogs.get(newMessage.getRoomId());
            if (dialog != null) {
                System.out.println("Appending new message to existing dialog for room: " + newMessage.getRoomId());
                dialog.appendMessageToChatArea(newMessage);
                if (senderId != currentUser.getUserId()) {
                    chatClient.markMessageAsRead(newMessage.getMessageId());
                }
            } else {
                System.out.println("New message received for room " + newMessage.getRoomId() + ", dialog not open. Content: " + newMessage.getContent());
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
            List<Message> notices = (List<Message>) response.getData().get("noticeMessages");
            updateNoticeArea(notices);
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
        // This is fine, as NEW_MESSAGE broadcasts it.
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
                JOptionPane.showMessageDialog(this, "파일 다운로드 중 오류 발생: " + e.getMessage(), "다운로드 오류", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void handleSystemNotification(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            Message notificationMessage = (Message) response.getData().get("message");
            int unreadRoomId = (int) response.getData().get("unreadRoomId");
            appendSystemNotification(notificationMessage, unreadRoomId);
        });
    }

    // --- 별도 창 기능 (Notice, Timeline, Unread) ---
    private void initNoticeFrame() {
        noticeFrame = new JFrame("공지 사항");
        noticeFrame.setSize(400, 500);
        noticeFrame.setLocationRelativeTo(this);
        noticeFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        noticeArea = new JTextArea();
        noticeArea.setEditable(false);
        noticeArea.setLineWrap(true);
        noticeArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(noticeArea);
        noticeFrame.add(scrollPane);
    }

    private void updateNoticeArea(List<Message> notices) {
        noticeArea.setText("");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (Message notice : notices) {
            noticeArea.append(
                    "[" + notice.getSentAt().format(formatter) + "] " +
                            notice.getSenderNickname() + ": " + notice.getContent() + "\n\n"
            );
        }
        noticeArea.setCaretPosition(0);
    }

    private void initTimelineFrame() {
        timelineFrame = new JFrame("타임라인");
        timelineFrame.setSize(500, 600);
        timelineFrame.setLocationRelativeTo(this);
        timelineFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        timelineArea = new JTextArea();
        timelineArea.setEditable(false);
        timelineArea.setLineWrap(true);
        timelineArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(timelineArea);
        timelineFrame.add(scrollPane);
    }

    private void updateTimelineArea(int roomId, List<TimelineEvent> events) {
        timelineArea.setText("");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (TimelineEvent event : events) {
            timelineArea.append(
                    "[" + event.getEventTime().format(formatter) + "] " +
                            event.getUsername() + " " + event.getCommand() + ": " + event.getDescription() + "\n\n"
            );
        }
        timelineArea.setCaretPosition(0);
    }

    private void initUnreadNotificationFrame() {
        unreadNotificationFrame = new JFrame("미열람 알림");
        unreadNotificationFrame.setSize(300, 200);
        unreadNotificationFrame.setLocation(this.getX() + this.getWidth() - 320, this.getY() + 30);
        unreadNotificationFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        unreadNotificationArea = new JTextArea();
        unreadNotificationArea.setEditable(false);
        unreadNotificationArea.setLineWrap(true);
        unreadNotificationArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(unreadNotificationArea);
        unreadNotificationFrame.add(scrollPane);
    }

    private void appendSystemNotification(Message notificationMessage, int unreadRoomId) {
        SwingUtilities.invokeLater(() -> {
            try {
                unreadNotificationArea.append(
                        notificationMessage.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) +
                                " - " + notificationMessage.getContent() +
                                "\n"
                );
                unreadNotificationArea.setCaretPosition(unreadNotificationArea.getDocument().getLength());
                if (!unreadNotificationFrame.isVisible()) {
                    unreadNotificationFrame.setVisible(true);
                }
            } catch (Exception e) {
                System.err.println("Error appending system notification: " + e.getMessage());
            }
        });
    }

    // "설정" (배색) 버튼 토글 로직
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
        JDialog createRoomDialog = new JDialog(this, "채팅방 생성 / 친구 초대", true);
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
        JButton inviteButton = new JButton("초대 (현재 방)");
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

        inviteButton.addActionListener(e -> {
            if (currentChatRoom == null) {
                JOptionPane.showMessageDialog(createRoomDialog, "친구를 초대하려면 채팅방을 먼저 선택해주세요.", "안내", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<User> selectedUsers = allUsersList.getSelectedValuesList();
            if (selectedUsers.isEmpty()) {
                JOptionPane.showMessageDialog(createRoomDialog, "초대할 친구를 선택해주세요.", "안내", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            for (User userToInvite : selectedUsers) {
                if (currentChatRoom.getParticipants().stream().noneMatch(p -> p.getUserId() == userToInvite.getUserId())) {
                    chatClient.inviteUserToRoom(currentChatRoom.getRoomId(), userToInvite.getUserId());
                } else {
                    JOptionPane.showMessageDialog(createRoomDialog, userToInvite.getNickname() + "님은 이미 방에 참여 중입니다.", "알림", JOptionPane.INFORMATION_MESSAGE);
                }
            }
            createRoomDialog.dispose();
        });


        cancelButton.addActionListener(e -> createRoomDialog.dispose());

        buttonPanel.add(createButton);
        buttonPanel.add(inviteButton);
        buttonPanel.add(cancelButton);

        createRoomDialog.add(namePanel, BorderLayout.NORTH);
        createRoomDialog.add(userScrollPane, BorderLayout.CENTER);
        createRoomDialog.add(buttonPanel, BorderLayout.SOUTH);
        createRoomDialog.setVisible(true);
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