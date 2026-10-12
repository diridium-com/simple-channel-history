// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.client.ui.Frame;
import com.mirth.connect.client.ui.PlatformUI;

/**
 * Dialog for viewing channel version history.
 *
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class ChannelHistoryDialog extends JDialog {
    private static final Logger log = LoggerFactory.getLogger(ChannelHistoryDialog.class);

    private final String channelId;
    private final String channelName;
    private RevisionInfoTable tblRevisions;
    private ChannelHistoryServletInterface servlet;
    private JButton btnShowDiff;
    private JButton btnRevert;

    public ChannelHistoryDialog(JFrame parent, String channelId, String channelName) {
        super(parent, "Version History - " + channelName, true);
        this.channelId = channelId;
        this.channelName = channelName;
        AllowedTypes.register();

        initComponents();
        loadHistory();

        setSize(700, 400);
        setLocationRelativeTo(parent);
        // The title-bar close disposes the dialog too, as Close and Escape do.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Escape key closes dialog
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // Table
        tblRevisions = new RevisionInfoTable();
        tblRevisions.setRowSelectionAllowed(true);
        tblRevisions.setColumnSelectionAllowed(false);
        tblRevisions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        tblRevisions.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });

        // Setup popup menu
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem menuPrune = new JMenuItem("Prune older revisions");
        menuPrune.addActionListener(e -> pruneOlderRevisions());
        popupMenu.add(menuPrune);

        tblRevisions.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tblRevisions.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        compareWithPrevious(row);
                    }
                }
            }

            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tblRevisions.rowAtPoint(e.getPoint());
                    if (row >= 0 && !tblRevisions.isRowSelected(row)) {
                        tblRevisions.setRowSelectionInterval(row, row);
                    }
                    // Enable prune if a single row is selected and there are older revisions to delete
                    int selectedRow = tblRevisions.getSelectedRow();
                    int totalRows = tblRevisions.getRowCount();
                    boolean hasOlderRevisions = selectedRow < totalRows - 1;
                    menuPrune.setEnabled(tblRevisions.getSelectedRowCount() == 1 && hasOlderRevisions);
                    popupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tblRevisions);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom panel with help and buttons
        JPanel bottomPanel = new JPanel(new BorderLayout());

        // Help icon with tooltip and click handler
        JLabel helpLabel = new JLabel(new ImageIcon(Frame.class.getResource("images/help.png")));
        String helpText = "<html>" +
                "<b>Double-click</b> a revision to compare with previous<br>" +
                "<b>Ctrl/Cmd-click</b> to select two revisions, then Show Diff<br>" +
                "<b>Right-click</b> for prune option" +
                "</html>";
        helpLabel.setToolTipText(helpText);
        helpLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                JOptionPane.showMessageDialog(ChannelHistoryDialog.this,
                        helpText,
                        "Help",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        JPanel helpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        helpPanel.add(helpLabel);
        bottomPanel.add(helpPanel, BorderLayout.WEST);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        btnShowDiff = new JButton("Show Diff");
        btnShowDiff.setEnabled(false);
        btnShowDiff.addActionListener(e -> showDiff());
        buttonPanel.add(btnShowDiff);

        btnRevert = new JButton("Revert to Selected");
        btnRevert.setEnabled(false);
        btnRevert.addActionListener(e -> revertToSelected());
        buttonPanel.add(btnRevert);

        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> dispose());
        buttonPanel.add(btnClose);

        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void updateButtonStates() {
        int selectedCount = tblRevisions.getSelectedRowCount();
        btnShowDiff.setEnabled(selectedCount == 2);
        // Can only revert if one row is selected and it's not the latest (row 0)
        btnRevert.setEnabled(selectedCount == 1 && tblRevisions.getSelectedRow() > 0);
    }

    private void loadHistory() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (servlet == null) {
                    servlet = PlatformUI.MIRTH_FRAME.mirthClient.getServlet(ChannelHistoryServletInterface.class);
                }

                List<RevisionInfo> revisions = servlet.getHistory(channelId);
                RevisionInfoTableModel model = new RevisionInfoTableModel(revisions);
                tblRevisions.setModel(model);
                updateButtonStates();
                toFront();
                requestFocus();
            } catch (Exception e) {
                PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
            }
        });
    }

    private void showDiff() {
        int[] rows = tblRevisions.getSelectedRows();
        if (rows.length != 2) {
            return;
        }

        RevisionInfoTableModel model = (RevisionInfoTableModel) tblRevisions.getModel();
        // rows[0] = newer (lower row index, table is newest-first), rows[1] = older
        // Assign left = older, right = newer to match standard diff convention
        RevisionInfo older = model.getRevisionAt(rows[1]);
        RevisionInfo newer = model.getRevisionAt(rows[0]);

        try {
            String left = servlet.getContent(channelId, older.getHash());
            String right = servlet.getContent(channelId, newer.getHash());

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String leftLabel = String.format("Old - %s (user: %s, time: %s)", older.getShortHash(), older.getCommitterName(), sdf.format(new Date(older.getTime())));
            String rightLabel = String.format("New - %s (user: %s, time: %s)", newer.getShortHash(), newer.getCommitterName(), sdf.format(new Date(newer.getTime())));

            try {
                ChannelXmlDecomposer.DecomposeResult leftResult = ChannelXmlDecomposer.decomposeWithNames(left);
                ChannelXmlDecomposer.DecomposeResult rightResult = ChannelXmlDecomposer.decomposeWithNames(right);
                DecomposedDiffWindow dw = DecomposedDiffWindow.create(this, "Channel Diff - " + channelName,
                        leftLabel, rightLabel, leftResult, rightResult, left, right);
                DiffWindow.showOverAdministrator(dw);
            } catch (Exception decompositionEx) {
                // Fallback to original monolithic DiffWindow — log so failures aren't invisible
                log.warn("Channel decomposition failed, falling back to raw diff: {}", decompositionEx.getMessage(), decompositionEx);
                DiffWindow dw = DiffWindow.create(this, "Channel Diff - " + channelName, leftLabel, rightLabel, left, right);
                DiffWindow.showOverAdministrator(dw);
            }
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    private void compareWithPrevious(int row) {
        int totalRows = tblRevisions.getRowCount();
        if (row >= totalRows - 1) {
            JOptionPane.showMessageDialog(this,
                    "No previous revision to compare to.",
                    "Compare",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Select the clicked row and the next row (previous revision)
        tblRevisions.setRowSelectionInterval(row, row);
        tblRevisions.addRowSelectionInterval(row + 1, row + 1);
        showDiff();

        // Restore selection to just the originally clicked row
        tblRevisions.setRowSelectionInterval(row, row);
    }

    private void revertToSelected() {
        int row = tblRevisions.getSelectedRow();
        if (row <= 0) {
            return;
        }

        RevisionInfoTableModel model = (RevisionInfoTableModel) tblRevisions.getModel();
        RevisionInfo targetRevision = model.getRevisionAt(row);

        int confirm = JOptionPane.showConfirmDialog(this,
                "Revert to revision " + targetRevision.getShortHash() + "?\n" +
                "This restores the channel as saved in that revision: connectors, scripts, filters, transformers,\n" +
                "and channel properties such as initial state and message storage.\n" +
                "These stay as they are now: enabled or disabled, message pruning, and tags.",
                "Confirm Revert",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            boolean reverted = servlet.revertChannel(channelId, targetRevision.getHash());
            if (reverted) {
                PlatformUI.MIRTH_FRAME.alertInformation(this, "Channel reverted successfully.");
                // Refresh the channel panel
                PlatformUI.MIRTH_FRAME.channelPanel.doRefreshChannels();
                loadHistory();
            }
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    private void pruneOlderRevisions() {
        int row = tblRevisions.getSelectedRow();
        if (row < 0) {
            return;
        }

        RevisionInfoTableModel model = (RevisionInfoTableModel) tblRevisions.getModel();
        RevisionInfo selectedRevision = model.getRevisionAt(row);
        int revisionsToDelete = model.getRowCount() - row - 1;

        if (revisionsToDelete <= 0) {
            JOptionPane.showMessageDialog(this, "No older revisions to delete.", "Prune", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete " + revisionsToDelete + " revision(s) older than " + selectedRevision.getShortHash() + "?\n" +
                "This action cannot be undone.",
                "Confirm Prune",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            int deleted = servlet.pruneChannelHistory(channelId, selectedRevision.getHash());
            PlatformUI.MIRTH_FRAME.alertInformation(this, "Deleted " + deleted + " older revision(s).");
            loadHistory();
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }
}
