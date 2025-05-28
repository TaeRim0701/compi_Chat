package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ClientRequest;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public class ChatRoomDialog extends JDialog {
    private ChatClient chatClient;
    private ChatRoom chatRoom;
    private User currentUser;

    private JEditorPane chatArea;
    private HTMLEditorKit editorKit;
    private HTMLDocument doc;

    private JTextField messageInput;
    private JButton sendButton;
    private JButton fileAttachButton;
    private JCheckBox noticeCheckBox;
    // private JButton inviteButton; // 이제 메뉴 버튼 안에 포함되므로 필드 제거

    public ChatRoomDialog(JFrame parent, ChatClient client, ChatRoom room) {
        super(parent, room.getRoomName(), false);
        this.chatClient = client;
        this.chatRoom = room;
        this.currentUser = client.getCurrentUser();

        setSize(700, 500);
        setLocationRelativeTo(parent);

        setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                // 다이얼로그가 닫힐 때 필요한 정리 작업 수행 (예: openChatRoomDialogs 맵에서 제거)
                // ChatClientGUI에서 이 다이얼로그를 맵에서 제거하는 로직이 있으므로 여기서는 추가 작업 불필요.
            }
        });

        initComponents();
        // chatArea에 HyperlinkListener를 한 번만 추가합니다.
        chatArea.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                if (e.getDescription() != null && e.getDescription().startsWith("server_uploads")) {
                    String filePath = e.getDescription();
                    String fileName = new File(filePath).getName();
                    downloadFile(filePath, fileName);
                }
            }
        });

        chatClient.setResponseListener(ServerResponse.ResponseType.SUCCESS, response -> {
            if (response.isSuccess() && "Left chat room successfully.".equals(response.getMessage())) {
                SwingUtilities.invokeLater(() -> {
                    // ChatClientGUI에서 이 다이얼로그를 openChatRoomDialogs 맵에서 제거해야 합니다.
                    // ChatClientGUI가 채팅방 목록 업데이트를 받으면 알아서 다이얼로그를 제거할 것입니다.
                    dispose(); // 현재 채팅방 다이얼로그 닫기
                });
            }
        });
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        JLabel roomNameLabel = new JLabel("<html><b>" + chatRoom.getRoomName() + "</b> (참여자: " + chatRoom.getParticipants().size() + "명)</html>");
        topPanel.add(roomNameLabel, BorderLayout.WEST);

        // 메뉴 버튼 추가
        JButton menuButton = new JButton("메뉴");
        menuButton.addActionListener(e -> {
            JPopupMenu popupMenu = new JPopupMenu();

            // 1. 친구 초대 메뉴 아이템
            JMenuItem inviteMenuItem = new JMenuItem("친구 초대");
            inviteMenuItem.addActionListener(ev -> showInviteFriendDialog());
            popupMenu.add(inviteMenuItem);

            // 2. 공지 내역 메뉴 아이템
            JMenuItem noticeMenuItem = new JMenuItem("공지 내역");
            noticeMenuItem.addActionListener(ev -> {
                // ChatClientGUI에 noticeFrame을 가져오는 public getter를 추가해야 합니다.
                ((ChatClientGUI) getParent()).getNoticeFrame().setVisible(true);
                chatClient.getNoticeMessages(); // 공지 내역 업데이트 요청
            });
            popupMenu.add(noticeMenuItem);

            // 3. 타임라인 메뉴 아이템 (그룹 채팅일 경우에만 표시)
            if (chatRoom.isGroupChat()) {
                JMenuItem timelineMenuItem = new JMenuItem("타임라인");
                timelineMenuItem.addActionListener(ev -> {
                    // ChatClientGUI에 timelineFrame을 가져오는 public getter를 추가해야 합니다.
                    ((ChatClientGUI) getParent()).getTimelineFrame().setVisible(true);
                    chatClient.getTimelineEvents(chatRoom.getRoomId()); // 해당 채팅방의 타임라인 요청
                });
                popupMenu.add(timelineMenuItem);
            }

            popupMenu.addSeparator(); // 구분선 추가

            // 4. 채팅방 나가기 메뉴 아이템
            JMenuItem leaveRoomMenuItem = new JMenuItem("채팅방 나가기");
            leaveRoomMenuItem.addActionListener(ev -> {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "정말로 이 채팅방을 나가시겠습니까? 나간 채팅방은 목록에서 사라집니다.",
                        "채팅방 나가기 확인", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    // 서버에 LEAVE_CHAT_ROOM 요청 전송
                    Map<String, Object> data = new HashMap<>();
                    data.put("roomId", chatRoom.getRoomId());
                    chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.LEAVE_CHAT_ROOM, data));
                    // 성공 응답은 setResponseListener에서 처리됩니다.
                }
            });
            popupMenu.add(leaveRoomMenuItem);

            popupMenu.show(menuButton, 0, menuButton.getHeight());
        });
        topPanel.add(menuButton, BorderLayout.EAST);
        mainPanel.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        chatArea = new JEditorPane();
        editorKit = new HTMLEditorKit();
        chatArea.setEditorKit(editorKit);
        doc = (HTMLDocument) chatArea.getDocument();
        chatArea.setEditable(false);
        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        centerPanel.add(chatScrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        messageInput = new JTextField();
        messageInput.addActionListener(e -> sendMessage());
        sendButton = new JButton("전송");
        sendButton.addActionListener(e -> sendMessage());
        fileAttachButton = new JButton("파일/사진");
        fileAttachButton.addActionListener(e -> attachFile());
        noticeCheckBox = new JCheckBox("공지");

        inputPanel.add(fileAttachButton, BorderLayout.WEST); // 기존 위치 변경
        inputPanel.add(messageInput, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        inputPanel.add(noticeCheckBox, BorderLayout.SOUTH);

        centerPanel.add(inputPanel, BorderLayout.SOUTH);

        mainPanel.add(centerPanel, BorderLayout.CENTER);
        add(mainPanel);
    }

    public void loadMessages() {
        chatClient.getMessagesInRoom(chatRoom.getRoomId());
    }

    private void sendMessage() {
        String content = messageInput.getText().trim();
        if (content.isEmpty()) {
            return;
        }

        MessageType type = MessageType.TEXT;
        boolean isNotice = noticeCheckBox.isSelected();

        if (content.startsWith("/")) {
            String[] parts = content.split(" ", 2);
            String command = parts[0];
            String description = parts.length > 1 ? parts[1] : "";

            if (command.equals("/start") || command.equals("/end")) {
                chatClient.sendMessage(chatRoom.getRoomId(), content, MessageType.COMMAND, false);
                // 서버에서 타임라인 이벤트를 처리하고 타임라인 업데이트를 브로드캐스트할 수 있도록 할 필요가 있습니다.
                // ChatServer에 timelineDAO를 사용하여 이벤트 저장 로직이 있어야 합니다.
            } else {
                chatClient.sendMessage(chatRoom.getRoomId(), content, type, isNotice);
            }
        } else {
            chatClient.sendMessage(chatRoom.getRoomId(), content, type, isNotice);
        }

        messageInput.setText("");
        noticeCheckBox.setSelected(false);
    }

    private void attachFile() {
        JFileChooser fileChooser = new JFileChooser();
        int returnValue = fileChooser.showOpenDialog(this);
        if (returnValue == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            try {
                byte[] fileBytes = Files.readAllBytes(selectedFile.toPath());
                chatClient.uploadFile(chatRoom.getRoomId(), selectedFile.getName(), fileBytes);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "파일 읽기 오류: " + e.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void downloadFile(String filePath, String fileName) {
        // ChatClientGUI에서 파일 다운로드 성공 응답을 처리하므로, 여기서는 요청만 보냅니다.
        // ChatClientGUI의 handleFileDownloadSuccess 메서드에서 실제로 파일을 저장합니다.
        chatClient.downloadFile(filePath);
        JOptionPane.showMessageDialog(this, "파일 다운로드 요청됨. 서버 응답을 기다립니다.", "정보", JOptionPane.INFORMATION_MESSAGE);
    }

    public void displayMessages(List<Message> messages) {
        try {
            doc.remove(0, doc.getLength()); // 기존 내용 삭제
            if (messages != null) { // 메시지 리스트가 null이 아닌지 확인
                for (Message message : messages) {
                    appendMessageToChatArea(message);
                }
            }
        } catch (BadLocationException e) {
            System.err.println("Error clearing or appending messages: " + e.getMessage());
        }
    }

    public void appendMessageToChatArea(Message message) {
        if (message == null) {
            return;
        }

        try {
            String color = "black";
            String fontWeight = "normal";
            String fontStyle = "normal";
            String backgroundColor = "white";
            String textColor = "black";
            String contentToShow = message.getContent();
            String prefix = "";
            String suffix = "";

            if (message.isNotice()) {
                backgroundColor = "#FFF2CC"; // 연한 노란색
                textColor = "red";
                fontWeight = "bold";
                prefix = "<span style='color: red;'>[공지] </span>";
            }

            if (message.getMessageType() == MessageType.FILE || message.getMessageType() == MessageType.IMAGE) {
                if (message.getContent() != null && !message.getContent().trim().isEmpty()) {
                    String fileName = new File(message.getContent()).getName();
                    contentToShow = "<a href='" + message.getContent() + "'>" + fileName + " (클릭하여 다운로드)</a>";
                } else {
                    contentToShow = "[잘못된 파일 링크]";
                }
            } else if (message.getMessageType() == MessageType.COMMAND) {
                fontStyle = "italic";
                color = "blue";
                prefix = "<span style='color: blue;'>[명령] </span>";
            } else if (message.getMessageType() == MessageType.SYSTEM) {
                fontStyle = "italic";
                color = "gray";
                prefix = "<span style='color: gray;'>[시스템] </span>";
            }

            // 미열람 카운트 표시 (발신자 자신 포함)
            // 발신자 자신도 메시지를 읽지 않은 것으로 간주될 수 있으므로,
            // ChatServer.broadcastMessageToRoom에서 보낸 사람에게는 읽음 처리하도록 로직이 추가되어야 함.
            if (message.getUnreadCount() > 0) {
                suffix = " <span style='font-size: 0.8em; color: gray;'>(" + message.getUnreadCount() + "명 미열람)</span>";
            }


            String htmlMessage = String.format(
                    "<div style='background-color: %s; padding: 5px; margin-bottom: 3px; border-radius: 5px;'>" +
                            "<span style='color: #888; font-size: 0.9em;'>%s</span> " +
                            "<span style='font-weight: %s; color: %s; font-style: %s;'>%s%s%s</span></div>",
                    backgroundColor,
                    message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + message.getSenderNickname(),
                    fontWeight, textColor, fontStyle, prefix, contentToShow, suffix
            );

            javax.swing.text.Element body = doc.getRootElements()[0].getElement(1);
            doc.insertAfterEnd(body, htmlMessage);
            chatArea.setCaretPosition(doc.getLength());

        } catch (BadLocationException | IOException e) {
            System.err.println("Error appending message to chat area: " + e.getMessage());
        }
    }


    private void showInviteFriendDialog() {
        JDialog inviteDialog = new JDialog(this, "친구 초대", true);
        inviteDialog.setSize(300, 400);
        inviteDialog.setLocationRelativeTo(this);
        inviteDialog.setLayout(new BorderLayout());

        DefaultListModel<User> invitabaleUsersModel = new DefaultListModel<>();
        JList<User> invitabaleUsersList = new JList<>(invitabaleUsersModel);
        invitabaleUsersList.setCellRenderer(new ChatClientGUI.FriendListCellRenderer());
        invitabaleUsersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // 메인 GUI의 friendListModel에 접근하여 친구 목록 가져오기
        DefaultListModel<User> mainFriendListModel = ((ChatClientGUI)getParent()).getFriendListModel();
        List<User> allFriends = java.util.Collections.list(mainFriendListModel.elements());

        List<User> participants = chatRoom.getParticipants();

        for (User friend : allFriends) {
            boolean isParticipant = false;
            for (User participant : participants) {
                if (friend.getUserId() == participant.getUserId()) {
                    isParticipant = true;
                    break;
                }
            }
            if (!isParticipant && friend.getUserId() != currentUser.getUserId()) {
                invitabaleUsersModel.addElement(friend);
            }
        }

        JScrollPane userScrollPane = new JScrollPane(invitabaleUsersList);
        userScrollPane.setBorder(new TitledBorder("초대할 친구 선택 (Ctrl/Shift 클릭)"));
        inviteDialog.add(userScrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton sendInviteButton = new JButton("초대");
        JButton cancelButton = new JButton("취소");

        sendInviteButton.addActionListener(e -> {
            List<User> selectedUsers = invitabaleUsersList.getSelectedValuesList();
            if (selectedUsers.isEmpty()) {
                JOptionPane.showMessageDialog(inviteDialog, "초대할 친구를 선택해주세요.", "안내", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            for (User userToInvite : selectedUsers) {
                chatClient.inviteUserToRoom(chatRoom.getRoomId(), userToInvite.getUserId());
            }
            inviteDialog.dispose();
        });

        cancelButton.addActionListener(e -> inviteDialog.dispose());

        buttonPanel.add(sendInviteButton);
        buttonPanel.add(cancelButton);
        inviteDialog.add(buttonPanel, BorderLayout.SOUTH);

        inviteDialog.setVisible(true);
    }
}