package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ServerResponse;

import javax.swing.*;
import java.awt.*;


public class RegisterGUI extends JDialog { // JDialog로 변경하여 부모 창에 종속되도록 함
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JTextField nicknameField;
    private JButton registerButton;

    private ChatClient chatClient;

    public RegisterGUI(ChatClient client) {
        super((JFrame) null, "회원가입", true); // 모달 다이얼로그로 설정
        this.chatClient = client;
        setSize(350, 250);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE); // 창 닫을 때 다이얼로그만 닫히도록

        // 회원가입 응답 리스너 등록
        chatClient.setResponseListener(ServerResponse.ResponseType.REGISTER_SUCCESS, this::handleRegisterSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.FAIL, this::handleRegisterFail); // 회원가입 실패 처리도 추가

        initComponents();
    }

    private void initComponents() {
        JPanel panel = new JPanel(new GridLayout(4, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        panel.add(new JLabel("아이디:"));
        usernameField = new JTextField();
        panel.add(usernameField);

        panel.add(new JLabel("비밀번호:"));
        passwordField = new JPasswordField();
        panel.add(passwordField);

        panel.add(new JLabel("닉네임:"));
        nicknameField = new JTextField();
        panel.add(nicknameField);

        registerButton = new JButton("회원가입");
        registerButton.addActionListener(e -> performRegister());
        panel.add(registerButton);

        // 취소 버튼 추가
        JButton cancelButton = new JButton("취소");
        cancelButton.addActionListener(e -> dispose()); // 다이얼로그 닫기
        panel.add(cancelButton); // 버튼을 두 개 추가하기 위해 GridLayout 조정 또는 FlowLayout 사용 고려

        add(panel);
    }

    private void performRegister() {
        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());
        String nickname = nicknameField.getText();

        if (username.isEmpty() || password.isEmpty() || nickname.isEmpty()) {
            JOptionPane.showMessageDialog(this, "아이디, 비밀번호, 닉네임을 모두 입력해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
            return;
        }

        chatClient.register(username, password, nickname);
    }

    private void handleRegisterSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "성공", JOptionPane.INFORMATION_MESSAGE);
            // 회원가입 성공 후 필드 초기화 및 다이얼로그 닫기
            usernameField.setText("");
            passwordField.setText("");
            nicknameField.setText("");
            dispose(); // 회원가입 성공 시 창 닫기
        });
    }

    private void handleRegisterFail(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "회원가입 오류", JOptionPane.ERROR_MESSAGE);
        });
    }
}