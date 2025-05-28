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

    // 별도 창들 (설정 메뉴에서 제거되었으나, 인스턴스는 유지하거나 완전히 제거할 수 있음)
    // 여기서는 필요에 따라 유지하되, 설정 메뉴에서만 호출하지 않도록 합니다.
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

        // 별도 창 초기화 (설정 메뉴에서 직접 호출하지 않으므로 여기서 초기화)
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
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // 1:1 대화방 생성을 위해 단일 선택으로 변경 (더블클릭 시)
        friendPanel.add(new JScrollPane(friendList), BorderLayout.CENTER);

        addFriendButton = new JButton("친구 추가");
        addFriendButton.addActionListener(e -> showAddFriendDialog());
        friendPanel.add(addFriendButton, BorderLayout.SOUTH);
        centerPanel.add(friendPanel);

        // 친구 목록 더블클릭 이벤트 추가 (1:1 대화방 생성)
        friendList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) { // 더블클릭 감지
                    User selectedUser = friendList.getSelectedValue();
                    if (selectedUser != null) {
                        // 선택된 친구와 1:1 대화방 생성 또는 열기 요청
                        List<Integer> participants = new ArrayList<>();
                        participants.add(currentUser.getUserId());
                        participants.add(selectedUser.getUserId());
                        // isGroupChat=false 로 보내서 1:1 채팅방임을 서버에 알림
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
                }
            });
        }
        dialog.setVisible(true);
        dialog.toFront();
        dialog.loadMessages(); // 메시지 로드 (새로 열거나 다시 볼 때)
    }


    // --- 서버 응답 핸들러 ---

    private void handleFriendListUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            List<User> friends = (List<User>) response.getData().get("friends");
            friendListModel.clear();
            if (friends != null) { // NullPointerException 방어
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

    private void handleChatRoomsUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            List<ChatRoom> chatRooms = (List<ChatRoom>) response.getData().get("chatRooms");

            if (chatRooms == null) {
                // 이 메시지는 이제 ChatRoomDAO에서 빈 리스트를 반환하므로 거의 나오지 않을 것입니다.
                System.err.println("Received null chatRooms list from server. (This should not happen if DAO returns empty list)");
                chatRooms = new ArrayList<>(); // null 방지
            }

            // 기존 목록을 비우고 새로 채웁니다.
            // 서버에서 항상 해당 유저의 '전체' 채팅방 목록을 보내준다는 전제하에 올바른 방식입니다.
            chatRoomListModel.clear();
            for (ChatRoom room : chatRooms) {
                chatRoomListModel.addElement(room);
            }
            // 기존 선택 유지 (필요하다면)
            if (currentChatRoom != null) {
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
                dialog.appendMessageToChatArea(newMessage);
                if (senderId != currentUser.getUserId()) {
                    chatClient.markMessageAsRead(newMessage.getMessageId());
                }
            } else {
                // 다이얼로그가 열려 있지 않으면, 채팅방 목록에서 새로운 메시지 알림 등을 표시
                // TODO: 채팅방 목록 옆에 읽지 않은 메시지 수 표시 등의 UI 업데이트 로직 추가
                System.out.println("New message in room " + newMessage.getRoomId() + ": " + newMessage.getContent());
                // 필요하다면 해당 채팅방을 자동으로 열 수도 있음
                // openChatRoomDialog(chatClient.getChatRooms().stream().filter(r -> r.getRoomId() == newMessage.getRoomId()).findFirst().orElse(null));
            }
        });
    }

    private void handleRoomMessagesUpdate(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int roomId = (int) response.getData().get("roomId");
            ChatRoomDialog dialog = openChatRoomDialogs.get(roomId);
            if (dialog != null) {
                List<Message> messages = (List<Message>) response.getData().get("messages");
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
        // 이 메시지는 서버에서 NEW_MESSAGE로 브로드캐스트되므로, 여기서는 별도의 GUI 업데이트 불필요.
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
    // 설정 메뉴에서 직접 접근하지 않지만, 인스턴스는 유지합니다.
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

        // TODO: 서버에 GET_ALL_USERS 요청이 필요합니다. 현재는 친구 목록을 가져와 사용합니다.
        // 이것은 GET_FRIEND_LIST 응답을 가로채므로 주의해야 합니다.
        // 이상적으로는 서버에 getAllUsers API를 추가하고 ClientRequest.RequestType.GET_ALL_USERS를 사용해야 합니다.
        chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.GET_FRIEND_LIST, null));
        chatClient.setResponseListener(ServerResponse.ResponseType.FRIEND_LIST_UPDATE, res -> {
            SwingUtilities.invokeLater(() -> {
                List<User> friends = (List<User>) res.getData().get("friends");
                allUsersModel.clear();
                if (friends != null) { // NullPointerException 방어
                    friends.stream()
                            .filter(user -> user.getUserId() != currentUser.getUserId())
                            .forEach(allUsersModel::addElement);
                }
            });
        });


        JScrollPane userScrollPane = new JScrollPane(allUsersList);
        userScrollPane.setBorder(new TitledBorder("초대할 친구 선택 (Ctrl/Shift 클릭)"));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton createButton = new JButton("생성");
        JButton inviteButton = new JButton("초대 (현재 방)");
        JButton cancelButton = new JButton("취소");

        createButton.addActionListener(e -> {
            List<User> selectedUsers = allUsersList.getSelectedValuesList();
            List<Integer> invitedUserIds = selectedUsers.stream()
                    .map(User::getUserId)
                    .collect(Collectors.toList());
            invitedUserIds.add(currentUser.getUserId()); // 본인도 참여자에 포함

            boolean isGroupChat;
            String finalRoomName = roomNameField.getText().trim();

            if (selectedUsers.isEmpty()) {
                JOptionPane.showMessageDialog(createRoomDialog, "채팅방에 초대할 친구를 선택해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                return;
            } else if (selectedUsers.size() == 1) { // 본인 포함 총 2명 => 그룹 채팅방으로 간주
                isGroupChat = true;
                if (finalRoomName.isEmpty()) {
                    finalRoomName = selectedUsers.get(0).getNickname() + "님과의 채팅";
                }
            } else { // 본인 포함 3명 이상 => 그룹 채팅방
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


    // FriendListCellRenderer를 public static으로 변경
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

    // ChatRoomDialog에서 메인 GUI의 친구 목록 모델에 접근하기 위한 getter
    public DefaultListModel<User> getFriendListModel() {
        return friendListModel;
    }

}