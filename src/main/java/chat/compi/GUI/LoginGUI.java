package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.User;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginGUI extends JFrame {
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JTextField nicknameField; // 회원가입 시 필요
    private JButton loginButton;
    private JButton registerButton;

    private ChatClient chatClient;
    private ExecutorService responseProcessor; // 응답 처리 스레드 풀

    public LoginGUI() {
        setTitle("로그인");
        setSize(400, 300);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // 화면 중앙에 배치

        chatClient = new ChatClient();
        if (!chatClient.connect()) {
            JOptionPane.showMessageDialog(this, "서버 연결에 실패했습니다.", "연결 오류", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // 응답 처리 스레드 시작
        responseProcessor = Executors.newSingleThreadExecutor();
        responseProcessor.submit(chatClient::processResponses); // ChatClient의 processResponses 메서드 호출

        // 로그인 응답 리스너 등록
        chatClient.setResponseListener(ServerResponse.ResponseType.LOGIN_SUCCESS, this::handleLoginSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.FAIL, this::handleLoginOrRegisterFail);
        chatClient.setResponseListener(ServerResponse.ResponseType.REGISTER_SUCCESS, this::handleRegisterSuccess);


        initComponents();
    }

    private void initComponents() {
        JPanel panel = new JPanel(new GridLayout(5, 2, 10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        panel.add(new JLabel("아이디:"));
        usernameField = new JTextField();
        panel.add(usernameField);

        panel.add(new JLabel("비밀번호:"));
        passwordField = new JPasswordField();
        panel.add(passwordField);

        panel.add(new JLabel("닉네임 (회원가입 시):"));
        nicknameField = new JTextField();
        panel.add(nicknameField);

        loginButton = new JButton("로그인");
        loginButton.addActionListener(e -> performLogin());
        panel.add(loginButton);

        registerButton = new JButton("회원가입");
        registerButton.addActionListener(e -> performRegister());
        panel.add(registerButton);

        add(panel);
    }

    private void performLogin() {
        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "아이디와 비밀번호를 입력해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
            return;
        }

        chatClient.login(username, password);
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

    private void handleLoginSuccess(ServerResponse response) {
        User user = (User) response.getData().get("user");
        chatClient.setCurrentUser(user);
        SwingUtilities.invokeLater(() -> {
            new ChatClientGUI(chatClient).setVisible(true);
            this.dispose(); // 로그인 창 닫기
        });
    }

    private void handleLoginOrRegisterFail(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        });
    }

    private void handleRegisterSuccess(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "성공", JOptionPane.INFORMATION_MESSAGE);
            // 회원가입 성공 후 로그인 필드 초기화
            usernameField.setText("");
            passwordField.setText("");
            nicknameField.setText("");
        });
    }

}