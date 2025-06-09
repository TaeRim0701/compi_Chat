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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime; // LocalDateTime 임포트
import java.time.format.DateTimeFormatter; // DateTimeFormatter 임포트
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Enumeration;

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

    // 스크롤할 메시지 ID 임시 저장 필드
    private int pendingScrollMessageId = -1;

    // JScrollPane 인스턴스를 저장하여 스크롤바 상태를 직접 확인
    private JScrollPane chatScrollPane;

    public ChatRoom getChatRoom() {
        return chatRoom;
    }

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
                    Boolean isMessageNotice = null;

                    Element currentElement = element;
                    while (currentElement != null && (messageId == null || isMessageNotice == null)) {
                        AttributeSet attrs = currentElement.getAttributes();
                        String msgIdStr = (String) attrs.getAttribute("data-message-id");
                        String isNoticeStr = (String) attrs.getAttribute("data-is-notice");

                        if (msgIdStr != null) {
                            try {
                                messageId = Integer.parseInt(msgIdStr);
                            } catch (NumberFormatException ex) {
                                System.err.println("Invalid message ID in HTML: " + msgIdStr);
                            }
                        }
                        if (isNoticeStr != null) {
                            isMessageNotice = Boolean.parseBoolean(isNoticeStr);
                        }
                        currentElement = currentElement.getParentElement();
                    }

                    if (messageId != null) {
                        final int finalMessageId = messageId;
                        final boolean finalIsMessageNotice = (isMessageNotice != null && isMessageNotice);

                        JPopupMenu popupMenu = new JPopupMenu();
                        JMenuItem noticeItem = new JMenuItem("공지 설정");
                        JMenuItem removeNoticeItem = new JMenuItem("공지 해제");
                        JMenuItem renotifyItem = new JMenuItem("재알림");

                        noticeItem.addActionListener(ev -> {
                            // 부모 프레임(ChatClientGUI)을 가져와서 전달
                            JFrame parentFrame = (JFrame) SwingUtilities.getWindowAncestor(ChatRoomDialog.this);
                            LocalDateTime selectedDateTime = showDateTimePickerDialog(parentFrame); // 이 부분을 수정
                            if (selectedDateTime != null) {
                                Map<String, Object> data = new HashMap<>();
                                data.put("messageId", finalMessageId);
                                data.put("isNotice", true);
                                data.put("roomId", chatRoom.getRoomId());
                                data.put("expiryTime", selectedDateTime);
                                chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.MARK_AS_NOTICE, data));
                            }
                        });

                        removeNoticeItem.addActionListener(ev -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("messageId", finalMessageId);
                            data.put("isNotice", false);
                            data.put("roomId", chatRoom.getRoomId());
                            data.put("expiryTime", null);
                            chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.MARK_AS_NOTICE, data));
                        });

                        renotifyItem.addActionListener(ev -> {
                            Map<String, Object> data = new HashMap<>();
                            data.put("roomId", chatRoom.getRoomId());
                            data.put("messageId", finalMessageId);
                            chatClient.sendRequest(new ClientRequest(ClientRequest.RequestType.RESEND_NOTIFICATION, data));
                            JOptionPane.showMessageDialog(ChatRoomDialog.this, "재알림 요청을 서버로 보냈습니다.", "재알림", JOptionPane.INFORMATION_MESSAGE);
                        });

                        removeNoticeItem.setEnabled(finalIsMessageNotice);
                        noticeItem.setEnabled(!finalIsMessageNotice);

                        popupMenu.add(noticeItem);
                        popupMenu.add(removeNoticeItem);
                        popupMenu.add(renotifyItem);
                        popupMenu.show(e.getComponent(), e.getX(), e.getY());
                    } else {
                        System.out.println("DEBUG: Right-click not on a message with ID.");
                    }
                }
            }
        });
    }

    private JPanel createNumberInputPanel(JTextField textField, int min, int max) {
        JPanel panel = new JPanel(new BorderLayout());
        JButton plusButton = new JButton("+");
        JButton minusButton = new JButton("-");

        plusButton.addActionListener(e -> {
            try {
                int val = Integer.parseInt(textField.getText());
                if (val < max) textField.setText(String.valueOf(val + 1));
            } catch (NumberFormatException ignored) {}
        });
        minusButton.addActionListener(e -> {
            try {
                int val = Integer.parseInt(textField.getText());
                if (val > min) textField.setText(String.valueOf(val - 1));
            } catch (NumberFormatException ignored) {}
        });

        panel.add(textField, BorderLayout.CENTER);
        panel.add(plusButton, BorderLayout.EAST);
        panel.add(minusButton, BorderLayout.WEST);
        return panel;
    }

    // 날짜/시간 선택 다이얼로그 메서드
    private LocalDateTime showDateTimePickerDialog(JFrame parentFrame) {
        JDialog dateTimeDialog = new JDialog(parentFrame, "공지 만료 시간 설정", true);
        dateTimeDialog.setSize(350, 300); // 다이얼로그 크기 조정
        dateTimeDialog.setLocationRelativeTo(parentFrame);
        dateTimeDialog.setLayout(new BorderLayout(10, 10)); // 여백 추가

        JPanel inputPanel = new JPanel(new GridBagLayout()); // GridBagLayout 사용
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // 현재 시간으로 필드 초기화
        LocalDateTime now = LocalDateTime.now();
        JTextField yearField = new JTextField(String.valueOf(now.getYear()));
        JTextField monthField = new JTextField(String.valueOf(now.getMonthValue()));
        JTextField dayField = new JTextField(String.valueOf(now.getDayOfMonth()));
        JTextField hourField = new JTextField(String.valueOf(now.getHour()));
        JTextField minuteField = new JTextField(String.valueOf(now.getMinute()));

        // 컴포넌트 추가: 년
        gbc.gridx = 0; gbc.gridy = 0; inputPanel.add(new JLabel("년:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; inputPanel.add(createNumberInputPanel(yearField, now.getYear(), 2100), gbc);

        // 컴포넌트 추가: 월
        gbc.gridx = 0; gbc.gridy = 1; inputPanel.add(new JLabel("월:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; inputPanel.add(createNumberInputPanel(monthField, 1, 12), gbc);

        // 컴포넌트 추가: 일
        gbc.gridx = 0; gbc.gridy = 2; inputPanel.add(new JLabel("일:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; inputPanel.add(createNumberInputPanel(dayField, 1, 31), gbc); // 월별 일수 유효성 검사는 실제 LocalDateTime.of에서 처리

        // 컴포넌트 추가: 시
        gbc.gridx = 0; gbc.gridy = 3; inputPanel.add(new JLabel("시:"), gbc);
        gbc.gridx = 1; gbc.gridy = 3; inputPanel.add(createNumberInputPanel(hourField, 0, 23), gbc);

        // 컴포넌트 추가: 분
        gbc.gridx = 0; gbc.gridy = 4; inputPanel.add(new JLabel("분:"), gbc);
        gbc.gridx = 1; gbc.gridy = 4; inputPanel.add(createNumberInputPanel(minuteField, 0, 59), gbc);

        dateTimeDialog.add(inputPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton setButton = new JButton("설정");
        JButton cancelButton = new JButton("취소");

        final LocalDateTime[] resultDateTime = {null}; // 최종 결과 저장용 배열

        setButton.addActionListener(e -> {
            try {
                int year = Integer.parseInt(yearField.getText());
                int month = Integer.parseInt(monthField.getText());
                int day = Integer.parseInt(dayField.getText());
                int hour = Integer.parseInt(hourField.getText());
                int minute = Integer.parseInt(minuteField.getText());

                LocalDateTime proposedDateTime = LocalDateTime.of(year, month, day, hour, minute);
                if (proposedDateTime.isBefore(LocalDateTime.now())) {
                    JOptionPane.showMessageDialog(dateTimeDialog, "만료 시간은 현재 시간보다 미래여야 합니다.", "시간 오류", JOptionPane.WARNING_MESSAGE);
                    resultDateTime[0] = null; // 유효하지 않은 시간
                    return;
                }
                resultDateTime[0] = proposedDateTime;
                dateTimeDialog.dispose();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dateTimeDialog, "유효한 숫자 값을 입력해주세요.", "입력 오류", JOptionPane.ERROR_MESSAGE);
            } catch (java.time.DateTimeException ex) {
                JOptionPane.showMessageDialog(dateTimeDialog, "유효하지 않은 날짜 또는 시간입니다: " + ex.getMessage(), "날짜/시간 오류", JOptionPane.ERROR_MESSAGE);
            }
        });

        cancelButton.addActionListener(e -> dateTimeDialog.dispose());

        buttonPanel.add(setButton);
        buttonPanel.add(cancelButton);
        dateTimeDialog.add(buttonPanel, BorderLayout.SOUTH);

        dateTimeDialog.setVisible(true); // 모달 다이얼로그 표시
        return resultDateTime[0]; // 다이얼로그 닫힌 후 결과 반환
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

            if (!chatRoom.isGroupChat() && chatRoom.getRoomName().startsWith("시스템 메시지 for")) {
                leaveRoomMenuItem.setEnabled(false); // 시스템 채팅방일 경우 비활성화
                leaveRoomMenuItem.setText("채팅방 나가기 (시스템 채팅방)"); // 텍스트 변경
            }

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
        loadMessages(-1); // 기본 호출 (스크롤 메시지 ID 없음)
    }

    public void loadMessages(int messageIdToScroll) { // messageIdToScroll 인자 추가
        this.pendingScrollMessageId = messageIdToScroll; // 메시지 ID 저장
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

        // 특정 명령어 처리
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
        } else if (content.startsWith("/del ")) {
            String projectNameToDelete = content.substring(5).trim();
            if (projectNameToDelete.isEmpty()) {
                JOptionPane.showMessageDialog(this, "삭제할 프로젝트 이름을 입력해주세요.\n예시: /del 삭제할 프로젝트", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }
            chatClient.deleteTimelineEvent(chatRoom.getRoomId(), projectNameToDelete);
            messageInput.setText("");
            return;
        } else if (content.startsWith("/c ")) {
            String commandArgs = content.substring(3).trim();
            int separatorIndex = commandArgs.indexOf("/");

            if (separatorIndex == -1 || separatorIndex == 0 || commandArgs.length() - 1 == separatorIndex) {
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
        } else if (content.startsWith("/d ")) {
            String projectNameToEnd = content.substring(3).trim();
            if (projectNameToEnd.isEmpty()) {
                JOptionPane.showMessageDialog(this, "종료할 프로젝트 이름을 입력해주세요.\n예시: /d 나의 프로젝트", "입력 오류", JOptionPane.WARNING_MESSAGE);
                messageInput.setText("");
                return;
            }
            chatClient.endProjectToTimeline(chatRoom.getRoomId(), projectNameToEnd);
            messageInput.setText("");
            return;
        }
        // 수정된 로직: 특정 명령어가 아닌 경우 또는 '/'로 시작하지만 정의된 명령어가 아닌 경우
        else if (content.startsWith("/")) {
            // 정의된 명령어가 아니지만 '/'로 시작하는 경우, 일반 텍스트로 보냅니다.
            chatClient.sendMessage(chatRoom.getRoomId(), content, MessageType.TEXT, false);
            messageInput.setText("");
        }
        else {
            // '/'로 시작하지 않는 일반 메시지
            chatClient.sendMessage(chatRoom.getRoomId(), content, MessageType.TEXT, false);
            messageInput.setText("");
        }
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
        // 현재 스크롤 위치 저장 (최하단 여부 판단용)
        boolean wasAtBottom = isUserAtBottom();

        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException e) {
            System.err.println("Error clearing messages: " + e.getMessage());
        }

        if (messages != null) {
            for (Message message : messages) {
                appendMessageToChatArea(message);

                // 현재 사용자가 보낸 메시지가 아니고,
                // 이 메시지를 읽은 사용자 목록에 현재 사용자가 포함되어 있지 않다면 읽음 처리 요청
                boolean alreadyReadByMe = false;
                if (message.getReaders() != null) {
                    for (User reader : message.getReaders()) {
                        if (reader.getUserId() == currentUser.getUserId()) {
                            alreadyReadByMe = true;
                            break;
                        }
                    }
                }

                if (message.getSenderId() != currentUser.getUserId() && !alreadyReadByMe) {
                    chatClient.markMessageAsRead(message.getMessageId());
                }
            }
        }

        // 모든 메시지 로드 후 스크롤 로직
        SwingUtilities.invokeLater(() -> {
            if (pendingScrollMessageId != -1) {
                scrollToMessage(pendingScrollMessageId);
                pendingScrollMessageId = -1; // 스크롤 요청 처리 후 초기화
            } else if (wasAtBottom) { // 새로운 메시지가 로드될 때만 (채팅방 열 때는 모든 메시지 로드 후 최하단)
                // 이전에 최하단에 있었다면 새로운 메시지 도착 시에도 최하단으로 스크롤
                chatArea.setCaretPosition(doc.getLength());
            }
            // else (wasAtBottom이 false인 경우): 사용자가 위로 스크롤한 상태이므로 자동 스크롤하지 않음
        });
    }

    public void appendMessageToChatArea(Message message) {
        if (message == null) {
            return;
        }

        // 새로운 메시지가 추가되기 전의 스크롤 위치를 확인
        // 만약 사용자 자신의 메시지이거나, 현재 스크롤이 최하단에 있다면 자동 스크롤해야 함.
        boolean shouldAutoScroll = (message.getSenderId() == currentUser.getUserId() || isUserAtBottom());

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

            if (message.getMessageType() == MessageType.SYSTEM) {
                fontStyle = "italic";
                textColor = "gray";
                prefix = "<span style='color: gray;'>[시스템] </span>";
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
            } else { // TEXT 또는 COMMAND 메시지 (COMMAND는 이제 TEXT처럼 처리)
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
                    "<div data-message-id='%d' data-is-notice='%b' style='clear: both; margin-bottom: 5px; %s'>" + // data-is-notice 추가
                            "<div style='display: inline-block; background-color: %s; padding: 8px 12px; border-radius: 10px; max-width: 70%%; word-wrap: break-word; %s'>" +
                            "<span style='color: #888; font-size: 0.8em; display: block; %s'>%s</span>" +
                            "<span style='font-weight: %s; color: %s; font-style: %s; display: block;'>%s%s%s</span>" +
                            "</div></div>",
                    message.getMessageId(),
                    message.isNotice(), // isNotice 값 추가
                    outerDivAlign,
                    backgroundColor,
                    innerBubbleMargin,
                    (message.getSenderId() == currentUser.getUserId() || message.getMessageType() == MessageType.SYSTEM || message.isNotice() ? "text-align: right;" : "text-align: left;"),
                    timestampAndSender,
                    fontWeight, textColor, fontStyle, prefix, contentToShow, suffix
            );

            editorKit.insertHTML(doc, doc.getLength(), htmlMessage, 0, 0, null);

            // 메시지 추가 후 자동 스크롤 조건
            if (shouldAutoScroll) {
                SwingUtilities.invokeLater(() -> chatArea.setCaretPosition(doc.getLength()));
            }

        } catch (BadLocationException | IOException e) {
            System.err.println("Error appending message to chat area: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 사용자가 현재 채팅창의 최하단에 스크롤되어 있는지 확인합니다.
     * @return 최하단에 스크롤되어 있다면 true, 그렇지 않다면 false
     */
    private boolean isUserAtBottom() {
        if (chatScrollPane == null) {
            return true; // 스크롤 패인이 없으면 항상 최하단으로 가정 (초기 로딩 시)
        }
        JScrollBar verticalScrollBar = chatScrollPane.getVerticalScrollBar();
        int maxScroll = verticalScrollBar.getMaximum() - verticalScrollBar.getVisibleAmount();
        int currentScroll = verticalScrollBar.getValue();

        // 오차 범위 50 픽셀 내라면 최하단으로 간주
        return (Math.abs(currentScroll - maxScroll) <= 50);
    }


    /**
     * 특정 메시지 ID로 채팅창을 스크롤합니다.
     * 이 메서드는 JEditorPane의 HTML 문서에서 해당 메시지 ID를 가진 요소를 찾아 스크롤합니다.
     * @param messageId 스크롤할 메시지의 ID
     */
    public void scrollToMessage(int messageId) {
        final int targetMessageId = messageId;
        final int maxRetries = 10;
        final int retryDelay = 50;

        Timer retryTimer = new Timer(retryDelay, new ActionListener() {
            private int currentRetry = 0;

            @Override
            public void actionPerformed(ActionEvent e) {
                currentRetry++;
                System.out.println("scrollToMessage: Retrying for ID " + targetMessageId + ", attempt " + currentRetry);

                HTMLDocument doc = (HTMLDocument) chatArea.getDocument();
                Element targetElement = findElementById(doc.getDefaultRootElement(), targetMessageId);

                if (targetElement != null) {
                    try {
                        Rectangle rect = chatArea.modelToView(targetElement.getStartOffset());
                        if (rect != null) {
                            JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, chatArea);
                            if (scrollPane != null) {
                                JViewport viewport = scrollPane.getViewport();
                                Point currentView = viewport.getViewPosition();
                                Point newViewPosition = new Point(currentView.x, rect.y);
                                viewport.setViewPosition(newViewPosition);
                            } else {
                                chatArea.scrollRectToVisible(rect);
                            }

                            chatArea.setSelectionStart(targetElement.getStartOffset());
                            chatArea.setSelectionEnd(targetElement.getEndOffset());
                            Timer selectionTimer = new Timer(500000, ev -> {
                                chatArea.setSelectionStart(doc.getLength());
                                chatArea.setSelectionEnd(doc.getLength());
                                // 여기서 강제 스크롤 코드를 제거합니다.
                                // chatArea.setCaretPosition(doc.getLength()); // 이 라인을 제거하세요!
                            });
                            selectionTimer.setRepeats(false);
                            selectionTimer.start();

                            System.out.println("Scrolled to message ID: " + targetMessageId + " after " + currentRetry + " retries.");
                            ((Timer) e.getSource()).stop();
                        }
                    } catch (BadLocationException ex) {
                        System.err.println("Error scrolling to message during retry: " + ex.getMessage());
                        ((Timer) e.getSource()).stop();
                    }
                } else if (currentRetry >= maxRetries) {
                    System.out.println("scrollToMessage: Message with ID " + targetMessageId + " not found after " + maxRetries + " attempts. Giving up.");
                    ((Timer) e.getSource()).stop();
                }
            }
        });
        retryTimer.setRepeats(true);
        retryTimer.start();
    }

    /**
     * HTML 문서에서 특정 data-message-id를 가진 Element를 재귀적으로 찾는 헬퍼 메서드.
     * JEditorPane의 HTMLDocument 구조는 복잡할 수 있으므로, 모든 자식 요소를 탐색합니다.
     * @param element 현재 탐색 중인 Element
     * @param messageId 찾으려는 메시지 ID
     * @return 찾은 Element 또는 null
     */
    private Element findElementById(Element element, int messageId) {
        if (element == null) {
            return null;
        }

        // 현재 Element가 찾고 있는 메시지 div인지 확인
        AttributeSet attrs = element.getAttributes();
        String msgIdStr = (String) attrs.getAttribute("data-message-id");
        if (msgIdStr != null) {
            try {
                if (Integer.parseInt(msgIdStr) == messageId) {
                    return element; // 찾았으면 반환
                }
            } catch (NumberFormatException e) {
                // 유효하지 않은 ID 형식, 무시하고 계속 탐색
            }
        }

        // 자식 Element들을 재귀적으로 탐색
        for (int i = 0; i < element.getElementCount(); i++) {
            Element child = element.getElement(i);
            Element found = findElementById(child, messageId);
            if (found != null) {
                return found; // 자식에서 찾았으면 반환
            }
        }
        return null; // 못 찾음
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