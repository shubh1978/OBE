import java.sql.*;

public class DBDump {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/obe";
        try (Connection conn = DriverManager.getConnection(url, "shubhsinghal", "postgres");
             Statement stmt = conn.createStatement()) {
            
            System.out.println("--- CHECKING COs FOR COURSES 718-725 ---");
            ResultSet rs = stmt.executeQuery("SELECT course_id, count(*) FROM co WHERE course_id BETWEEN 718 AND 725 GROUP BY course_id");
            while (rs.next()) {
                System.out.println("Course ID=" + rs.getInt(1) + ", CO Count=" + rs.getInt(2));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
