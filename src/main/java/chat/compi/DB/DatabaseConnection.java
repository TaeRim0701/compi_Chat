
package chat.compi.DB;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection {

    private static final String URL = "jdbc:mysql://localhost:3306/chat_db?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASSWORD = "anjgkffkrh@@123";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("MySQL JDBC Driver registered successfully.");
        } catch (ClassNotFoundException e) {
            System.err.println("Error: MySQL JDBC Driver not found. Make sure mysql-connector-java JAR is in your classpath.");
            e.printStackTrace();
        }
    }

    private DatabaseConnection() {

    }

    public static Connection getConnection() throws SQLException {
        try {
            Connection newConnection = DriverManager.getConnection(URL, USER, PASSWORD);
            return newConnection;
        } catch (SQLException e) {
            System.err.println("Database connection failed: " + e.getMessage());
            throw e;
        }
    }

    // 이 메서드는 이제 사용되지 않거나, 각 DAO 메서드에서 try-with-resources에 의해 관리됩니다.
    public static void closeConnection() {
    }
}