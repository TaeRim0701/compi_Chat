package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ClientRequest;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.AttributeSet; // 추가
import javax.swing.text.Element; // 추가
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter; // 추가
import java.awt.event.MouseEvent; // 추가
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    // private JCheckBox noticeCheckBox; // 공지 체크박스 제거

    private JLabel roomNameLabel;

    public ChatRoomDialog(JFrame parent, ChatClient client, ChatRoom room) {
        super(parent, room.getRoomName(), false);
        this.chatClient = client;
        this.chatRoom = room;
        this.currentUser = client.getCurrentUser();

        setSize(700, 500);
        setLocationRelativeTo(parent);

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                System.out.println("ChatRoomDialog for room " + chatRoom.getRoomId() + " disposed.");
            }
        });

        initComponents();
        chatArea.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                if (e.getDescription() != null && e.getDescription().startsWith("server_uploads")) {
                    String filePath = e.getDescription();
                    String fileName = new File(filePath).getName();
                    downloadFile(filePath, fileName);
                }
            }
        });

        // 채팅 영역에 우클릭 리스너 추가
        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) { // 우클릭(플랫폼별 팝업 트리거) 감지
                    int pos = chatArea.viewToModel(e.getPoint()); // 클릭 위치의 모델 오프셋
                    HTMLDocument doc = (HTMLDocument) chatArea.getDocument();
                    Element element = doc.getCharacterElement(pos); // 해당 오프셋의 HTML 요소

                    Integer messageId = null;
                    Element currentElement = element;
                    // 클릭된 요소부터 상위 요소로 이동하며 data-message-id 속성 찾기
                    while (currentElement != null && messageId == null) {
                        AttributeSet attrs = currentElement.getAttributes();
                        String msgIdStr = (String) attrs.getAttribute("data-message-id");
                        if (msgIdStr != null) {
                            try {
                                messageId = Integer.parseInt(msgIdStr);
                            } catch (NumberFormatException ex) {
                                System.err.println("Invalid message ID in HTML: " + msgIdStr);
                            }
                        }
                        currentElement = currentElement.getParentElement(); // 다음 상위 요소
                    }

                    if (messageId != null) {
                        final int finalMessageId = messageId;
                        JPopupMenu popupMenu = new JPopupMenu();
                        JMenuItem noticeItem = new JMenuItem("공지");
                        JMenuItem renotifyItem = new JMenuItem("재알림");

                        noticeItem.addActionListener(ev -> {
                            // 서버에 공지 메시지 요청 전송
                            Map<String, Object> data = new HashMap<>();
                            data.put("messageId", finalMessageId);
                            data.put("isNotice", true); // 공지로 설정
                            data.put("roomId", chatRoom.getRoomId()); // 채팅방 ID도 함께 보냄
                            chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.MARK_AS_NOTICE, data));
                        });

                        renotifyItem.addActionListener(ev -> {
                            // 재알림 기능은 아직 구현되지 않았음을 알림
                            JOptionPane.showMessageDialog(ChatRoomDialog.this, "재알림 기능은 아직 구현되지 않았습니다.", "안내", JOptionPane.INFORMATION_MESSAGE);
                        });

                        popupMenu.add(noticeItem);
                        popupMenu.add(renotifyItem);
                        popupMenu.show(e.getComponent(), e.getX(), e.getY());
                    } else {
                        System.out.println("DEBUG: Right-click not on a message with ID.");
                    }
                }
            }
        });
    }

    private void initComponents() {
        JPanel mainPanel = new JPanel(new BorderLayout());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        roomNameLabel = new JLabel("<html><b>" + chatRoom.getRoomName() + "</b> (참여자: " + chatRoom.getParticipants().size() + "명)</html>");
        topPanel.add(roomNameLabel, BorderLayout.WEST);

        JButton menuButton = new JButton("메뉴");
        menuButton.addActionListener(e -> {
            JPopupMenu popupMenu = new JPopupMenu();

            JMenuItem inviteMenuItem = new JMenuItem("친구 초대");
            inviteMenuItem.addActionListener(ev -> showInviteFriendDialog());
            popupMenu.add(inviteMenuItem);

            JMenuItem noticeMenuItem = new JMenuItem("공지 내역");
            noticeMenuItem.addActionListener(ev -> {
                ((ChatClientGUI) getParent()).getNoticeFrame().setVisible(true);
                chatClient.getNoticeMessages(chatRoom.getRoomId());
            });
            popupMenu.add(noticeMenuItem);

            JMenuItem timelineMenuItem = new JMenuItem("타임라인");
            timelineMenuItem.addActionListener(ev -> {
                ((ChatClientGUI) getParent()).getTimelineFrame().setVisible(true);
                chatClient.getTimelineEvents(chatRoom.getRoomId());
            });
            popupMenu.add(timelineMenuItem);

            popupMenu.addSeparator();

            JMenuItem leaveRoomMenuItem = new JMenuItem("채팅방 나가기");
            leaveRoomMenuItem.addActionListener(ev -> {
                int confirm = JOptionPane.showConfirmDialog(this,
                        "정말로 이 채팅방을 나가시겠습니까? 나간 채팅방은 목록에서 사라집니다.",
                        "채팅방 나가기 확인", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("roomId", chatRoom.getRoomId());
                    chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.LEAVE_CHAT_ROOM, data));
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
        // noticeCheckBox = new JCheckBox("공지"); // 공지 체크박스 제거

        inputPanel.add(fileAttachButton, BorderLayout.WEST);
        inputPanel.add(messageInput, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        // inputPanel.add(noticeCheckBox, BorderLayout.SOUTH); // 공지 체크박스 제거

        centerPanel.add(inputPanel, BorderLayout.SOUTH);

        mainPanel.add(centerPanel, BorderLayout.CENTER);
        add(mainPanel);
    }

    public void loadMessages() {
        chatClient.getMessagesInRoom(chatRoom.getRoomId());
    }

    public void updateChatRoomInfo(ChatRoom updatedRoom) {
        this.chatRoom = updatedRoom;
        setTitle(chatRoom.getRoomName());
        roomNameLabel.setText("<html><b>" + chatRoom.getRoomName() + "</b> (참여자: " + chatRoom.getParticipants().size() + "명)</html>");
        System.out.println("ChatRoomDialog for room " + chatRoom.getRoomId() + " updated. New participant count: " + chatRoom.getParticipants().size());
    }

    private void sendMessage() {
        String content = messageInput.getText().trim();
        if (content.isEmpty()) {
            return;
        }

        MessageType type = MessageType.TEXT;
        // boolean isNotice = noticeCheckBox.isSelected(); // 체크박스 제거로 인한 변경

        if (content.startsWith("/")) {
            String[] parts = content.split(" ", 2);
            String command = parts[0];
            String description = parts.length > 1 ? parts[1] : "";

            if (command.equals("/start") || command.equals("/end")) {
                // 공지는 나중에 우클릭으로 설정하므로, 여기서는 항상 false
                chatClient.sendMessage(chatRoom.getRoomId(), content, MessageType.COMMAND, false);
            } else {
                // 공지는 나중에 우클릭으로 설정하므로, 여기서는 항상 false
                chatClient.sendMessage(chatRoom.getRoomId(), content, type, false);
            }
        } else {
            // 공지는 나중에 우클릭으로 설정하므로, 여기서는 항상 false
            chatClient.sendMessage(chatRoom.getRoomId(), content, type, false);
        }

        messageInput.setText("");
        // noticeCheckBox.setSelected(false); // 체크박스 제거로 인한 변경
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
        chatClient.downloadFile(filePath);
        JOptionPane.showMessageDialog(this, "파일 다운로드 요청됨. 서버 응답을 기다립니다.", "정보", JOptionPane.INFORMATION_MESSAGE);
    }

    public void displayMessages(List<Message> messages) {
        try {
            doc.remove(0, doc.getLength());
            if (messages != null) {
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
            String backgroundColor;
            String fontWeight = "normal";
            String fontStyle = "normal";
            String textColor = "black";
            String prefix = "";
            String suffix = "";
            String outerDivAlign;
            String innerBubbleMargin;

            // 내 메시지인 경우 (오른쪽 정렬)
            if (message.getSenderId() == currentUser.getUserId()) {
                backgroundColor = "#DCF8C6"; // 연한 초록색
                outerDivAlign = "text-align: right;";
                innerBubbleMargin = "margin-left: 15%;"; // 오른쪽으로 밀기
            } else { // 상대방이 보낸 메시지인 경우 (왼쪽 정렬)
                backgroundColor = "#E5E5EA"; // 연한 회색
                outerDivAlign = "text-align: left;";
                innerBubbleMargin = "margin-right: 15%;"; // 왼쪽으로 밀기
            }

            // 공지 메시지 스타일 (우선순위 높음)
            if (message.isNotice()) {
                backgroundColor = "#FFF2CC"; // 노란색 계열
                textColor = "red";
                fontWeight = "bold";
                prefix = "<span style='color: red;'>[공지] </span>";
                outerDivAlign = "text-align: center;"; // 중앙 정렬
                innerBubbleMargin = "margin-left: auto; margin-right: auto;"; // 중앙 정렬을 위한 자동 마진
            }

            // 메시지 타입별 스타일
            String contentToShow = message.getContent();
            String timestampAndSender;

            if (message.getMessageType() == MessageType.COMMAND || message.getMessageType() == MessageType.SYSTEM) {
                fontStyle = "italic";
                textColor = (message.getMessageType() == MessageType.COMMAND) ? "blue" : "gray";
                prefix = (message.getMessageType() == MessageType.COMMAND) ? "<span style='color: blue;'>[명령] </span>" : "<span style='color: gray;'>[시스템] </span>";
                outerDivAlign = "text-align: center;";
                innerBubbleMargin = "margin-left: auto; margin-right: auto;";
                timestampAndSender = message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm"));
            } else if (message.getMessageType() == MessageType.FILE || message.getMessageType() == MessageType.IMAGE) {
                if (message.getContent() != null && !message.getContent().trim().isEmpty()) {
                    String fileName = new File(message.getContent()).getName();
                    contentToShow = "<a href='" + message.getContent() + "'>" + fileName + " (클릭하여 다운로드)</a>";
                } else {
                    contentToShow = "[잘못된 파일 링크]";
                }
                timestampAndSender = (message.getSenderId() == currentUser.getUserId()) ?
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) :
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + message.getSenderNickname();
            } else { // 일반 텍스트 메시지
                timestampAndSender = (message.getSenderId() == currentUser.getUserId()) ?
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) :
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + message.getSenderNickname();
            }

            // '읽은 사람' 목록 또는 '미열람자 수' 표시 로직
            if (message.getMessageType() != MessageType.SYSTEM && message.getReaders() != null) {
                List<String> readerNicknames = message.getReaders().stream()
                        .filter(reader -> reader.getUserId() != message.getSenderId())
                        .map(User::getNickname)
                        .collect(Collectors.toList());

                if (!readerNicknames.isEmpty()) {
                    suffix = " <span style='font-size: 0.7em; color: #666;'>읽음: " + String.join(", ", readerNicknames) + "</span>";
                } else {
                    if (message.getSenderId() == currentUser.getUserId() && message.getUnreadCount() > 0) {
                        suffix = " <span style='font-size: 0.8em; color: gray;'>(" + message.getUnreadCount() + "명 미열람)</span>";
                    }
                }
            }


            // HTML 메시지 구조에 data-message-id 속성 추가
            String htmlMessage = String.format(
                    "<div data-message-id='%d' style='clear: both; margin-bottom: 5px; %s'>" +
                            "<div style='display: inline-block; background-color: %s; padding: 8px 12px; border-radius: 10px; max-width: 70%%; word-wrap: break-word; %s'>" +
                            "<span style='color: #888; font-size: 0.8em; display: block; %s'>%s</span>" +
                            "<span style='font-weight: %s; color: %s; font-style: %s; display: block;'>%s%s%s</span>" +
                            "</div></div>",
                    message.getMessageId(), // messageId를 data-message-id에 전달
                    outerDivAlign,
                    backgroundColor,
                    innerBubbleMargin,
                    (message.getSenderId() == currentUser.getUserId() || message.getMessageType() == MessageType.SYSTEM || message.isNotice() || message.getMessageType() == MessageType.COMMAND ? "text-align: right;" : "text-align: left;"),
                    timestampAndSender,
                    fontWeight, textColor, fontStyle, prefix, contentToShow, suffix
            );

            editorKit.insertHTML(doc, doc.getLength(), htmlMessage, 0, 0, null);
            chatArea.setCaretPosition(doc.getLength());

        } catch (BadLocationException | IOException e) {
            System.err.println("Error appending message to chat area: " + e.getMessage());
            e.printStackTrace();
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