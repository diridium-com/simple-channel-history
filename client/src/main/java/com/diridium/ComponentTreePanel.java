// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

public class ComponentTreePanel extends JPanel {

    public interface ComponentSelectionListener {
        void componentSelected(String key);
    }

    enum ChangeType {
        UNCHANGED,
        MODIFIED,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    private static final Color COLOR_MODIFIED = new Color(200, 130, 0);
    private static final Color COLOR_LEFT_ONLY = new Color(200, 50, 50);
    private static final Color COLOR_RIGHT_ONLY = new Color(50, 140, 50);

    private static final int ICON_SIZE = 7;
    private static final Icon ICON_MODIFIED = createCircleIcon(COLOR_MODIFIED);
    private static final Icon ICON_LEFT_ONLY = createCircleIcon(COLOR_LEFT_ONLY);
    private static final Icon ICON_RIGHT_ONLY = createCircleIcon(COLOR_RIGHT_ONLY);
    private static final Icon ICON_UNCHANGED = createCircleIcon(Color.GRAY);

    private final Map<String, DecomposedComponent> leftComponents;
    private final Map<String, DecomposedComponent> rightComponents;
    private final Map<String, String> groupDisplayNames;
    private final Map<String, ChangeType> changeTypes;
    private final Set<String> reorderedSubGroups;
    private final Set<String> allKeys;
    private final JTree tree;
    private final JCheckBox showChangedOnlyCheckBox;
    private final JCheckBox showLabelsCheckBox;
    private ComponentSelectionListener listener;

    public ComponentTreePanel(Map<String, DecomposedComponent> leftComponents,
                              Map<String, DecomposedComponent> rightComponents,
                              Map<String, String> leftGroupDisplayNames,
                              Map<String, String> rightGroupDisplayNames) {
        this.leftComponents = leftComponents;
        this.rightComponents = rightComponents;
        // Merge display names: right (newer) takes precedence
        this.groupDisplayNames = new LinkedHashMap<>();
        if (leftGroupDisplayNames != null) {
            this.groupDisplayNames.putAll(leftGroupDisplayNames);
        }
        if (rightGroupDisplayNames != null) {
            this.groupDisplayNames.putAll(rightGroupDisplayNames);
        }
        this.changeTypes = computeChangeTypes();
        this.reorderedSubGroups = computeReorderedSubGroups();
        this.allKeys = computeAllKeys();

        setLayout(new BorderLayout());

        tree = new JTree();
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new ChangedNodeRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || listener == null) {
                return;
            }
            if (node.getUserObject() instanceof ComponentNodeData data) {
                listener.componentSelected(data.key);
            } else {
                listener.componentSelected(null);
            }
        });

        add(new JScrollPane(tree), BorderLayout.CENTER);

        showChangedOnlyCheckBox = new JCheckBox("Show Changed Only", false);
        showChangedOnlyCheckBox.addActionListener(e -> rebuildTree());

        showLabelsCheckBox = new JCheckBox("Show Labels", true);
        showLabelsCheckBox.addActionListener(e -> tree.repaint());

        JPanel checkBoxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        checkBoxPanel.add(showChangedOnlyCheckBox);
        checkBoxPanel.add(showLabelsCheckBox);
        add(checkBoxPanel, BorderLayout.SOUTH);

        rebuildTree();
    }

    public void setComponentSelectionListener(ComponentSelectionListener listener) {
        this.listener = listener;

        // Replay current selection since it may have fired before the listener was wired
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node != null && node.getUserObject() instanceof ComponentNodeData data && listener != null) {
            listener.componentSelected(data.key);
        }
    }

    public int getChangedCount() {
        return (int) changeTypes.values().stream().filter(t -> t != ChangeType.UNCHANGED).count();
    }

    public int getTotalCount() {
        return allKeys.size();
    }

    private Map<String, ChangeType> computeChangeTypes() {
        Map<String, ChangeType> types = new LinkedHashMap<>();
        Set<String> all = computeAllKeys();
        for (String key : all) {
            DecomposedComponent left = leftComponents.get(key);
            DecomposedComponent right = rightComponents.get(key);
            if (left == null) {
                types.put(key, ChangeType.RIGHT_ONLY);
            } else if (right == null) {
                types.put(key, ChangeType.LEFT_ONLY);
            } else if (!Objects.equals(left.getContent(), right.getContent())) {
                types.put(key, ChangeType.MODIFIED);
            } else {
                types.put(key, ChangeType.UNCHANGED);
            }
        }
        return types;
    }

    private Set<String> computeAllKeys() {
        Set<String> all = new LinkedHashSet<>(leftComponents.keySet());
        all.addAll(rightComponents.keySet());
        return all;
    }

    private static final java.util.regex.Pattern POSITIONAL_FIELDS_PATTERN =
            java.util.regex.Pattern.compile(
                    "<sequenceNumber>\\d+</sequenceNumber>|<operator>[^<]*</operator>");

    /**
     * Detect sub-groups where all steps exist in both versions with the same content
     * but in a different order (pure reorder). Strips sequenceNumber from content
     * before comparing since it's purely positional and always changes on reorder.
     */
    private Set<String> computeReorderedSubGroups() {
        Set<String> result = new LinkedHashSet<>();

        // Find all sub-group parent keys (keys containing "/" that are used as parentGroup)
        Map<String, List<String>> leftSubGroups = new LinkedHashMap<>();
        Map<String, List<String>> rightSubGroups = new LinkedHashMap<>();

        for (DecomposedComponent comp : leftComponents.values()) {
            String pg = comp.getParentGroup();
            if (pg.contains("/")) {
                leftSubGroups.computeIfAbsent(pg, k -> new ArrayList<>())
                        .add(stripPositionalFields(comp.getContent()));
            }
        }
        for (DecomposedComponent comp : rightComponents.values()) {
            String pg = comp.getParentGroup();
            if (pg.contains("/")) {
                rightSubGroups.computeIfAbsent(pg, k -> new ArrayList<>())
                        .add(stripPositionalFields(comp.getContent()));
            }
        }

        for (String subGroup : leftSubGroups.keySet()) {
            List<String> leftContents = leftSubGroups.get(subGroup);
            List<String> rightContents = rightSubGroups.get(subGroup);
            if (rightContents == null || leftContents.size() != rightContents.size()) {
                continue;
            }
            if (leftContents.equals(rightContents)) {
                continue;
            }
            List<String> leftSorted = new ArrayList<>(leftContents);
            List<String> rightSorted = new ArrayList<>(rightContents);
            Collections.sort(leftSorted);
            Collections.sort(rightSorted);
            if (leftSorted.equals(rightSorted)) {
                result.add(subGroup);
            }
        }
        return result;
    }

    private static String stripPositionalFields(String content) {
        String stripped = POSITIONAL_FIELDS_PATTERN.matcher(content).replaceAll("");
        return stripped.replaceAll("\\s+", " ").trim();
    }

    private ChangeType computeGroupChangeType(List<String> keys) {
        return computeGroupChangeType(changeTypes, keys);
    }

    static ChangeType computeGroupChangeType(Map<String, ChangeType> changeTypes, List<String> keys) {
        boolean hasLeftOnly = false;
        boolean hasRightOnly = false;
        boolean hasModified = false;
        boolean hasUnchanged = false;

        for (String key : keys) {
            switch (changeTypes.getOrDefault(key, ChangeType.UNCHANGED)) {
                case LEFT_ONLY -> hasLeftOnly = true;
                case RIGHT_ONLY -> hasRightOnly = true;
                case MODIFIED -> hasModified = true;
                case UNCHANGED -> hasUnchanged = true;
            }
        }

        // All children are the same type -> group inherits that type
        if (hasLeftOnly && !hasRightOnly && !hasModified && !hasUnchanged) {
            return ChangeType.LEFT_ONLY;
        }
        if (hasRightOnly && !hasLeftOnly && !hasModified && !hasUnchanged) {
            return ChangeType.RIGHT_ONLY;
        }
        if (hasModified || hasLeftOnly || hasRightOnly) {
            return ChangeType.MODIFIED;
        }
        return ChangeType.UNCHANGED;
    }

    private void rebuildTree() {
        boolean changedOnly = showChangedOnlyCheckBox.isSelected();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Components");

        // Group keys by parentGroup, preserving insertion order
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String key : allKeys) {
            DecomposedComponent comp = leftComponents.containsKey(key) ? leftComponents.get(key) : rightComponents.get(key);
            String group = comp.getParentGroup();
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(key);
        }

        // Identify top-level groups vs sub-groups.
        // A group is a sub-group if its name starts with another group's name + "/"
        Set<String> allGroupNames = groups.keySet();
        Map<String, List<String>> topToSubGroups = new LinkedHashMap<>();
        Set<String> subGroupSet = new LinkedHashSet<>();

        for (String groupName : allGroupNames) {
            for (String otherGroup : allGroupNames) {
                if (!otherGroup.equals(groupName) && groupName.startsWith(otherGroup + "/")) {
                    subGroupSet.add(groupName);
                    topToSubGroups.computeIfAbsent(otherGroup, k -> new ArrayList<>()).add(groupName);
                }
            }
        }

        for (String groupName : allGroupNames) {
            // Skip sub-groups; they are handled under their parent
            if (subGroupSet.contains(groupName)) {
                continue;
            }

            // Collect all component keys for this top-level group including sub-group children
            List<String> allKeysForGroup = new ArrayList<>(groups.getOrDefault(groupName, List.of()));
            List<String> subGroups = topToSubGroups.getOrDefault(groupName, List.of());
            for (String subGroup : subGroups) {
                allKeysForGroup.addAll(groups.getOrDefault(subGroup, List.of()));
            }

            ChangeType groupChangeType = computeGroupChangeType(allKeysForGroup);

            if (changedOnly && groupChangeType == ChangeType.UNCHANGED) {
                continue;
            }

            String groupDisplay = groupDisplayNames.getOrDefault(groupName, groupName);
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(
                    new GroupNodeData(groupDisplay, groupChangeType));

            // Add direct children (components whose parentGroup is this top-level group)
            List<String> directKeys = groups.getOrDefault(groupName, List.of());
            for (String key : directKeys) {
                ChangeType ct = changeTypes.getOrDefault(key, ChangeType.UNCHANGED);
                if (changedOnly && ct == ChangeType.UNCHANGED) {
                    continue;
                }
                DecomposedComponent comp = leftComponents.containsKey(key) ? leftComponents.get(key) : rightComponents.get(key);
                DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(
                        new ComponentNodeData(key, comp.getDisplayName(), ct));
                groupNode.add(leaf);
            }

            // Add sub-group nodes
            for (String subGroup : subGroups) {
                List<String> subKeys = groups.getOrDefault(subGroup, List.of());
                ChangeType subGroupChangeType = computeGroupChangeType(subKeys);

                if (changedOnly && subGroupChangeType == ChangeType.UNCHANGED) {
                    continue;
                }

                // Display name is the suffix after the parent group name + "/"
                String subDisplayName = subGroup.substring(groupName.length() + 1);
                if (reorderedSubGroups.contains(subGroup)) {
                    subDisplayName += " (steps reordered)";
                }
                DefaultMutableTreeNode subGroupNode = new DefaultMutableTreeNode(
                        new GroupNodeData(subDisplayName, subGroupChangeType));

                for (String key : subKeys) {
                    ChangeType ct = changeTypes.getOrDefault(key, ChangeType.UNCHANGED);
                    if (changedOnly && ct == ChangeType.UNCHANGED) {
                        continue;
                    }
                    DecomposedComponent comp = leftComponents.containsKey(key) ? leftComponents.get(key) : rightComponents.get(key);
                    DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(
                            new ComponentNodeData(key, comp.getDisplayName(), ct));
                    subGroupNode.add(leaf);
                }

                if (subGroupNode.getChildCount() > 0) {
                    groupNode.add(subGroupNode);
                }
            }

            if (groupNode.getChildCount() > 0) {
                root.add(groupNode);
            }
        }

        tree.setModel(new DefaultTreeModel(root));

        // Expand all group nodes and sub-group nodes
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode groupNode = (DefaultMutableTreeNode) root.getChildAt(i);
            tree.expandPath(new TreePath(groupNode.getPath()));
            for (int j = 0; j < groupNode.getChildCount(); j++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) groupNode.getChildAt(j);
                if (child.getUserObject() instanceof GroupNodeData) {
                    tree.expandPath(new TreePath(child.getPath()));
                }
            }
        }

        // Auto-select first changed component
        selectFirstChanged(root);
    }

    private void selectFirstChanged(DefaultMutableTreeNode root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode groupNode = (DefaultMutableTreeNode) root.getChildAt(i);
            for (int j = 0; j < groupNode.getChildCount(); j++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) groupNode.getChildAt(j);
                if (child.getUserObject() instanceof ComponentNodeData data) {
                    if (data.changeType != ChangeType.UNCHANGED) {
                        tree.setSelectionPath(new TreePath(child.getPath()));
                        return;
                    }
                } else if (child.getUserObject() instanceof GroupNodeData) {
                    // Sub-group: search its children
                    for (int k = 0; k < child.getChildCount(); k++) {
                        DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) child.getChildAt(k);
                        if (leaf.getUserObject() instanceof ComponentNodeData data) {
                            if (data.changeType != ChangeType.UNCHANGED) {
                                tree.setSelectionPath(new TreePath(leaf.getPath()));
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    static class ComponentNodeData {
        final String key;
        final String displayName;
        final ChangeType changeType;

        ComponentNodeData(String key, String displayName, ChangeType changeType) {
            this.key = key;
            this.displayName = displayName;
            this.changeType = changeType;
        }

        @Override
        public String toString() {
            return displayName;
        }

        String toStringWithLabel() {
            return displayName + changeTypeLabel(changeType);
        }
    }

    static class GroupNodeData {
        final String groupName;
        final ChangeType changeType;

        GroupNodeData(String groupName, ChangeType changeType) {
            this.groupName = groupName;
            this.changeType = changeType;
        }

        @Override
        public String toString() {
            return groupName;
        }

        String toStringWithLabel() {
            return groupName + changeTypeLabel(changeType);
        }
    }

    private static String changeTypeLabel(ChangeType ct) {
        return switch (ct) {
            case LEFT_ONLY -> " (removed)";
            case RIGHT_ONLY -> " (added)";
            case MODIFIED -> " (changed)";
            default -> "";
        };
    }

    private class ChangedNodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            boolean showLabels = showLabelsCheckBox.isSelected();

            if (value instanceof DefaultMutableTreeNode treeNode) {
                Object userObj = treeNode.getUserObject();
                if (userObj instanceof ComponentNodeData data) {
                    if (showLabels) {
                        setText(data.toStringWithLabel());
                    }
                    setIcon(iconForChangeType(data.changeType));
                    Color color = colorForChangeType(data.changeType);
                    if (color != null) {
                        setForeground(color);
                        setFont(getFont().deriveFont(Font.BOLD));
                    }
                } else if (userObj instanceof GroupNodeData data) {
                    if (showLabels) {
                        setText(data.toStringWithLabel());
                    }
                    setIcon(iconForChangeType(data.changeType));
                    Color groupColor = colorForChangeType(data.changeType);
                    if (groupColor != null) {
                        setForeground(groupColor);
                        setFont(getFont().deriveFont(Font.BOLD));
                    }
                }
            }
            return this;
        }

        private Color colorForChangeType(ChangeType ct) {
            return switch (ct) {
                case MODIFIED -> COLOR_MODIFIED;
                case LEFT_ONLY -> COLOR_LEFT_ONLY;
                case RIGHT_ONLY -> COLOR_RIGHT_ONLY;
                default -> null;
            };
        }
    }

    private static Icon iconForChangeType(ChangeType ct) {
        return switch (ct) {
            case MODIFIED -> ICON_MODIFIED;
            case LEFT_ONLY -> ICON_LEFT_ONLY;
            case RIGHT_ONLY -> ICON_RIGHT_ONLY;
            default -> ICON_UNCHANGED;
        };
    }

    private static Icon createCircleIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x, y, ICON_SIZE, ICON_SIZE);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return ICON_SIZE; }

            @Override
            public int getIconHeight() { return ICON_SIZE; }
        };
    }
}
