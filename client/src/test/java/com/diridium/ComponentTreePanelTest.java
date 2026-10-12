// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ComponentTreePanelTest {

    @Test
    public void testAllUnchanged() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.UNCHANGED);
        changeTypes.put("b", ComponentTreePanel.ChangeType.UNCHANGED);

        assertEquals(ComponentTreePanel.ChangeType.UNCHANGED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b")));
    }

    @Test
    public void testAllLeftOnly() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.LEFT_ONLY);
        changeTypes.put("b", ComponentTreePanel.ChangeType.LEFT_ONLY);

        assertEquals(ComponentTreePanel.ChangeType.LEFT_ONLY,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b")));
    }

    @Test
    public void testAllRightOnly() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.RIGHT_ONLY);
        changeTypes.put("b", ComponentTreePanel.ChangeType.RIGHT_ONLY);

        assertEquals(ComponentTreePanel.ChangeType.RIGHT_ONLY,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b")));
    }

    @Test
    public void testAllModified() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.MODIFIED);

        assertEquals(ComponentTreePanel.ChangeType.MODIFIED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, List.of("a")));
    }

    @Test
    public void testMixedChangesRollUpToModified() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.UNCHANGED);
        changeTypes.put("b", ComponentTreePanel.ChangeType.RIGHT_ONLY);

        // Mix of unchanged and right_only → MODIFIED
        assertEquals(ComponentTreePanel.ChangeType.MODIFIED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b")));
    }

    @Test
    public void testLeftOnlyAndRightOnlyRollUpToModified() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.LEFT_ONLY);
        changeTypes.put("b", ComponentTreePanel.ChangeType.RIGHT_ONLY);

        assertEquals(ComponentTreePanel.ChangeType.MODIFIED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b")));
    }

    @Test
    public void testModifiedWithUnchangedRollsUpToModified() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("a", ComponentTreePanel.ChangeType.MODIFIED);
        changeTypes.put("b", ComponentTreePanel.ChangeType.UNCHANGED);
        changeTypes.put("c", ComponentTreePanel.ChangeType.UNCHANGED);

        assertEquals(ComponentTreePanel.ChangeType.MODIFIED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, Arrays.asList("a", "b", "c")));
    }

    @Test
    public void testMissingKeyTreatedAsUnchanged() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        // "a" not in the map — should default to UNCHANGED

        assertEquals(ComponentTreePanel.ChangeType.UNCHANGED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, List.of("a")));
    }

    @Test
    public void testEmptyKeyList() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();

        assertEquals(ComponentTreePanel.ChangeType.UNCHANGED,
                ComponentTreePanel.computeGroupChangeType(changeTypes, List.of()));
    }

    @Test
    public void testSingleLeftOnlyChild() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("removed", ComponentTreePanel.ChangeType.LEFT_ONLY);

        assertEquals(ComponentTreePanel.ChangeType.LEFT_ONLY,
                ComponentTreePanel.computeGroupChangeType(changeTypes, List.of("removed")));
    }

    @Test
    public void testSingleRightOnlyChild() {
        Map<String, ComponentTreePanel.ChangeType> changeTypes = new LinkedHashMap<>();
        changeTypes.put("added", ComponentTreePanel.ChangeType.RIGHT_ONLY);

        assertEquals(ComponentTreePanel.ChangeType.RIGHT_ONLY,
                ComponentTreePanel.computeGroupChangeType(changeTypes, List.of("added")));
    }

    @Test
    public void testStepReorderDetection() {
        // Two steps in a transformer, same content but swapped order.
        // sequenceNumber and operator change on reorder (positional) — detection must ignore them.
        String stepAatPos0 = "<Step><sequenceNumber>0</sequenceNumber><operator>NONE</operator><name>MapperStep</name><script>doA()</script></Step>";
        String stepBatPos1 = "<Step><sequenceNumber>1</sequenceNumber><operator>AND</operator><name>JSStep</name><script>doB()</script></Step>";
        String stepBatPos0 = "<Step><sequenceNumber>0</sequenceNumber><operator>NONE</operator><name>JSStep</name><script>doB()</script></Step>";
        String stepAatPos1 = "<Step><sequenceNumber>1</sequenceNumber><operator>AND</operator><name>MapperStep</name><script>doA()</script></Step>";

        Map<String, DecomposedComponent> left = new LinkedHashMap<>();
        left.put("Destination [1]/Transformer/Step 0",
                new DecomposedComponent("Destination [1]/Transformer/Step 0", "Step 0: MapperStep",
                        stepAatPos0, "Destination [1]/Transformer"));
        left.put("Destination [1]/Transformer/Step 1",
                new DecomposedComponent("Destination [1]/Transformer/Step 1", "Step 1: JSStep",
                        stepBatPos1, "Destination [1]/Transformer"));
        left.put("Destination [1]/Configuration",
                new DecomposedComponent("Destination [1]/Configuration", "Configuration",
                        "config", "Destination [1]"));

        Map<String, DecomposedComponent> right = new LinkedHashMap<>();
        right.put("Destination [1]/Transformer/Step 0",
                new DecomposedComponent("Destination [1]/Transformer/Step 0", "Step 0: JSStep",
                        stepBatPos0, "Destination [1]/Transformer"));
        right.put("Destination [1]/Transformer/Step 1",
                new DecomposedComponent("Destination [1]/Transformer/Step 1", "Step 1: MapperStep",
                        stepAatPos1, "Destination [1]/Transformer"));
        right.put("Destination [1]/Configuration",
                new DecomposedComponent("Destination [1]/Configuration", "Configuration",
                        "config", "Destination [1]"));

        assumeFalse("Skipping Swing test in headless environment", GraphicsEnvironment.isHeadless());
        ComponentTreePanel panel = new ComponentTreePanel(left, right, null, null);
        // Panel detects the reorder — changed count should reflect that steps are modified
        // (2 steps show as modified because content at each position differs)
        assertEquals(2, panel.getChangedCount());
    }

    @Test
    public void testNoFalseReorderWhenContentActuallyChanged() {
        // Two steps where content genuinely changed (not just reordered)
        Map<String, DecomposedComponent> left = new LinkedHashMap<>();
        left.put("Destination [1]/Transformer/Step 0",
                new DecomposedComponent("Destination [1]/Transformer/Step 0", "Step 0",
                        "content-A", "Destination [1]/Transformer"));
        left.put("Destination [1]/Configuration",
                new DecomposedComponent("Destination [1]/Configuration", "Configuration",
                        "config", "Destination [1]"));

        Map<String, DecomposedComponent> right = new LinkedHashMap<>();
        right.put("Destination [1]/Transformer/Step 0",
                new DecomposedComponent("Destination [1]/Transformer/Step 0", "Step 0",
                        "content-B", "Destination [1]/Transformer"));
        right.put("Destination [1]/Configuration",
                new DecomposedComponent("Destination [1]/Configuration", "Configuration",
                        "config", "Destination [1]"));

        assumeFalse("Skipping Swing test in headless environment", GraphicsEnvironment.isHeadless());
        ComponentTreePanel panel = new ComponentTreePanel(left, right, null, null);
        // Content genuinely changed — should show 1 changed, NOT be flagged as reorder
        assertEquals(1, panel.getChangedCount());
    }
}
