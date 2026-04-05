import java.sql.*;

public class SpecDump {
    public static void main(String[] args) {
        String url = "jdbc:postgresql://localhost:5432/obe";
        String user = "shubhsinghal";
        String pass = "postgres";
        System.out.println("Starting SpecDump...");
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            
            System.out.println("--- ALL SPECIALIZATIONS ---");
            ResultSet rs = stmt.executeQuery("SELECT id, name, program_id FROM specialization ORDER BY id");
            while (rs.next()) {
                System.out.println("Spec: ID=" + rs.getInt("id") + ", NAME='" + rs.getString("name") + "', PROG=" + rs.getInt("program_id"));
            }

            System.out.println("--- ALL BATCHES FOR 2023 ---");
            ResultSet rsBatch = stmt.executeQuery("SELECT id, start_year, end_year, program_id, specialization_id FROM batch WHERE start_year=2023");
            while (rsBatch.next()) {
                System.out.println("Batch: ID=" + rsBatch.getInt("id") + ", START=" + rsBatch.getInt("start_year") + ", END=" + rsBatch.getInt("end_year") + ", PROG=" + rsBatch.getInt("program_id") + ", SPEC=" + rsBatch.getInt("specialization_id"));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
