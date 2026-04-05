import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;

public class ExcelHeaderDebugger {
    public static void main(String[] args) throws Exception {
        String[] filesToCheck = {
                "/Users/shubhsinghal/IdeaProjects/ObeApplication/SOET/ENCS201  JAVA PROGRAMMING(MID TERM).xlsx",
                "/Users/shubhsinghal/IdeaProjects/ObeApplication/SOET/ENCS205  DATA STRUCTURES (MID TERM GROUP 1 AND GROUP 2).xlsx",
                "/Users/shubhsinghal/IdeaProjects/ObeApplication/SOET/ETME309A MANUFACTURING TECHNOLOGY (end term).xlsx",
                "/Users/shubhsinghal/IdeaProjects/ObeApplication/SOET/SEC043 TECHNOLOGY IN EXPERIENCE DESIGN (mid term).xlsx"
        };

        for (String filePath : filesToCheck) {
            File file = new File(filePath);
            if (!file.exists()) {
                System.out.println("\n❌ File not found: " + filePath);
                continue;
            }
            
            try (FileInputStream fis = new FileInputStream(file);
                 Workbook workbook = new XSSFWorkbook(fis)) {
                
                Sheet sheet = workbook.getSheetAt(0);
                Row headerRow = sheet.getRow(0);
                
                System.out.println("\n📄 FILE: " + new File(filePath).getName());
                System.out.println("   Total columns: " + headerRow.getLastCellNum());
                System.out.println("\n   Header columns:");
                
                boolean foundQuestionColumns = false;
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    String header = cell == null ? "" : cell.getStringCellValue().trim();
                    
                    if (!header.isEmpty()) {
                        boolean isQuestion = header.matches("^Q\\s*\\d.*") || 
                                           header.toUpperCase().contains("QUESTION") ||
                                           header.matches("^Q[\\s\\-_]?\\d+.*");
                        
                        if (isQuestion) {
                            foundQuestionColumns = true;
                            System.out.println("   [" + c + "] ✓ Q-COL: " + header);
                        } else if (c >= 20 && c <= 30) {  // Show columns near question range
                            System.out.println("   [" + c + "]        : " + header);
                        }
                    }
                }
                
                if (!foundQuestionColumns) {
                    System.out.println("   ❌ NO QUESTION COLUMNS FOUND!");
                    System.out.println("\n   All non-empty headers:");
                    for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                        Cell cell = headerRow.getCell(c);
                        String header = cell == null ? "" : cell.getStringCellValue().trim();
                        if (!header.isEmpty()) {
                            System.out.println("   [" + c + "] " + header);
                        }
                    }
                }
                
            } catch (Exception e) {
                System.out.println("\n❌ Error reading file: " + filePath);
                e.printStackTrace();
            }
        }
    }
}

