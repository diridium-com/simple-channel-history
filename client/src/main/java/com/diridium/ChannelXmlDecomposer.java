// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import com.mirth.connect.util.MirthXmlUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class ChannelXmlDecomposer {

    /**
     * Result of decomposing a channel XML, containing the component map and
     * a mapping from stable group keys to human-readable display names.
     */
    public static class DecomposeResult {
        private final Map<String, DecomposedComponent> components;
        private final Map<String, String> groupDisplayNames;

        DecomposeResult(Map<String, DecomposedComponent> components, Map<String, String> groupDisplayNames) {
            this.components = components;
            this.groupDisplayNames = groupDisplayNames;
        }

        public Map<String, DecomposedComponent> getComponents() {
            return components;
        }

        public Map<String, String> getGroupDisplayNames() {
            return groupDisplayNames;
        }
    }

    public static DecomposeResult decomposeWithNames(String channelXml) throws Exception {
        Map<String, String> groupDisplayNames = new LinkedHashMap<>();
        Map<String, DecomposedComponent> components = decomposeInternal(channelXml, groupDisplayNames);
        return new DecomposeResult(components, groupDisplayNames);
    }

    private static Map<String, DecomposedComponent> decomposeInternal(String channelXml,
            Map<String, String> groupDisplayNames) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        Document doc = db.parse(new InputSource(new StringReader(channelXml)));

        XPath xpath = XPathFactory.newInstance().newXPath();
        Map<String, DecomposedComponent> components = new LinkedHashMap<>();

        // Channel-level scripts
        extractChannelScript(doc, xpath, components, "preprocessingScript", "Preprocessing Script");
        extractChannelScript(doc, xpath, components, "postprocessingScript", "Postprocessing Script");
        extractChannelScript(doc, xpath, components, "deployScript", "Deploy Script");
        extractChannelScript(doc, xpath, components, "undeployScript", "Undeploy Script");

        // Source connector
        Node sourceConnector = (Node) xpath.evaluate("/channel/sourceConnector", doc, XPathConstants.NODE);
        if (sourceConnector != null) {
            String srcGroup = "Source Connector";
            extractConnectorScript(xpath, components, (Element) sourceConnector, srcGroup);
            extractPluginProperties(xpath, components, (Element) sourceConnector, srcGroup);
            extractStepsFromSubElement(xpath, components, (Element) sourceConnector, srcGroup,
                    "filter", "Filter");
            extractStepsFromSubElement(xpath, components, (Element) sourceConnector, srcGroup,
                    "transformer", "Transformer");

            // Serialize remaining source connector as Configuration
            String srcConfigXml = serializeNode(sourceConnector);
            String srcKey = srcGroup + "/Configuration";
            components.put(srcKey, new DecomposedComponent(srcKey, "Configuration", srcConfigXml,
                    srcGroup));
            sourceConnector.getParentNode().removeChild(sourceConnector);
        }

        // Destination connectors
        NodeList destConnectors = (NodeList) xpath.evaluate(
                "/channel/destinationConnectors/connector", doc, XPathConstants.NODESET);
        List<Element> destElements = new ArrayList<>();
        List<String> destGroupNames = new ArrayList<>();
        StringBuilder destOrderBuilder = new StringBuilder();
        for (int i = 0; i < destConnectors.getLength(); i++) {
            Element connector = (Element) destConnectors.item(i);
            String connName = getDirectChildText(connector, "name");
            String metaDataId = getDirectChildText(connector, "metaDataId");
            String groupName = "Destination [" + metaDataId + "]";
            String displayName = "Destination: " + connName + " [" + metaDataId + "]";
            if (groupDisplayNames != null) {
                groupDisplayNames.put(groupName, displayName);
            }
            destElements.add(connector);
            destGroupNames.add(groupName);

            if (!destOrderBuilder.isEmpty()) {
                destOrderBuilder.append("\n");
            }
            destOrderBuilder.append(i + 1).append(". ").append(connName).append(" [").append(metaDataId).append("]");

            extractConnectorScript(xpath, components, connector, groupName);
            extractPluginProperties(xpath, components, connector, groupName);
            extractStepsFromSubElement(xpath, components, connector, groupName,
                    "filter", "Filter");
            extractStepsFromSubElement(xpath, components, connector, groupName,
                    "transformer", "Transformer");
            extractStepsFromSubElement(xpath, components, connector, groupName,
                    "responseTransformer", "Response Transformer");
        }

        // Extract each destination connector configuration (after sub-elements removed)
        for (int i = 0; i < destElements.size(); i++) {
            Element connector = destElements.get(i);
            String groupName = destGroupNames.get(i);
            if (connector.getParentNode() != null) {
                String configXml = serializeNode(connector);
                String configKey = groupName + "/Configuration";
                components.put(configKey, new DecomposedComponent(configKey, "Configuration", configXml,
                        groupName));
                connector.getParentNode().removeChild(connector);
            }
        }

        // Remove the now-empty destinationConnectors wrapper element
        Node destConnectorsWrapper = (Node) xpath.evaluate("/channel/destinationConnectors", doc, XPathConstants.NODE);
        if (destConnectorsWrapper != null) {
            destConnectorsWrapper.getParentNode().removeChild(destConnectorsWrapper);
        }

        // Channel Properties = the remainder XML (without scripts or connectors)
        String remainderXml = serializeDocument(doc);
        String key = "Channel Properties";

        // Reorder so Channel Properties and Destination Order appear first in the tree
        Map<String, DecomposedComponent> ordered = new LinkedHashMap<>();
        ordered.put(key, new DecomposedComponent(key, "Channel Properties", remainderXml,
                key));
        if (!destOrderBuilder.isEmpty()) {
            String destOrderKey = "Destination Order";
            ordered.put(destOrderKey, new DecomposedComponent(destOrderKey, "Destination Order",
                    destOrderBuilder.toString(),
                    destOrderKey));
        }
        ordered.putAll(components);

        return ordered;
    }

    private static void extractChannelScript(Document doc, XPath xpath,
            Map<String, DecomposedComponent> components,
            String elementName, String displayName) throws Exception {
        Node node = (Node) xpath.evaluate("/channel/" + elementName, doc, XPathConstants.NODE);
        if (node != null) {
            String content = node.getTextContent();
            String group = "Channel Scripts";
            String compKey = group + "/" + displayName;
            components.put(compKey, new DecomposedComponent(compKey, displayName, content,
                    group));
            node.getParentNode().removeChild(node);
        }
    }

    private static void extractConnectorScript(XPath xpath,
            Map<String, DecomposedComponent> components,
            Element connector, String groupName) throws Exception {
        Node node = (Node) xpath.evaluate("properties/script", connector, XPathConstants.NODE);
        if (node != null) {
            String content = node.getTextContent();
            String compKey = groupName + "/Script";
            components.put(compKey, new DecomposedComponent(compKey, "Script", content,
                    groupName));
            node.getParentNode().removeChild(node);
        }
    }

    private static void extractPluginProperties(XPath xpath,
            Map<String, DecomposedComponent> components,
            Element connector, String groupName) throws Exception {
        Node pluginProps = (Node) xpath.evaluate("properties/pluginProperties", connector, XPathConstants.NODE);
        if (pluginProps == null) {
            return;
        }

        List<Element> plugins = new ArrayList<>();
        NodeList children = pluginProps.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                plugins.add((Element) children.item(i));
            }
        }

        for (Element plugin : plugins) {
            String pluginName = getStepTypeName(plugin.getTagName());
            String compKey = groupName + "/Plugin: " + pluginName;
            String content = serializeNode(plugin);
            components.put(compKey, new DecomposedComponent(compKey, "Plugin: " + pluginName, content,
                    groupName));
            pluginProps.removeChild(plugin);
        }
    }

    private static void extractStepsFromSubElement(XPath xpath,
            Map<String, DecomposedComponent> components,
            Element connector, String connectorGroup,
            String elementName, String displayName) throws Exception {
        Node node = (Node) xpath.evaluate(elementName, connector, XPathConstants.NODE);
        if (node == null) {
            return;
        }

        Element subElement = (Element) node;
        Node elementsNode = (Node) xpath.evaluate("elements", subElement, XPathConstants.NODE);
        if (elementsNode == null) {
            return;
        }

        // Collect step elements (direct children of <elements>)
        List<Element> steps = new ArrayList<>();
        NodeList children = elementsNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                steps.add((Element) children.item(i));
            }
        }

        if (steps.isEmpty()) {
            return;
        }

        String subGroupKey = connectorGroup + "/" + displayName;

        for (int i = 0; i < steps.size(); i++) {
            Element step = steps.get(i);

            // Read sequence number, fallback to index
            String seqText = getDirectChildText(step, "sequenceNumber");
            String seq = (seqText != null) ? seqText : String.valueOf(i);

            // Read step name, fallback to type name from tag
            String stepName = getDirectChildText(step, "name");
            if (stepName == null || stepName.isEmpty()) {
                stepName = getStepTypeName(step.getTagName());
            }

            String stepDisplayName = "Step " + seq + ": " + stepName;
            String stepKey = subGroupKey + "/Step " + seq;

            String stepContent = serializeNode(step);
            components.put(stepKey, new DecomposedComponent(stepKey, stepDisplayName, stepContent,
                    subGroupKey));

            // Remove the step from the DOM
            elementsNode.removeChild(step);
        }
    }

    static String getStepTypeName(String tagName) {
        int lastDot = tagName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < tagName.length() - 1) {
            return tagName.substring(lastDot + 1);
        }
        return tagName;
    }

    private static String getDirectChildText(Element parent, String childName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && child.getNodeName().equals(childName)) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private static String serializeNode(Node node) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return MirthXmlUtil.prettyPrint(writer.toString().trim()).trim();
    }

    private static String serializeDocument(Document doc) throws Exception {
        return serializeNode(doc);
    }
}
