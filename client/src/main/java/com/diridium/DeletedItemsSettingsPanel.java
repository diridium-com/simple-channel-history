// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

import org.jdesktop.swingx.decorator.HighlighterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.client.core.ForbiddenException;
import com.mirth.connect.client.ui.AbstractSettingsPanel;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.components.MirthTable;
import com.mirth.connect.model.converters.ObjectXMLSerializer;

/**
 * Settings panel for viewing and managing deleted channel/code template snapshots.
 */
public class DeletedItemsSettingsPanel extends AbstractSettingsPanel {

    private static final Logger log = LoggerFactory.getLogger(DeletedItemsSettingsPanel.class);

    private static final String[] COLUMN_NAMES = {"Type", "Name", "Item ID", "Deleted By", "Date Deleted"};

    private static final String FILTER_ALL = "All";
    private static final String FILTER_CHANNELS = "Channels";
    private static final String FILTER_CODE_TEMPLATES = "Code Templates";

    private ChannelHistoryServletInterface servlet;
    private MirthTable table;
    private DeletedItemTableModel model;
    private List<DeletedItemInfo> allItems = Collections.emptyList();
    private JComboBox<String> filterCombo;
    private JButton btnViewXml;
    private JButton btnDiff;
    private JButton btnDownload;
    private JTextField searchField;
    private JButton btnPurge;
    private JLabel failureLabel;

    private static final Color FAILURE_COLOR = new Color(176, 0, 32);

    public DeletedItemsSettingsPanel(String tabName) {
        super(tabName);
        ObjectXMLSerializer.getInstance().allowTypes(
                Collections.emptyList(),
                Collections.singletonList(DeletedItemInfo.class.getPackage().getName() + ".**"),
                Collections.emptyList());
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        table = new MirthTable();
        table.setHighlighters(HighlighterFactory.createAlternateStriping(
                UIConstants.HIGHLIGHTER_COLOR, UIConstants.BACKGROUND_COLOR));
        table.setSortable(true);
        table.setRowSelectionAllowed(true);
        table.setColumnSelectionAllowed(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        model = new DeletedItemTableModel(Collections.emptyList());
        table.setModel(model);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRowCount() == 1) {
                    viewXml();
                }
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        buttonPanel.add(new JLabel("Show:"));
        filterCombo = new JComboBox<>(new String[]{FILTER_ALL, FILTER_CHANNELS, FILTER_CODE_TEMPLATES});
        filterCombo.addActionListener(e -> applyFilter());
        buttonPanel.add(filterCombo);

        buttonPanel.add(new JLabel("  Filter:"));
        searchField = new JTextField(15);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        buttonPanel.add(searchField);

        btnViewXml = new JButton("View XML");
        btnViewXml.setEnabled(false);
        btnViewXml.addActionListener(e -> viewXml());
        buttonPanel.add(btnViewXml);

        btnDiff = new JButton("Show Diff");
        btnDiff.setEnabled(false);
        btnDiff.addActionListener(e -> showDiff());
        buttonPanel.add(btnDiff);

        btnDownload = new JButton("Download XML");
        btnDownload.setEnabled(false);
        btnDownload.addActionListener(e -> downloadXml());
        buttonPanel.add(btnDownload);

        btnPurge = new JButton("Purge");
        btnPurge.setEnabled(false);
        btnPurge.addActionListener(e -> purge());
        buttonPanel.add(btnPurge);

        failureLabel = new JLabel();
        failureLabel.setForeground(FAILURE_COLOR);
        failureLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));
        failureLabel.setVisible(false);

        JPanel top = new JPanel(new BorderLayout());
        top.add(buttonPanel, BorderLayout.NORTH);
        top.add(failureLabel, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    private ChannelHistoryServletInterface getServlet() {
        if (servlet == null) {
            try {
                servlet = PlatformUI.MIRTH_FRAME.mirthClient.getServlet(ChannelHistoryServletInterface.class);
            } catch (Exception e) {
                log.error("Failed to get servlet", e);
            }
        }
        return servlet;
    }

    @Override
    public void doRefresh() {
        // A list that fails to load is said so on the panel, rather than shown as an empty list.
        List<String> failures = new ArrayList<>();
        new SwingWorker<List<DeletedItemInfo>, Void>() {
            @Override
            protected List<DeletedItemInfo> doInBackground() throws Exception {
                List<DeletedItemInfo> all = new ArrayList<>();
                ChannelHistoryServletInterface svc = getServlet();
                if (svc == null) {
                    failures.add("Deleted items could not be loaded: the plugin's server side did not answer.");
                    return all;
                }
                try {
                    List<DeletedItemInfo> channels = svc.getDeletedChannels();
                    for (DeletedItemInfo info : channels) {
                        info.setType(DeletedItemInfo.TYPE_CHANNEL);
                    }
                    all.addAll(channels);
                } catch (Exception e) {
                    log.error("Failed to load deleted channels", e);
                    failures.add("Deleted channels could not be loaded: " + describe(e));
                }
                try {
                    List<DeletedItemInfo> codeTemplates = svc.getDeletedCodeTemplates();
                    for (DeletedItemInfo info : codeTemplates) {
                        info.setType(DeletedItemInfo.TYPE_CODE_TEMPLATE);
                    }
                    all.addAll(codeTemplates);
                } catch (Exception e) {
                    log.error("Failed to load deleted code templates", e);
                    failures.add("Deleted code templates could not be loaded: " + describe(e));
                }
                // Sort newest-first
                all.sort((a, b) -> Long.compare(b.getDateDeleted(), a.getDateDeleted()));
                return all;
            }

            @Override
            protected void done() {
                try {
                    allItems = get();
                } catch (Exception e) {
                    log.error("Failed to refresh deleted items", e);
                    allItems = Collections.emptyList();
                    failures.add("Deleted items could not be loaded: " + describe(e));
                }
                showFailures(failures);
                applyFilter();
            }
        }.execute();
    }

    /** A short reason for a failed request: a refused permission in words, anything else as its message. */
    private static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ForbiddenException) {
                return "you are not authorized to view them.";
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void showFailures(List<String> failures) {
        failureLabel.setText(failures.isEmpty() ? "" : "<html>" + String.join("<br>", failures.stream()
                .map(f -> f.replace("&", "&amp;").replace("<", "&lt;")).toList()) + "</html>");
        failureLabel.setVisible(!failures.isEmpty());
    }

    private void applyFilter() {
        String typeFilter = (String) filterCombo.getSelectedItem();
        String search = searchField != null ? searchField.getText().trim().toLowerCase() : "";

        List<DeletedItemInfo> filtered = new ArrayList<>();
        for (DeletedItemInfo info : allItems) {
            if (FILTER_CHANNELS.equals(typeFilter) && !DeletedItemInfo.TYPE_CHANNEL.equals(info.getType())) continue;
            if (FILTER_CODE_TEMPLATES.equals(typeFilter) && !DeletedItemInfo.TYPE_CODE_TEMPLATE.equals(info.getType())) continue;
            if (!search.isEmpty() && !info.getName().toLowerCase().contains(search)) continue;
            filtered.add(info);
        }

        model = new DeletedItemTableModel(filtered);
        table.setModel(model);
        btnViewXml.setEnabled(false);
        btnDiff.setEnabled(false);
        btnDownload.setEnabled(false);
        btnPurge.setEnabled(false);
    }

    @Override
    public boolean doSave() {
        // Nothing to configure — this panel is read-only
        return true;
    }

    private void updateButtonStates() {
        int count = table.getSelectedRowCount();
        btnViewXml.setEnabled(count == 1);
        btnDownload.setEnabled(count == 1);
        btnPurge.setEnabled(count == 1);

        // Diff enabled when exactly 2 rows selected with the same type
        boolean canDiff = false;
        if (count == 2) {
            int[] rows = table.getSelectedRows();
            DeletedItemInfo a = model.getItemAt(table.convertRowIndexToModel(rows[0]));
            DeletedItemInfo b = model.getItemAt(table.convertRowIndexToModel(rows[1]));
            canDiff = a.getType().equals(b.getType());
        }
        btnDiff.setEnabled(canDiff);
    }

    private void showDiff() {
        int[] rows = table.getSelectedRows();
        if (rows.length != 2) return;

        ChannelHistoryServletInterface svc = getServlet();
        if (svc == null) return;

        DeletedItemInfo a = model.getItemAt(table.convertRowIndexToModel(rows[0]));
        DeletedItemInfo b = model.getItemAt(table.convertRowIndexToModel(rows[1]));

        // Assign older/newer by date
        DeletedItemInfo older = a.getDateDeleted() <= b.getDateDeleted() ? a : b;
        DeletedItemInfo newer = a.getDateDeleted() <= b.getDateDeleted() ? b : a;

        try {
            String left;
            String right;
            boolean isChannel = DeletedItemInfo.TYPE_CHANNEL.equals(newer.getType());

            if (isChannel) {
                left = svc.getDeletedChannelContent(older.getId());
                right = svc.getDeletedChannelContent(newer.getId());
            } else {
                left = svc.getDeletedCodeTemplateContent(older.getId());
                right = svc.getDeletedCodeTemplateContent(newer.getId());
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String leftLabel = "Old - " + older.getName() + " (deleted " + sdf.format(new Date(older.getDateDeleted())) + ")";
            String rightLabel = "New - " + newer.getName() + " (deleted " + sdf.format(new Date(newer.getDateDeleted())) + ")";
            String title = "Deleted " + newer.getType() + " Diff - " + newer.getName();

            if (isChannel) {
                try {
                    ChannelXmlDecomposer.DecomposeResult leftResult = ChannelXmlDecomposer.decomposeWithNames(left);
                    ChannelXmlDecomposer.DecomposeResult rightResult = ChannelXmlDecomposer.decomposeWithNames(right);
                    DecomposedDiffWindow dw = DecomposedDiffWindow.create(null, title,
                            leftLabel, rightLabel, leftResult, rightResult, left, right);
                    DiffWindow.showOverAdministrator(dw);
                } catch (Exception decompositionEx) {
                    log.warn("Channel decomposition failed, falling back to raw diff: {}", decompositionEx.getMessage(), decompositionEx);
                    DiffWindow dw = DiffWindow.create(title, leftLabel, rightLabel, left, right);
                    DiffWindow.showOverAdministrator(dw);
                }
            } else {
                DiffWindow dw = DiffWindow.create(title, leftLabel, rightLabel, left, right);
                DiffWindow.showOverAdministrator(dw);
            }
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    private void viewXml() {
        int row = table.getSelectedRow();
        if (row < 0) return;

        ChannelHistoryServletInterface svc = getServlet();
        if (svc == null) return;

        DeletedItemInfo info = model.getItemAt(table.convertRowIndexToModel(row));
        try {
            String xml;
            boolean isChannel = DeletedItemInfo.TYPE_CHANNEL.equals(info.getType());

            if (isChannel) {
                xml = svc.getDeletedChannelContent(info.getId());
            } else {
                xml = svc.getDeletedCodeTemplateContent(info.getId());
            }

            if (xml == null) {
                PlatformUI.MIRTH_FRAME.alertError(this, "No content found for this deleted item.");
                return;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
            String label = info.getName() + " (deleted " + sdf.format(new Date(info.getDateDeleted())) + ")";
            String title = "Deleted " + info.getType() + " - " + info.getName();

            if (isChannel) {
                try {
                    ChannelXmlDecomposer.DecomposeResult result = ChannelXmlDecomposer.decomposeWithNames(xml);
                    DecomposedDiffWindow dw = DecomposedDiffWindow.createViewOnly(null, title, label, result, xml);
                    DiffWindow.showOverAdministrator(dw);
                } catch (Exception decompositionEx) {
                    log.warn("Channel decomposition failed, falling back to raw view: {}", decompositionEx.getMessage(), decompositionEx);
                    DiffWindow dw = DiffWindow.createViewOnly(title, label, xml);
                    DiffWindow.showOverAdministrator(dw);
                }
            } else {
                DiffWindow dw = DiffWindow.createViewOnly(title, label, xml);
                DiffWindow.showOverAdministrator(dw);
            }
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    private void downloadXml() {
        int row = table.getSelectedRow();
        if (row < 0) return;

        ChannelHistoryServletInterface svc = getServlet();
        if (svc == null) return;

        DeletedItemInfo info = model.getItemAt(table.convertRowIndexToModel(row));
        try {
            String xml;
            if (DeletedItemInfo.TYPE_CHANNEL.equals(info.getType())) {
                xml = svc.getDeletedChannelContent(info.getId());
            } else {
                xml = svc.getDeletedCodeTemplateContent(info.getId());
            }

            if (xml == null) {
                PlatformUI.MIRTH_FRAME.alertError(this, "No content found for this deleted item.");
                return;
            }

            // The engine's own export: it asks before overwriting, adds .xml, and encrypts the file when the
            // server's encryption.export setting is on, as an exported channel or code template is.
            PlatformUI.MIRTH_FRAME.exportFile(xml, info.getName(), "XML", "Deleted " + info.getType().toLowerCase() + " snapshot");
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    private void purge() {
        int row = table.getSelectedRow();
        if (row < 0) return;

        DeletedItemInfo info = model.getItemAt(table.convertRowIndexToModel(row));
        int confirm = JOptionPane.showConfirmDialog(this,
                "Permanently delete the snapshot for \"" + info.getName() + "\"?\nThis action cannot be undone.",
                "Confirm Purge",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        ChannelHistoryServletInterface svc = getServlet();
        if (svc == null) return;

        try {
            boolean purged = DeletedItemInfo.TYPE_CHANNEL.equals(info.getType())
                    ? svc.purgeDeletedChannel(info.getId())
                    : svc.purgeDeletedCodeTemplate(info.getId());
            if (!purged) {
                // Nothing was purged: someone else purged it first.
                PlatformUI.MIRTH_FRAME.alertInformation(this, "The snapshot for \"" + info.getName() + "\" was already purged.");
            }
            doRefresh();
        } catch (Exception e) {
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    // ========== Table Model ==========

    static class DeletedItemTableModel extends AbstractTableModel {

        private static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        private final List<DeletedItemInfo> items;

        DeletedItemTableModel(List<DeletedItemInfo> items) {
            this.items = items;
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMN_NAMES[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DeletedItemInfo info = items.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> info.getType();
                case 1 -> info.getName();
                case 2 -> info.getItemId();
                case 3 -> info.getDeletedBy();
                case 4 -> df.format(new Date(info.getDateDeleted()));
                default -> throw new IllegalArgumentException("unknown column " + columnIndex);
            };
        }

        DeletedItemInfo getItemAt(int row) {
            return items.get(row);
        }
    }
}
