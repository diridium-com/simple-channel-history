// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JTextPane;

import org.junit.Test;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;

public class SimpleDiffPanelTest {

    @Test
    public void testToCharList() {
        List<String> chars = SimpleDiffPanel.toCharList("abc");
        assertEquals(3, chars.size());
        assertEquals("a", chars.get(0));
        assertEquals("b", chars.get(1));
        assertEquals("c", chars.get(2));
    }

    @Test
    public void testToCharListEmpty() {
        List<String> chars = SimpleDiffPanel.toCharList("");
        assertTrue(chars.isEmpty());
    }

    @Test
    public void testComputeInlineHighlightsChangedChars() {
        // "hello world" -> "hello earth"
        String oldLine = "hello world";
        String newLine = "hello earth";
        Patch<String> charPatch = DiffUtils.diff(
                SimpleDiffPanel.toCharList(oldLine),
                SimpleDiffPanel.toCharList(newLine));

        boolean[] oldHighlights = SimpleDiffPanel.computeInlineHighlights(oldLine.length(), charPatch, true);
        boolean[] newHighlights = SimpleDiffPanel.computeInlineHighlights(newLine.length(), charPatch, false);

        // "hello " is unchanged (positions 0-5)
        for (int i = 0; i < 6; i++) {
            assertFalse("Old position " + i + " should not be highlighted", oldHighlights[i]);
            assertFalse("New position " + i + " should not be highlighted", newHighlights[i]);
        }

        // "world" / "earth" should be highlighted (positions 6-10)
        boolean hasOldHighlight = false;
        for (int i = 6; i < oldLine.length(); i++) {
            if (oldHighlights[i]) hasOldHighlight = true;
        }
        assertTrue("Old line should have highlights in changed region", hasOldHighlight);

        boolean hasNewHighlight = false;
        for (int i = 6; i < newLine.length(); i++) {
            if (newHighlights[i]) hasNewHighlight = true;
        }
        assertTrue("New line should have highlights in changed region", hasNewHighlight);
    }

    @Test
    public void testComputeInlineHighlightsIdenticalLines() {
        String line = "no changes here";
        Patch<String> charPatch = DiffUtils.diff(
                SimpleDiffPanel.toCharList(line),
                SimpleDiffPanel.toCharList(line));

        boolean[] highlights = SimpleDiffPanel.computeInlineHighlights(line.length(), charPatch, true);

        for (int i = 0; i < line.length(); i++) {
            assertFalse("Position " + i + " should not be highlighted", highlights[i]);
        }
    }

    @Test
    public void testComputeInlineHighlightsEntireLineChanged() {
        String oldLine = "aaa";
        String newLine = "bbb";
        Patch<String> charPatch = DiffUtils.diff(
                SimpleDiffPanel.toCharList(oldLine),
                SimpleDiffPanel.toCharList(newLine));

        boolean[] oldHighlights = SimpleDiffPanel.computeInlineHighlights(oldLine.length(), charPatch, true);
        boolean[] newHighlights = SimpleDiffPanel.computeInlineHighlights(newLine.length(), charPatch, false);

        // Every character should be highlighted on both sides
        for (int i = 0; i < oldLine.length(); i++) {
            assertTrue("Old position " + i + " should be highlighted", oldHighlights[i]);
        }
        for (int i = 0; i < newLine.length(); i++) {
            assertTrue("New position " + i + " should be highlighted", newHighlights[i]);
        }
    }

    @Test
    public void testComputeInlineHighlightsInsertion() {
        // "ab" -> "aXb" — "X" inserted at position 1
        String oldLine = "ab";
        String newLine = "aXb";
        Patch<String> charPatch = DiffUtils.diff(
                SimpleDiffPanel.toCharList(oldLine),
                SimpleDiffPanel.toCharList(newLine));

        boolean[] newHighlights = SimpleDiffPanel.computeInlineHighlights(newLine.length(), charPatch, false);

        // "a" unchanged, "X" highlighted, "b" unchanged
        assertFalse(newHighlights[0]);
        assertTrue(newHighlights[1]);
        assertFalse(newHighlights[2]);
    }

    @Test
    public void testComputeInlineHighlightsDeletion() {
        // "aXb" -> "ab" — "X" deleted from position 1
        String oldLine = "aXb";
        String newLine = "ab";
        Patch<String> charPatch = DiffUtils.diff(
                SimpleDiffPanel.toCharList(oldLine),
                SimpleDiffPanel.toCharList(newLine));

        boolean[] oldHighlights = SimpleDiffPanel.computeInlineHighlights(oldLine.length(), charPatch, true);

        // "a" unchanged, "X" highlighted, "b" unchanged
        assertFalse(oldHighlights[0]);
        assertTrue(oldHighlights[1]);
        assertFalse(oldHighlights[2]);
    }

    @Test
    public void testEmptyContentProducesEmptyList() {
        // Empty content is no lines, not [""], so an entirely new or removed component diffs as
        // INSERT/DELETE (green/red) rather than CHANGE (blue/yellow).
        assertTrue(SimpleDiffPanel.splitLines("").isEmpty());
        assertEquals(List.of("line1", "line2"), SimpleDiffPanel.splitLines("line1\nline2"));
        assertEquals(List.of("line1", ""), SimpleDiffPanel.splitLines("line1\n"));
    }

    @Test
    public void testDeletedAndInsertedLinesArePaddedToAlign() {
        // b is deleted and d inserted: each side gets a blank padding line where the other has one,
        // so the two panes stay line-for-line aligned.
        SimpleDiffPanel panel = new SimpleDiffPanel("a\nb\nc", "a\nc\nd");
        List<JTextPane> panes = new ArrayList<>();
        collectTextPanes(panel, panes);
        assertEquals(2, panes.size());
        List<String> left = lines(panes.get(0));
        List<String> right = lines(panes.get(1));
        assertEquals("left " + left + ", right " + right, left.size(), right.size());
        assertEquals(List.of("a", "b", "c", ""), left);
        assertEquals(List.of("a", "", "c", "d"), right);
    }

    /** The pane's lines, trimmed: a padding line is a single space, so it keeps its height. */
    private static List<String> lines(JTextPane pane) {
        String text = pane.getText();
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            lines.add(line.trim());
        }
        return lines;
    }

    private static void collectTextPanes(Container container, List<JTextPane> found) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextPane) {
                found.add((JTextPane) child);
            } else if (child instanceof Container) {
                collectTextPanes((Container) child, found);
            }
        }
    }
}
