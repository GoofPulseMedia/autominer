package net.autominer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MiningLogger {
    private BufferedWriter writer;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    public MiningLogger() {
        try {
            File logDir = new File("logs");
            if (!logDir.exists()) {
                logDir.mkdir();
            }
            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            File logFile = new File(logDir, "autominer_log_" + timestamp + ".txt");
            this.writer = new BufferedWriter(new FileWriter(logFile));
            log("Log file created for new mining operation.");
        } catch (IOException e) {
            System.err.println("[AutoMiner] Failed to create log file!");
            e.printStackTrace();
            this.writer = null;
        }
    }

    public void log(String message) {
        if (writer != null) {
            try {
                String timestamp = timeFormat.format(new Date());
                writer.write("[" + timestamp + "] " + message);
                writer.newLine();
                writer.flush(); // Ensure messages are written immediately
            } catch (IOException e) {
                // Can't do much here, maybe the disk is full
            }
        }
    }

    public void close() {
        if (writer != null) {
            try {
                log("Closing log file.");
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
