// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;

public class DecomposedDiffWindow extends JDialog {

    private static final String VIEW_DECOMPOSED = "decomposed";
    private static final String VIEW_RAW = "raw";

    private final Map<String, DecomposedComponent> leftComponents;
    private final Map<String, DecomposedComponent> rightComponents;
    private final Map<String, String> leftGroupDisplayNames;
    private final Map<String, String> rightGroupDisplayNames;
    private final String leftRawXml;
    private final String rightRawXml;
    private final boolean viewOnly;
    private JPanel diffContainer;
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private JButton toggleButton;
    private boolean showingDecomposed = true;

    private DecomposedDiffWindow(java.awt.Dialog parent, String title, String leftLabel, String rightLabel,
                                  Map<String, DecomposedComponent> leftComponents,
                                  Map<String, DecomposedComponent> rightComponents,
                                  Map<String, String> leftGroupDisplayNames,
                                  Map<String, String> rightGroupDisplayNames,
                                  String leftRawXml, String rightRawXml,
                                  boolean viewOnly) {
        super(DiffWindow.ownerOrAdministrator(parent), title, ModalityType.APPLICATION_MODAL);
        this.leftComponents = leftComponents;
        this.rightComponents = rightComponents;
        this.leftGroupDisplayNames = leftGroupDisplayNames;
        this.rightGroupDisplayNames = rightGroupDisplayNames;
        this.leftRawXml = leftRawXml;
        this.rightRawXml = rightRawXml;
        this.viewOnly = viewOnly;
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

        buildContent(leftLabel, rightLabel);
    }

    public static DecomposedDiffWindow create(java.awt.Dialog parent, String title,
            String leftLabel, String rightLabel,
            ChannelXmlDecomposer.DecomposeResult leftResult,
            ChannelXmlDecomposer.DecomposeResult rightResult,
            String leftRawXml, String rightRawXml) {
        return new DecomposedDiffWindow(parent, title, leftLabel, rightLabel,
                leftResult.getComponents(), rightResult.getComponents(),
                leftResult.getGroupDisplayNames(), rightResult.getGroupDisplayNames(),
                leftRawXml, rightRawXml, false);
    }

    public static DecomposedDiffWindow createViewOnly(java.awt.Dialog parent, String title,
            String label, ChannelXmlDecomposer.DecomposeResult result, String rawXml) {
        return new DecomposedDiffWindow(parent, title, label, null,
                result.getComponents(), result.getComponents(),
                result.getGroupDisplayNames(), result.getGroupDisplayNames(),
                rawXml, null, true);
    }

    private void buildContent(String leftLabel, String rightLabel) {
        // Revision labels — placed above the diff container so they align with left/right panes
        JPanel labelPanel;
        if (viewOnly) {
            labelPanel = new JPanel(new GridLayout(1, 1));
            JLabel lbl = new JLabel(leftLabel, JLabel.CENTER);
            lbl.setFont(new Font(lbl.getFont().getName(), Font.BOLD, 14));
            labelPanel.add(lbl);
        } else {
            labelPanel = new JPanel(new GridLayout(1, 2));
            JLabel lblLeft = new JLabel(leftLabel, JLabel.CENTER);
            Font labelFont = new Font(lblLeft.getFont().getName(), Font.BOLD, 14);
            lblLeft.setFont(labelFont);
            labelPanel.add(lblLeft);

            JLabel lblRight = new JLabel(rightLabel, JLabel.CENTER);
            lblRight.setFont(labelFont);
            labelPanel.add(lblRight);
        }

        // --- Decomposed view ---
        ComponentTreePanel treePanel = new ComponentTreePanel(leftComponents, rightComponents,
                leftGroupDisplayNames, rightGroupDisplayNames);
        treePanel.setPreferredSize(new Dimension(280, 0));
        treePanel.setMinimumSize(new Dimension(200, 0));

        JLabel summaryLabel = new JLabel(
                treePanel.getChangedCount() + " of " + treePanel.getTotalCount() + " components changed");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.ITALIC));
        summaryLabel.setHorizontalAlignment(JLabel.CENTER);

        JPanel treeWithSummary = new JPanel(new BorderLayout());
        treeWithSummary.add(treePanel, BorderLayout.CENTER);
        treeWithSummary.add(summaryLabel, BorderLayout.SOUTH);

        // Diff container with labels above it so they align with left/right panes
        diffContainer = new JPanel(new BorderLayout());
        diffContainer.setMinimumSize(new Dimension(0, 0));

        JPanel diffWithLabels = new JPanel(new BorderLayout());
        diffWithLabels.add(labelPanel, BorderLayout.NORTH);
        diffWithLabels.add(diffContainer, BorderLayout.CENTER);

        JSplitPane decomposedSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeWithSummary, diffWithLabels);
        decomposedSplit.setDividerLocation(280);
        decomposedSplit.setOneTouchExpandable(true);

        // --- Raw XML view (created lazily on first toggle) ---
        // Use a CardLayout to swap between decomposed and raw views
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(decomposedSplit, VIEW_DECOMPOSED);

        // Toggle button
        toggleButton = new JButton("Show Raw XML");
        toggleButton.addActionListener(e -> toggleView());

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(toggleButton, BorderLayout.NORTH);
        mainPanel.add(cardPanel, BorderLayout.CENTER);

        add(mainPanel);

        // Wire up selection listener
        treePanel.setComponentSelectionListener(this::showComponentDiff);
    }

    private void toggleView() {
        if (showingDecomposed) {
            // Lazily create the raw view on first toggle
            if (cardPanel.getComponentCount() < 2) {
                SimpleDiffPanel rawDiff = viewOnly
                        ? new SimpleDiffPanel(leftRawXml)
                        : new SimpleDiffPanel(leftRawXml, rightRawXml);
                cardPanel.add(rawDiff, VIEW_RAW);
            }
            cardLayout.show(cardPanel, VIEW_RAW);
            toggleButton.setText("Show Component View");
        } else {
            cardLayout.show(cardPanel, VIEW_DECOMPOSED);
            toggleButton.setText("Show Raw XML");
        }
        showingDecomposed = !showingDecomposed;
    }

    private void showComponentDiff(String key) {
        diffContainer.removeAll();

        if (key != null) {
            DecomposedComponent comp = leftComponents.get(key);
            String content = comp != null ? comp.getContent() : "";

            if (viewOnly) {
                diffContainer.add(new SimpleDiffPanel(content), BorderLayout.CENTER);
            } else {
                String rightContent = "";
                DecomposedComponent rightComp = rightComponents.get(key);
                if (rightComp != null) {
                    rightContent = rightComp.getContent();
                }
                diffContainer.add(new SimpleDiffPanel(content, rightContent), BorderLayout.CENTER);
            }
        }

        diffContainer.revalidate();
        diffContainer.repaint();
    }
}
