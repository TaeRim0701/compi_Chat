package chat.compi.GUI;

import chat.compi.Controller.ChatClient;
import chat.compi.Dto.ServerResponse;
import chat.compi.Entity.User;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent; // ActionEvent 임포트 추가
import java.awt.event.ActionListener; // ActionListener 임포트 추가
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginGUI extends JFrame {
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton loginButton;
    private JButton registerButton; // 회원가입 버튼 유지

    private ChatClient chatClient;
    private ExecutorService responseProcessor;

    public LoginGUI() {
        setTitle("로그인");
        setSize(400, 250); // 회원가입 필드 제거로 크기 조정
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        chatClient = new ChatClient();
        if (!chatClient.connect()) {
            JOptionPane.showMessageDialog(this, "서버 연결에 실패했습니다.", "연결 오류", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        responseProcessor = Executors.newSingleThreadExecutor();
        responseProcessor.submit(chatClient::processResponses);

        chatClient.setResponseListener(ServerResponse.ResponseType.LOGIN_SUCCESS, this::handleLoginSuccess);
        chatClient.setResponseListener(ServerResponse.ResponseType.FAIL, this::handleLoginOrRegisterFail);
        // REGISTER_SUCCESS 리스너는 LoginGUI에서는 더 이상 필요하지 않음. RegisterGUI에서 처리.
        // chatClient.setResponseListener(ServerResponse.ResponseType.REGISTER_SUCCESS, this::handleRegisterSuccess);


        initComponents();
    }

    private void initComponents() {
        JPanel panel = new JPanel(new GridLayout(3, 2, 10, 10)); // 닉네임 필드 제거로 행 수 조정
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        panel.add(new JLabel("아이디:"));
        usernameField = new JTextField();
        // 아이디 필드에서 엔터 입력 시 로그인 시도
        usernameField.addActionListener(e -> performLogin());
        panel.add(usernameField);

        panel.add(new JLabel("비밀번호:"));
        passwordField = new JPasswordField();
        // 비밀번호 필드에서 엔터 입력 시 로그인 시도
        passwordField.addActionListener(e -> performLogin());
        panel.add(passwordField);

        loginButton = new JButton("로그인");
        loginButton.addActionListener(e -> performLogin());
        panel.add(loginButton);

        registerButton = new JButton("회원가입");
        registerButton.addActionListener(e -> {
            // 회원가입 버튼 클릭 시 RegisterGUI를 띄움
            new RegisterGUI(chatClient).setVisible(true);
            // LoginGUI는 유지하거나 숨길 수 있음. 여기서는 유지.
            // this.setVisible(false); // 회원가입 창을 띄울 때 로그인 창을 숨기려면 이 주석을 해제
        });
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

    private void handleLoginSuccess(ServerResponse response) {
        User user = (User) response.getData().get("user");
        chatClient.setCurrentUser(user);
        SwingUtilities.invokeLater(() -> {
            new ChatClientGUI(chatClient).setVisible(true); // ChatClientGUI 열기
            this.dispose(); // 로그인 창 닫기
        });
    }

    private void handleLoginOrRegisterFail(ServerResponse response) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, response.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
        });
    }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginGUI().setVisible(true));
    }
}