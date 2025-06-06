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
    private int pendingPrivateChatUserId = -1;

    // 별도 창들
    private JFrame noticeFrame;
    private JTextArea noticeArea;
    private JFrame timelineFrame;
    private JTextArea timelineArea;

    private JList<String> projectList;
    private DefaultListModel<String> projectListModel;
    private List<TimelineEvent> allTimelineEvents;

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
    }

    private void handleMessageReadConfirm(ServerResponse response) {
        System.out.println("DEBUG: Message read confirmed from server. Message ID: " + response.getData().get("messageId"));
    }

    private void handleMessageAlreadyRead(ServerResponse response) {
        System.out.println("DEBUG: Message " + response.getData().get("messageId") + " was already read. No action needed.");
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
        System.out.println("openChatRoomDialog called for room ID: " + room.getRoomId() + ", name: " + room.getRoomName());
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
        dialog.loadMessages();
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
                JOptionPane.showMessageDialog(this, "파일 다운로드 중 오류 발생: " + e.getMessage(), "다운로드 오류", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void handleSystemNotification(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            Message notificationMessage = (Message) response.getData().get("message");
            Integer unreadRoomIdInteger = (Integer) response.getData().get("unreadRoomId");
            int targetRoomId = (unreadRoomIdInteger != null) ? unreadRoomIdInteger.intValue() : notificationMessage.getRoomId();

            if (notificationMessage == null) {
                System.err.println("Error: Received SYSTEM_NOTIFICATION with null message object.");
                return;
            }

            ChatRoomDialog dialog = openChatRoomDialogs.get(targetRoomId);

            if (dialog != null) {
                System.out.println("Appending system message to existing dialog for room: " + targetRoomId);
                dialog.appendMessageToChatArea(notificationMessage);
                if (notificationMessage.getMessageId() != 0) {
                    chatClient.markMessageAsRead(notificationMessage.getMessageId());
                }
            } else {
                System.out.println("New system message received for room " + targetRoomId + ", dialog not open. Attempting to open.");
                ChatRoom systemChatRoom = null;
                for (int i = 0; i < chatRoomListModel.size(); i++) {
                    ChatRoom room = chatRoomListModel.getElementAt(i);
                    if (room.getRoomId() == targetRoomId) {
                        systemChatRoom = room;
                        break;
                    }
                }

                if (systemChatRoom != null) {
                    openChatRoomDialog(systemChatRoom);
                } else {
                    System.err.println("System chat room with ID " + targetRoomId + " not found in chatRoomListModel. Requesting chat rooms update.");
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
        } else if (response.getMessage().equals("프로젝트 타임라인에 내용이 추가되었습니다.")) { // ADD_PROJECT_CONTENT_TO_TIMELINE 성공 응답 처리
            // 별도의 팝업 메시지 없이, 타임라인이 자동으로 업데이트되므로 이 부분은 필요 없을 수 있음.
            // 필요하다면 JOptionPane.showMessageDialog(this, response.getMessage(), "성공", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void handleMessageMarkedAsNoticeSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            int messageId = (int) response.getData().get("messageId");
            boolean isNotice = (boolean) response.getData().get("isNotice");
            int roomId = (int) response.getData().get("roomId");

            String statusText = isNotice ? "공지로 설정되었습니다." : "공지에서 해제되었습니다.";
            JOptionPane.showMessageDialog(this, "메시지가 " + statusText, "알림", JOptionPane.INFORMATION_MESSAGE);

            chatClient.getNoticeMessages(roomId);

            ChatRoomDialog dialog = openChatRoomDialogs.get(roomId);
            if (dialog != null) {
                dialog.loadMessages();
            }
        });
    }

    private void handleTimelineEventDeletedSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            String projectName = (String) response.getData().get("projectName");
            int deletedCount = (int) response.getData().get("deletedCount");
            int roomId = (int) response.getData().get("roomId");

            JOptionPane.showMessageDialog(this, "'" + projectName + "' 프로젝트의 타임라인 이벤트 " + deletedCount + "개가 삭제되었습니다.", "삭제 성공", JOptionPane.INFORMATION_MESSAGE);
            // ClientHandler에서 TIMELINE_UPDATE 응답을 이미 보내므로, 여기서는 별도의 getTimelineEvents 호출 없이 UI가 업데이트될 것임.
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
        timelineArea = new JTextArea();
        timelineArea.setEditable(false);
        timelineArea.setLineWrap(true);
        timelineArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(timelineArea);
        contentPanel.add(scrollPane, BorderLayout.CENTER);
        mainTimelinePanel.add(contentPanel, BorderLayout.CENTER);

        timelineFrame.add(mainTimelinePanel);
    }

    private void updateTimelineArea(int roomId, List<TimelineEvent> events) {
        this.allTimelineEvents = events;

        Set<String> projectNames = new TreeSet<>();
        for (TimelineEvent event : events) {
            // "PROJECT_START" 및 "PROJECT_CONTENT" 모두 프로젝트 목록에 포함
            if (("PROJECT_START".equals(event.getEventType()) || "PROJECT_CONTENT".equals(event.getEventType()))
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
            filterTimelineEvents();
        });
    }

    private void filterTimelineEvents() {
        String selectedProject = projectList.getSelectedValue();
        timelineArea.setText("");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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

        for (TimelineEvent event : filteredEvents) {
            String displayContent;
            if ("PROJECT_START".equals(event.getEventType())) {
                displayContent = String.format(
                        "(%s) %s",
                        event.getEventTime().format(formatter),
                        event.getDescription()
                );
            } else if ("PROJECT_CONTENT".equals(event.getEventType())) { // 새롭게 추가: PROJECT_CONTENT 타입 처리
                displayContent = String.format(
                        "(%s) %s", // (시간) (내용) / (닉네임)
                        event.getEventTime().format(formatter),
                        event.getDescription()
                );
            }
            else {
                displayContent = event.getEventTime().format(formatter) + " " + event.getSenderNickname() + " " + event.getCommand() + ": " + event.getDescription();
            }
            timelineArea.append(displayContent + "\n\n");
        }
        timelineArea.setCaretPosition(0);
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