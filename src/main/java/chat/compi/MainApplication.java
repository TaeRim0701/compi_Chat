package chat.compi;

import chat.compi.Controller.ChatServer;
import chat.compi.GUI.LoginGUI;

import javax.swing.*;

@SuppressWarnings("unchecked")
public class MainApplication {

	public static void main(String[] args) {
		// 1. 서버 스레드 시작
		// 서버는 백그라운드에서 실행되어 클라이언트 연결을 기다립니다.
		Thread serverThread = new Thread(() -> {
			ChatServer server = new ChatServer();
			server.start();
		});
		serverThread.setName("ChatServerThread"); // 스레드 이름 설정 (디버깅에 용이)
		serverThread.start();

		// 서버가 완전히 시작될 시간을 잠시 대기
		// 실제 애플리케이션에서는 서버가 준비되었다는 신호를 보내는 메커니즘이 필요하지만,
		// 간단한 테스트를 위해 일정 시간 대기합니다.
		try {
			Thread.sleep(2000); // 2초 대기 (서버 시작 시간 고려)
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			System.err.println("Main application interrupted during server startup delay: " + e.getMessage());
		}

		// 2. 클라이언트 GUI 스레드 시작
		// Swing GUI는 EDT(Event Dispatch Thread)에서 실행되어야 합니다.
		SwingUtilities.invokeLater(() -> {
			LoginGUI loginGUI = new LoginGUI();
			loginGUI.setVisible(true);
		});

		// 참고: 이 main 메서드는 서버와 GUI가 동시에 실행되도록 하지만,
		// 서버와 클라이언트는 여전히 별도의 엔티티로 동작합니다.
		// 서버는 별도의 스레드에서 실행되므로, 이 main 메서드는 클라이언트 GUI를 띄운 후 종료됩니다.
		// 하지만 JVM은 서버 스레드가 실행 중이므로 종료되지 않습니다.
	}
}