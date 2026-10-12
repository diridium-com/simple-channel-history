// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/**
 * A simple side-by-side diff panel using java-diff-utils.
 *
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class SimpleDiffPanel extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(SimpleDiffPanel.class);

    private static final Color COLOR_ADDED = new Color(200, 255, 200);      // Light green
    private static final Color COLOR_DELETED = new Color(255, 200, 200);    // Light red
    private static final Color COLOR_CHANGED_OLD = COLOR_DELETED;           // Changed old side = light red
    private static final Color COLOR_CHANGED_NEW = COLOR_ADDED;             // Changed new side = light green
    private static final Color COLOR_HIGHLIGHT_OLD = new Color(255, 150, 150); // Darker red for changed words
    private static final Color COLOR_HIGHLIGHT_NEW = new Color(130, 220, 130); // Darker green for changed words
    private static final Color COLOR_PADDING = new Color(220, 220, 220);        // Gray for alignment gaps
    private static final Color COLOR_LINE_NUMBER_BG = new Color(240, 240, 240); // Light gray

    private DiffTextPane leftPane;
    private DiffTextPane rightPane;
    private JTextArea leftLineNumbers;
    private JTextArea rightLineNumbers;
    private JScrollPane leftScrollPane;
    private JScrollPane rightScrollPane;
    private boolean syncingScroll = false;

    private StringBuilder leftLineNumBuilder = new StringBuilder();
    private StringBuilder rightLineNumBuilder = new StringBuilder();

    /**
     * View-only constructor — single scroll pane with line numbers, no diff.
     */
    public SimpleDiffPanel(String content) {
        setLayout(new BorderLayout());

        leftPane = createTextPane();
        leftLineNumbers = createLineNumberArea();

        Dimension smallSize = new Dimension(0, 0);
        leftScrollPane = new JScrollPane(leftPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        leftScrollPane.setRowHeaderView(leftLineNumbers);
        leftScrollPane.getViewport().setBackground(Color.WHITE);
        leftScrollPane.setMinimumSize(smallSize);
        leftScrollPane.setPreferredSize(smallSize);

        add(leftScrollPane, BorderLayout.CENTER);
        displayContent(content);
    }

    public SimpleDiffPanel(String leftContent, String rightContent) {
        setLayout(new BorderLayout());

        // Create text panes
        leftPane = createTextPane();
        rightPane = createTextPane();

        // Create line number components
        leftLineNumbers = createLineNumberArea();
        rightLineNumbers = createLineNumberArea();

        // Create scroll panes with line numbers as row headers.
        // Use fixed preferred/minimum sizes so content doesn't drive layout.
        Dimension smallSize = new Dimension(0, 0);

        leftScrollPane = new JScrollPane(leftPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        leftScrollPane.setRowHeaderView(leftLineNumbers);
        leftScrollPane.getViewport().setBackground(Color.WHITE);
        leftScrollPane.setMinimumSize(smallSize);
        leftScrollPane.setPreferredSize(smallSize);

        rightScrollPane = new JScrollPane(rightPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rightScrollPane.setRowHeaderView(rightLineNumbers);
        rightScrollPane.getViewport().setBackground(Color.WHITE);
        rightScrollPane.setMinimumSize(smallSize);
        rightScrollPane.setPreferredSize(smallSize);

        // Synchronize scrolling
        setupScrollSync();

        // Create split pane — resizeWeight keeps it 50/50 on resize
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightScrollPane);
        splitPane.setResizeWeight(0.5);

        add(splitPane, BorderLayout.CENTER);

        // Compute and display diff
        displayDiff(leftContent, rightContent);
    }

    private DiffTextPane createTextPane() {
        DiffTextPane pane = new DiffTextPane();
        pane.setEditable(false);
        pane.setFont(new Font("Monospaced", Font.PLAIN, 12));
        pane.setOpaque(false); // Let DiffTextPane.paintComponent handle background painting
        return pane;
    }

    private static class DiffTextPane extends JTextPane {
        private final List<int[]> lineOffsets = new ArrayList<>();
        private final List<Color> lineColors = new ArrayList<>();

        void addLineBackground(int startOffset, int endOffset, Color color) {
            lineOffsets.add(new int[]{startOffset, endOffset});
            lineColors.add(color);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            Container parent = getParent();
            if (parent instanceof javax.swing.JViewport) {
                width = Math.max(width, parent.getWidth());
            }
            super.setBounds(x, y, width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Fill white background (pane is non-opaque so super won't do this)
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());

            // Paint full-width line backgrounds before text rendering
            for (int i = 0; i < lineOffsets.size(); i++) {
                Color color = lineColors.get(i);
                if (color == null) {
                    continue;
                }
                int[] offsets = lineOffsets.get(i);
                try {
                    Rectangle2D r = modelToView2D(offsets[0]);
                    if (r != null) {
                        g.setColor(color);
                        g.fillRect(0, (int) r.getY(), getWidth(), (int) Math.ceil(r.getHeight()));
                    }
                } catch (BadLocationException e) {
                    // skip
                }
            }
            super.paintComponent(g);
        }
    }

    private JTextArea createLineNumberArea() {
        JTextArea lineNumbers = new JTextArea();
        lineNumbers.setEditable(false);
        lineNumbers.setFont(new Font("Monospaced", Font.PLAIN, 12));
        lineNumbers.setBackground(COLOR_LINE_NUMBER_BG);
        lineNumbers.setForeground(Color.GRAY);
        return lineNumbers;
    }

    private void setupScrollSync() {
        // Sync vertical scrolling
        leftScrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    rightScrollPane.getVerticalScrollBar().setValue(e.getValue());
                    syncingScroll = false;
                }
            }
        });

        rightScrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    leftScrollPane.getVerticalScrollBar().setValue(e.getValue());
                    syncingScroll = false;
                }
            }
        });

        // Sync horizontal scrolling
        leftScrollPane.getHorizontalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    rightScrollPane.getHorizontalScrollBar().setValue(e.getValue());
                    syncingScroll = false;
                }
            }
        });

        rightScrollPane.getHorizontalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (!syncingScroll) {
                    syncingScroll = true;
                    leftScrollPane.getHorizontalScrollBar().setValue(e.getValue());
                    syncingScroll = false;
                }
            }
        });
    }

    private void displayDiff(String leftContent, String rightContent) {
        List<String> leftLines = leftContent.isEmpty() ? List.of() : Arrays.asList(leftContent.split("\n", -1));
        List<String> rightLines = rightContent.isEmpty() ? List.of() : Arrays.asList(rightContent.split("\n", -1));

        Patch<String> patch = DiffUtils.diff(leftLines, rightLines);

        StyledDocument leftDoc = leftPane.getStyledDocument();
        StyledDocument rightDoc = rightPane.getStyledDocument();

        // Normal style (no background) — used for unchanged text and as base for changed lines
        Style normalStyle = leftDoc.addStyle("normal", null);
        rightDoc.addStyle("normal", null);

        // Character-level highlight styles for inline word diffs (darker colors)
        Style highlightOldStyle = leftDoc.addStyle("highlightOld", null);
        StyleConstants.setBackground(highlightOldStyle, COLOR_HIGHLIGHT_OLD);

        Style highlightNewStyle = rightDoc.addStyle("highlightNew", null);
        StyleConstants.setBackground(highlightNewStyle, COLOR_HIGHLIGHT_NEW);

        // Base styles for changed lines — light background on characters
        Style changedOldCharStyle = leftDoc.addStyle("changedOldChar", null);
        StyleConstants.setBackground(changedOldCharStyle, COLOR_CHANGED_OLD);

        Style changedNewCharStyle = rightDoc.addStyle("changedNewChar", null);
        StyleConstants.setBackground(changedNewCharStyle, COLOR_CHANGED_NEW);

        try {
            // Track current position in each file
            int leftLine = 0;
            int rightLine = 0;

            for (AbstractDelta<String> delta : patch.getDeltas()) {
                int sourceStart = delta.getSource().getPosition();
                int targetStart = delta.getTarget().getPosition();

                // Add unchanged lines before this delta
                while (leftLine < sourceStart) {
                    appendLine(leftPane, leftLines.get(leftLine), normalStyle, null, leftLineNumBuilder, leftLine + 1);
                    leftLine++;
                }
                while (rightLine < targetStart) {
                    appendLine(rightPane, rightLines.get(rightLine), normalStyle, null, rightLineNumBuilder, rightLine + 1);
                    rightLine++;
                }

                // Handle the delta based on type
                switch (delta.getType()) {
                    case DELETE:
                        // Lines deleted from left (not in right)
                        for (String line : delta.getSource().getLines()) {
                            appendLine(leftPane, line, normalStyle, COLOR_DELETED, leftLineNumBuilder, leftLine + 1);
                            leftLine++;
                        }
                        // Add gray padding lines on right to keep alignment
                        for (int i = 0; i < delta.getSource().getLines().size(); i++) {
                            appendLine(rightPane, "", normalStyle, COLOR_PADDING, rightLineNumBuilder, -1);
                        }
                        break;

                    case INSERT:
                        // Gray padding lines on left to keep alignment
                        for (int i = 0; i < delta.getTarget().getLines().size(); i++) {
                            appendLine(leftPane, "", normalStyle, COLOR_PADDING, leftLineNumBuilder, -1);
                        }
                        // Lines added to right
                        for (String line : delta.getTarget().getLines()) {
                            appendLine(rightPane, line, normalStyle, COLOR_ADDED, rightLineNumBuilder, rightLine + 1);
                            rightLine++;
                        }
                        break;

                    case CHANGE:
                        // Lines changed - show old on left, new on right with word-level highlights
                        List<String> oldLines = delta.getSource().getLines();
                        List<String> newLines = delta.getTarget().getLines();
                        int maxLines = Math.max(oldLines.size(), newLines.size());

                        for (int i = 0; i < maxLines; i++) {
                            if (i < oldLines.size() && i < newLines.size()) {
                                // Both sides have a line — do inline character-level diff
                                String oldLine = oldLines.get(i);
                                String newLine = newLines.get(i);
                                Patch<String> charPatch = DiffUtils.diff(toCharList(oldLine), toCharList(newLine));

                                boolean[] oldHighlights = computeInlineHighlights(oldLine.length(), charPatch, true);
                                boolean[] newHighlights = computeInlineHighlights(newLine.length(), charPatch, false);

                                appendLineWithHighlights(leftPane, oldLine, changedOldCharStyle, highlightOldStyle, COLOR_CHANGED_OLD, oldHighlights, leftLineNumBuilder, leftLine + 1);
                                leftLine++;
                                appendLineWithHighlights(rightPane, newLine, changedNewCharStyle, highlightNewStyle, COLOR_CHANGED_NEW, newHighlights, rightLineNumBuilder, rightLine + 1);
                                rightLine++;
                            } else if (i < oldLines.size()) {
                                appendLine(leftPane, oldLines.get(i), normalStyle, COLOR_CHANGED_OLD, leftLineNumBuilder, leftLine + 1);
                                leftLine++;
                                appendLine(rightPane, "", normalStyle, COLOR_PADDING, rightLineNumBuilder, -1);
                            } else {
                                appendLine(leftPane, "", normalStyle, COLOR_PADDING, leftLineNumBuilder, -1);
                                appendLine(rightPane, newLines.get(i), normalStyle, COLOR_CHANGED_NEW, rightLineNumBuilder, rightLine + 1);
                                rightLine++;
                            }
                        }
                        break;

                    default:
                        break;
                }
            }

            // Add remaining unchanged lines
            while (leftLine < leftLines.size()) {
                appendLine(leftPane, leftLines.get(leftLine), normalStyle, null, leftLineNumBuilder, leftLine + 1);
                leftLine++;
            }
            while (rightLine < rightLines.size()) {
                appendLine(rightPane, rightLines.get(rightLine), normalStyle, null, rightLineNumBuilder, rightLine + 1);
                rightLine++;
            }

        } catch (BadLocationException e) {
            log.error("Error rendering diff", e);
        }

        // Set line numbers text
        leftLineNumbers.setText(leftLineNumBuilder.toString());
        rightLineNumbers.setText(rightLineNumBuilder.toString());

        // Calculate width needed for line numbers
        int maxLineNum = Math.max(leftLines.size(), rightLines.size());
        int digits = String.valueOf(maxLineNum).length();
        FontMetrics fm = leftLineNumbers.getFontMetrics(leftLineNumbers.getFont());
        int width = fm.charWidth('0') * (Math.max(digits, 4) + 2); // +2 for padding, min 4 to match %4d format

        leftLineNumbers.setPreferredSize(new Dimension(width, leftLineNumbers.getPreferredSize().height));
        rightLineNumbers.setPreferredSize(new Dimension(width, rightLineNumbers.getPreferredSize().height));

        // Scroll to top
        leftPane.setCaretPosition(0);
        rightPane.setCaretPosition(0);
    }

    private void displayContent(String content) {
        List<String> lines = content.isEmpty() ? List.of() : Arrays.asList(content.split("\n", -1));

        StyledDocument doc = leftPane.getStyledDocument();
        Style normalStyle = doc.addStyle("normal", null);

        try {
            for (int i = 0; i < lines.size(); i++) {
                appendLine(leftPane, lines.get(i), normalStyle, null, leftLineNumBuilder, i + 1);
            }
        } catch (BadLocationException e) {
            log.error("Error rendering diff", e);
        }

        leftLineNumbers.setText(leftLineNumBuilder.toString());

        int digits = String.valueOf(lines.size()).length();
        FontMetrics fm = leftLineNumbers.getFontMetrics(leftLineNumbers.getFont());
        int width = fm.charWidth('0') * (Math.max(digits, 4) + 2);
        leftLineNumbers.setPreferredSize(new Dimension(width, leftLineNumbers.getPreferredSize().height));

        leftPane.setCaretPosition(0);
    }

    private void appendLine(DiffTextPane pane, String line, Style style, Color bgColor,
            StringBuilder lineNumBuilder, int lineNum) throws BadLocationException {
        StyledDocument doc = pane.getStyledDocument();
        int length = doc.getLength();
        if (length > 0) {
            doc.insertString(length, "\n", style);
            lineNumBuilder.append("\n");
        }
        int lineStart = doc.getLength();
        doc.insertString(lineStart, line.isEmpty() ? " " : line, style);
        pane.addLineBackground(lineStart, doc.getLength(), bgColor);

        if (lineNum > 0) {
            lineNumBuilder.append(String.format("%4d ", lineNum));
        } else {
            lineNumBuilder.append("     ");
        }
    }

    private void appendLineWithHighlights(DiffTextPane pane, String line, Style baseStyle, Style highlightStyle,
            Color bgColor, boolean[] highlights, StringBuilder lineNumBuilder, int lineNum) throws BadLocationException {
        StyledDocument doc = pane.getStyledDocument();
        int length = doc.getLength();
        if (length > 0) {
            doc.insertString(length, "\n", baseStyle);
            lineNumBuilder.append("\n");
        }

        int lineStart = doc.getLength();

        // Insert the line in runs, switching between base and highlight styles for character-level diffs
        int i = 0;
        while (i < line.length()) {
            boolean hl = (i < highlights.length) && highlights[i];
            int runStart = i;
            while (i < line.length()) {
                boolean nextHl = (i < highlights.length) && highlights[i];
                if (nextHl != hl) break;
                i++;
            }
            doc.insertString(doc.getLength(), line.substring(runStart, i), hl ? highlightStyle : baseStyle);
        }

        pane.addLineBackground(lineStart, doc.getLength(), bgColor);

        if (lineNum > 0) {
            lineNumBuilder.append(String.format("%4d ", lineNum));
        } else {
            lineNumBuilder.append("     ");
        }
    }

    static List<String> toCharList(String s) {
        List<String> chars = new ArrayList<>(s.length());
        for (char c : s.toCharArray()) {
            chars.add(String.valueOf(c));
        }
        return chars;
    }

    static boolean[] computeInlineHighlights(int lineLength, Patch<String> charPatch, boolean isSource) {
        boolean[] highlights = new boolean[lineLength];
        for (AbstractDelta<String> delta : charPatch.getDeltas()) {
            int pos;
            int size;
            if (isSource) {
                pos = delta.getSource().getPosition();
                size = delta.getSource().size();
            } else {
                pos = delta.getTarget().getPosition();
                size = delta.getTarget().size();
            }
            for (int i = pos; i < pos + size && i < lineLength; i++) {
                highlights[i] = true;
            }
        }
        return highlights;
    }
}
