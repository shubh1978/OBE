package org.example;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.util.Arrays;

public class TestParseExcel {
    public static void main(String[] args) throws Exception {
        String[] files = {
            "SOET/ENCS201  JAVA PROGRAMMING(MID TERM).xlsx",
            "SOET/ENCS205  DATA STRUCTURES (MID TERM GROUP 1 AND GROUP 2).xlsx",
            "SOET/ETME309A MANUFACTURING TECHNOLOGY (end term).xlsx",
            "SOET/SEC043 TECHNOLOGY IN EXPERIENCE DESIGN (mid term).xlsx"
        };
        for (String file : files) {
            System.out.println("--- " + file + " ---");
            try (Workbook wb = new XSSFWorkbook(new FileInputStream(file))) {
                System.out.println("  Sheets in workbook: " + wb.getNumberOfSheets());
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    System.out.println("    " + i + ": " + wb.getSheetName(i));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
