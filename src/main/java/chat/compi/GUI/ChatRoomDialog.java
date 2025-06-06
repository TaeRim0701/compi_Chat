// ChatRoomDialog.java
package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ClientRequest;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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

        chatArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int pos = chatArea.viewToModel(e.getPoint());
                    HTMLDocument doc = (HTMLDocument) chatArea.getDocument();
                    Element element = doc.getCharacterElement(pos);

                    Integer messageId = null;
                    Element currentElement = element;
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
                        currentElement = currentElement.getParentElement();
                    }

                    if (messageId != null) {
                        final int finalMessageId = messageId;
                        JPopupMenu popupMenu = new JPopupMenu();
                        JMenuItem noticeItem = new JMenuItem("공지");
                        JMenuItem renotifyItem = new JMenuItem("재알림");

                        noticeItem.addActionListener(ev -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("messageId", finalMessageId);
                            data.put("isNotice", true);
                            data.put("roomId", chatRoom.getRoomId());
                            chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.MARK_AS_NOTICE, data));
                        });

                        renotifyItem.addActionListener(ev -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("roomId", chatRoom.getRoomId());
                            data.put("messageId", finalMessageId);
                            chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.RESEND_NOTIFICATION, data));
                            JOptionPane.showMessageDialog(ChatRoomDialog.this, "재알림 요청을 서버로 보냈습니다.", "재알림", JOptionPane.INFORMATION_MESSAGE);
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

        inputPanel.add(fileAttachButton, BorderLayout.WEST);
        inputPanel.add(messageInput, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

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

        if (content.startsWith("/s ")) {
            String projectName = content.substring(3).trim();
            if (projectName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "프로젝트 이름을 입력해주세요.\n예시: /s 나의 멋진 프로젝트", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }
            chatClient.addTimelineEvent(chatRoom.getRoomId(), "/s", "프로젝트 시작", "PROJECT_START", projectName);
            messageInput.setText("");
            return;
        }
        else if (content.startsWith("/del ")) {
            String projectNameToDelete = content.substring(5).trim();
            if (projectNameToDelete.isEmpty()) {
                JOptionPane.showMessageDialog(this, "삭제할 프로젝트 이름을 입력해주세요.\n예시: /del 삭제할 프로젝트", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }
            chatClient.deleteTimelineEvent(chatRoom.getRoomId(), projectNameToDelete);
            messageInput.setText("");
            return;
        }
        // "/c (프로젝트 이름)/(내용)" 명령어 처리 추가
        else if (content.startsWith("/c ")) {
            String commandArgs = content.substring(3).trim(); // "/c " 이후의 문자열
            int separatorIndex = commandArgs.indexOf("/"); // '/' 기준으로 분리

            if (separatorIndex == -1 || separatorIndex == 0 || separatorIndex == commandArgs.length() - 1) {
                JOptionPane.showMessageDialog(this, "프로젝트 이름과 내용을 '/'로 구분하여 입력해주세요.\n예시: /c 나의 프로젝트/새로운 내용", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }

            String projectName = commandArgs.substring(0, separatorIndex).trim();
            String projectContent = commandArgs.substring(separatorIndex + 1).trim();

            if (projectName.isEmpty() || projectContent.isEmpty()) {
                JOptionPane.showMessageDialog(this, "프로젝트 이름과 내용을 모두 입력해주세요.\n예시: /c 나의 프로젝트/새로운 내용", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }

            chatClient.addProjectContentToTimeline(chatRoom.getRoomId(), projectName, projectContent);
            messageInput.setText("");
            return;
        }


        // 기존 메시지 전송 로직
        MessageType type = MessageType.TEXT;
        if (content.startsWith("/")) {
            chatClient.sendMessage(chatRoom.getRoomId(), content, MessageType.COMMAND, false);
        } else {
            chatClient.sendMessage(chatRoom.getRoomId(), content, type, false);
        }
        messageInput.setText("");
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

                    if (message.getSenderId() != currentUser.getUserId() && message.getMessageType() != MessageType.SYSTEM) {
                        boolean isReadByCurrentUser = message.getReaders() != null &&
                                message.getReaders().stream().anyMatch(reader -> reader.getUserId() == currentUser.getUserId());
                        if (!isReadByCurrentUser) {
                            chatClient.markMessageAsRead(message.getMessageId());
                        }
                    }
                }
            }
            chatArea.setCaretPosition(doc.getLength());

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

            if (message.getSenderId() == currentUser.getUserId()) {
                backgroundColor = "#DCF8C6";
                outerDivAlign = "text-align: right;";
                innerBubbleMargin = "margin-left: 15%;";
            } else {
                backgroundColor = "#E5E5EA";
                outerDivAlign = "text-align: left;";
                innerBubbleMargin = "margin-right: 15%;";
            }

            if (message.isNotice()) {
                backgroundColor = "#FFF2CC";
                textColor = "red";
                fontWeight = "bold";
                prefix = "<span style='color: red;'>[공지] </span>";
                outerDivAlign = "text-align: center;";
                innerBubbleMargin = "margin-left: auto; margin-right: auto;";
            }

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
            } else {
                timestampAndSender = (message.getSenderId() == currentUser.getUserId()) ?
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) :
                        message.getSentAt().format(DateTimeFormatter.ofPattern("HH:mm")) + " " + message.getSenderNickname();
            }

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

            String htmlMessage = String.format(
                    "<div data-message-id='%d' style='clear: both; margin-bottom: 5px; %s'>" +
                            "<div style='display: inline-block; background-color: %s; padding: 8px 12px; border-radius: 10px; max-width: 70%%; word-wrap: break-word; %s'>" +
                            "<span style='color: #888; font-size: 0.8em; display: block; %s'>%s</span>" +
                            "<span style='font-weight: %s; color: %s; font-style: %s; display: block;'>%s%s%s</span>" +
                            "</div></div>",
                    message.getMessageId(),
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