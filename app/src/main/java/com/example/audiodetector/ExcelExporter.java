package com.example.audiodetector;

import android.content.Context;
import android.widget.Toast;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.List;

import java.util.Locale;
import java.util.Map;

public class ExcelExporter {


    public static void exportAllSegmentsToExcel(Context context,
                                                File outputFile,
                                                Map<String, List<Segment>> allFilesSegments
    ) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("All Audio Segments");

        // Header row
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("File Name");
        header.createCell(1).setCellValue("Label");
        header.createCell(2).setCellValue("Time");
        header.createCell(3).setCellValue("Confidence");
        header.createCell(4).setCellValue("Note");

        int rowIndex = 1;

        for (Map.Entry<String, List<Segment>> entry : allFilesSegments.entrySet()) {
            String fileName = entry.getKey();
            List<Segment> segments = entry.getValue();
            String preLabel ="";

            for (Segment seg : segments) {
                Row row = sheet.createRow(rowIndex++);
                if(!preLabel.equals(seg.label)){
                    row.createCell(0).setCellValue(fileName);
                    preLabel = seg.label;
                }
                row.createCell(1).setCellValue(seg.label);
                String startTimeFormatted = formatSecondsToHMS(seg.startTimeSec);
                String endTimeFormatted = formatSecondsToHMS(seg.endTimeSec);
                String timeRange = startTimeFormatted + " - " + endTimeFormatted;

               // String time = String.format("%.2f - %.2f", seg.startTimeSec, seg.endTimeSec);
                row.createCell(2).setCellValue(timeRange);

                row.createCell(3).setCellValue(seg.confidence);
                row.createCell(4).setCellValue("");
            }
        }

        rowIndex++;

        // Auto-size columns
//        for (int i = 0; i <= 4; i++) {
//            sheet.autoSizeColumn(i);
//        }

        FileOutputStream fos = new FileOutputStream(outputFile);
        workbook.write(fos);
        fos.close();
        workbook.close();
        Toast.makeText(context, "export has done", Toast.LENGTH_SHORT).show();
    }

    public static String formatSecondsToHMS(float seconds) {
        int totalSeconds = Math.round(seconds);

        int minutes = (totalSeconds % 3600) / 60;
        int secs = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }
}
