package io.pzstorm.launcher.ui;

import io.pzstorm.launcher.LauncherConfig;
import io.pzstorm.launcher.Log;
import io.pzstorm.launcher.LogReport;
import java.awt.Component;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The privacy prompt + background upload shared by the "Send Logs" toolbar button and any popup
 * that also wants to invite the user to send diagnostics.
 */
public final class SendLogsDialog {

    private static final int MIN_DESCRIPTION_LENGTH = 11;

    private SendLogsDialog() {}

    public static void open(Component parent, LauncherConfig config) {
        JTextArea description = new JTextArea(5, 40);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        JLabel counter = new JLabel();
        JButton send = new JButton("Send");
        JButton cancel = new JButton("Cancel");
        Runnable refresh =
                () -> {
                    int missing = MIN_DESCRIPTION_LENGTH - description.getText().trim().length();
                    send.setEnabled(missing <= 0);
                    counter.setText(
                            missing > 0
                                    ? missing
                                            + " more character"
                                            + (missing == 1 ? "" : "s")
                                            + " required"
                                    : " ");
                };
        description
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                refresh.run();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                refresh.run();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                refresh.run();
                            }
                        });
        refresh.run();
        JOptionPane pane =
                new JOptionPane(
                        new Object[] {
                            "<html><b>Privacy Notice</b><br><br>"
                                    + "You are about to send data to a private Discord channel"
                                    + " readable only by (the"
                                    + " developer).<br>"
                                    + "Passwords are never included, your operating-system account"
                                    + " name is removed from file paths and logs before sending,"
                                    + " and reports are deleted after 7 days.<br><br>"
                                    + "Data sent will include:<br>"
                                    + "&nbsp;&nbsp;• Information about this PC (operating system,"
                                    + " CPU, RAM, Java version)<br>"
                                    + "&nbsp;&nbsp;• Launcher settings and launcher logs<br>"
                                    + "&nbsp;&nbsp;• Logs from Project Zomboid and Storm"
                                    + " (console.txt and the Logs folder)<br>"
                                    + "&nbsp;&nbsp;• JVM crash dumps (hs_err files) from the"
                                    + " game folder<br>"
                                    + "&nbsp;&nbsp;• The description you type below<br><br>"
                                    + "Full details: Privacy Policy section 4.4 (the Privacy"
                                    + " button).<br><br>"
                                    + "<b>Describe the problem (required).</b> Be as"
                                    + " descriptive as possible: what you were doing, what"
                                    + " went wrong, and roughly when. Short answers won't be"
                                    + " looked at.</html>",
                            new JScrollPane(description),
                            counter
                        },
                        JOptionPane.PLAIN_MESSAGE,
                        JOptionPane.DEFAULT_OPTION,
                        null,
                        new Object[] {send, cancel},
                        send);
        send.addActionListener(e -> pane.setValue(send));
        cancel.addActionListener(e -> pane.setValue(cancel));
        JDialog dialog = pane.createDialog(parent, "Send logs");
        dialog.setVisible(true);
        dialog.dispose();
        if (pane.getValue() != send) {
            return;
        }
        String text = description.getText().trim();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                String logId = LogReport.send(config, text);
                                Log.info(
                                        "Logs sent — mention report id "
                                                + logId
                                                + " when asking for help.");
                            } catch (Exception e) {
                                Log.error("Sending logs failed", e);
                            }
                        },
                        "storm-log-report");
        worker.setDaemon(true);
        worker.start();
    }
}
