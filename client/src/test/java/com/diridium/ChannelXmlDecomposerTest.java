// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.Test;

public class ChannelXmlDecomposerTest {

    private String loadResource(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Resource not found: " + name, is);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testDecomposeVersion1() throws Exception {
        String xml = loadResource("channel-for-diffing-version1.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // Channel scripts
        assertNotNull(components.get("Channel Scripts/Preprocessing Script"));
        assertNotNull(components.get("Channel Scripts/Postprocessing Script"));
        assertNotNull(components.get("Channel Scripts/Deploy Script"));
        assertNotNull(components.get("Channel Scripts/Undeploy Script"));

        // Destination 1 has a JavaScript Writer with a script
        assertNotNull(components.get("Destination [1]/Script"));
        assertEquals("logger.info(\"destination one\");",
                components.get("Destination [1]/Script").getContent());

        // Destination 2 (HTTP Sender) has no script element
        assertNull(components.get("Destination [2]/Script"));

        // No source connector script (VmReceiverProperties has no <script>)
        assertNull(components.get("Source Connector/Script"));

        // Connector configurations
        assertNotNull(components.get("Source Connector/Configuration"));
        assertNotNull(components.get("Destination [1]/Configuration"));
        assertNotNull(components.get("Destination [2]/Configuration"));

        // All filter/transformer/responseTransformer have empty <elements/> in version 1,
        // so no step components are extracted — no Filter/Transformer/Response Transformer sub-groups
        assertNull(components.get("Source Connector/Filter"));
        assertNull(components.get("Source Connector/Transformer"));
        assertNull(components.get("Destination [1]/Filter"));
        assertNull(components.get("Destination [1]/Transformer"));
        assertNull(components.get("Destination [1]/Response Transformer"));
        assertNull(components.get("Destination [2]/Filter"));
        assertNull(components.get("Destination [2]/Transformer"));
        assertNull(components.get("Destination [2]/Response Transformer"));

        // Channel Properties remainder
        assertNotNull(components.get("Channel Properties"));

        // Destination Order
        DecomposedComponent destOrder = components.get("Destination Order");
        assertNotNull(destOrder);
        assertEquals("1. Destination 1 [1]\n2. Destination 2 [2]", destOrder.getContent());
    }

    @Test
    public void testDecomposeVersion2() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // Deploy script changed in version 2
        String deployScript = components.get("Channel Scripts/Deploy Script").getContent();
        assertTrue(deployScript.contains("added to just check how diffing works"));

        // Destination 1 still has JavaScript Writer script
        assertNotNull(components.get("Destination [1]/Script"));

        // Version 2 Destination 2 has a JavaScriptStep transformer — extracted as Step 0
        String stepKey = "Destination [2]/Transformer/Step 0";
        assertNotNull(components.get(stepKey));
        String stepContent = components.get(stepKey).getContent();
        assertTrue(stepContent.contains("JavaScriptStep"));
        assertTrue(stepContent.contains("dummy transformer"));

        // Channel Properties
        assertNotNull(components.get("Channel Properties"));
    }

    @Test
    public void testChangeDetectionBetweenVersions() throws Exception {
        String xml1 = loadResource("channel-for-diffing-version1.xml");
        String xml2 = loadResource("channel-for-diffing-version2.xml");

        Map<String, DecomposedComponent> comp1 = ChannelXmlDecomposer.decomposeWithNames(xml1).getComponents();
        Map<String, DecomposedComponent> comp2 = ChannelXmlDecomposer.decomposeWithNames(xml2).getComponents();

        // Deploy script should be different
        assertNotEquals(
                comp1.get("Channel Scripts/Deploy Script").getContent(),
                comp2.get("Channel Scripts/Deploy Script").getContent());

        // Preprocessing script should be the same
        assertEquals(
                comp1.get("Channel Scripts/Preprocessing Script").getContent(),
                comp2.get("Channel Scripts/Preprocessing Script").getContent());

        // Destination 2 transformer: version 1 has no steps, version 2 has Step 0 (RIGHT_ONLY)
        String stepKey = "Destination [2]/Transformer/Step 0";
        assertNull(comp1.get(stepKey));
        assertNotNull(comp2.get(stepKey));

        // Destination 2 config changed (HTTP Sender -> TCP Sender)
        assertNotEquals(
                comp1.get("Destination [2]/Configuration").getContent(),
                comp2.get("Destination [2]/Configuration").getContent());

        // Destination Order should be the same (same destinations in same order)
        assertEquals(
                comp1.get("Destination Order").getContent(),
                comp2.get("Destination Order").getContent());
    }

    @Test
    public void testComponentKeys() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // One of each kind of component, under the key the tree and the diff look it up by
        assertNotNull(components.get("Channel Scripts/Deploy Script"));
        assertNotNull(components.get("Source Connector/Configuration"));
        assertNotNull(components.get("Destination [1]/Configuration"));
        assertNotNull(components.get("Destination [1]/Script"));
        assertNotNull(components.get("Channel Properties"));
        assertNotNull(components.get("Destination [2]/Transformer/Step 0"));
    }

    @Test
    public void testParentGroups() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        assertEquals("Channel Scripts",
                components.get("Channel Scripts/Deploy Script").getParentGroup());
        assertEquals("Destination [1]",
                components.get("Destination [1]/Script").getParentGroup());

        // Step's parent group is the sub-group (connector/elementType)
        assertEquals("Destination [2]/Transformer",
                components.get("Destination [2]/Transformer/Step 0").getParentGroup());
    }

    @Test
    public void testChannelPropertiesDoesNotContainExtractedContent() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        String channelProps = components.get("Channel Properties").getContent();
        // Extracted scripts should not appear in the remainder
        assertFalse(channelProps.contains("<preprocessingScript>"));
        assertFalse(channelProps.contains("<postprocessingScript>"));
        assertFalse(channelProps.contains("<deployScript>"));
        assertFalse(channelProps.contains("<undeployScript>"));
        // Connectors should not appear in the remainder
        assertFalse(channelProps.contains("<sourceConnector"));
        assertFalse(channelProps.contains("destinationConnectors"));
    }

    @Test
    public void testConnectorConfigDoesNotContainExtractedSteps() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // Destination 2 config should not contain the JavaScriptStep (it was extracted)
        String dest2Config = components.get("Destination [2]/Configuration").getContent();
        assertFalse(dest2Config.contains("JavaScriptStep"));
        assertFalse(dest2Config.contains("dummy transformer"));

        // The transformer element itself (with data types) should remain in config
        assertTrue(dest2Config.contains("<transformer"));

        // Source config should still have filter/transformer shells (empty elements)
        String srcConfig = components.get("Source Connector/Configuration").getContent();
        assertTrue(srcConfig.contains("<filter"));
        assertTrue(srcConfig.contains("<transformer"));
    }

    @Test
    public void testConnectorConfigChangeDetection() throws Exception {
        String xml1 = loadResource("channel-for-diffing-version1.xml");
        String xml2 = loadResource("channel-for-diffing-version2.xml");

        Map<String, DecomposedComponent> comp1 = ChannelXmlDecomposer.decomposeWithNames(xml1).getComponents();
        Map<String, DecomposedComponent> comp2 = ChannelXmlDecomposer.decomposeWithNames(xml2).getComponents();

        // Destination 2 config changed between versions (HTTP Sender -> TCP Sender)
        assertNotEquals(
                comp1.get("Destination [2]/Configuration").getContent(),
                comp2.get("Destination [2]/Configuration").getContent());

        // Both versions should have the destination configuration
        assertNotNull(comp1.get("Destination [2]/Configuration"));
        assertNotNull(comp2.get("Destination [2]/Configuration"));
    }

    @Test
    public void testMalformedXmlThrowsException() {
        try {
            ChannelXmlDecomposer.decomposeWithNames("not valid xml <>").getComponents();
            fail("Should have thrown an exception for malformed XML");
        } catch (Exception e) {
            // Expected
        }
    }

    @Test
    public void testStepExtraction() throws Exception {
        String xml = loadResource("channel-for-diffing-version2.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // Destination 2 has one JavaScriptStep in its transformer
        String stepKey = "Destination [2]/Transformer/Step 0";
        DecomposedComponent step = components.get(stepKey);
        assertNotNull(step);

        // Display name includes sequence and type
        assertEquals("Step 0: JavaScriptStep", step.getDisplayName());

        // Parent group is the sub-group
        assertEquals("Destination [2]/Transformer", step.getParentGroup());

        // Content is the serialized step XML
        assertTrue(step.getContent().contains("<sequenceNumber>0</sequenceNumber>"));
        assertTrue(step.getContent().contains("dummy transformer"));
    }

    @Test
    public void testPluginPropertiesExtraction() throws Exception {
        String xml = "<channel version=\"3.9.1\">"
                + "<id>test-id</id><name>test</name><revision>1</revision>"
                + "<sourceConnector version=\"3.9.1\">"
                + "  <metaDataId>0</metaDataId><name>sourceConnector</name>"
                + "  <properties class=\"com.mirth.connect.connectors.tcp.TcpReceiverProperties\">"
                + "    <pluginProperties>"
                + "      <org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties version=\"4.5.2\">"
                + "        <isTlsManagerEnabled>true</isTlsManagerEnabled>"
                + "        <clientCertificateAlias>test-cert</clientCertificateAlias>"
                + "      </org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties>"
                + "    </pluginProperties>"
                + "  </properties>"
                + "  <transformer version=\"3.9.1\"><elements/></transformer>"
                + "  <filter version=\"3.9.1\"><elements/></filter>"
                + "</sourceConnector>"
                + "<destinationConnectors>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>1</metaDataId><name>Dest1</name>"
                + "    <properties class=\"com.mirth.connect.connectors.tcp.TcpDispatcherProperties\">"
                + "      <pluginProperties>"
                + "        <org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties version=\"4.5.2\">"
                + "          <isTlsManagerEnabled>false</isTlsManagerEnabled>"
                + "        </org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties>"
                + "        <com.example.another.PluginProperties>"
                + "          <setting>value</setting>"
                + "        </com.example.another.PluginProperties>"
                + "      </pluginProperties>"
                + "    </properties>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "</destinationConnectors>"
                + "</channel>";

        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // Source connector has one plugin
        String srcPluginKey = "Source Connector/Plugin: TLSConnectorProperties";
        DecomposedComponent srcPlugin = components.get(srcPluginKey);
        assertNotNull("Source TLS plugin should be extracted", srcPlugin);
        assertEquals("Source Connector", srcPlugin.getParentGroup());
        assertTrue(srcPlugin.getContent().contains("test-cert"));

        // Destination has two plugins
        String destTlsKey = "Destination [1]/Plugin: TLSConnectorProperties";
        DecomposedComponent destTls = components.get(destTlsKey);
        assertNotNull("Dest TLS plugin should be extracted", destTls);
        assertTrue(destTls.getContent().contains("isTlsManagerEnabled"));

        String destOtherKey = "Destination [1]/Plugin: PluginProperties";
        DecomposedComponent destOther = components.get(destOtherKey);
        assertNotNull("Dest second plugin should be extracted", destOther);
        assertTrue(destOther.getContent().contains("value"));

        // Plugins should NOT appear in the connector Configuration
        String srcConfig = components.get("Source Connector/Configuration").getContent();
        assertFalse(srcConfig.contains("TLSConnectorProperties"));
        assertFalse(srcConfig.contains("test-cert"));

        String destConfig = components.get("Destination [1]/Configuration").getContent();
        assertFalse(destConfig.contains("TLSConnectorProperties"));
        assertFalse(destConfig.contains("PluginProperties"));
    }

    @Test
    public void testEmptyPluginPropertiesProducesNoComponents() throws Exception {
        // The standard test XML has empty <pluginProperties/> — no plugin components should be created
        String xml = loadResource("channel-for-diffing-version1.xml");
        Map<String, DecomposedComponent> components = ChannelXmlDecomposer.decomposeWithNames(xml).getComponents();

        // No plugin components for any connector
        for (Map.Entry<String, DecomposedComponent> entry : components.entrySet()) {
            assertFalse("No plugin components expected, but found: " + entry.getKey(),
                    entry.getKey().contains("/Plugin: "));
        }
    }

    @Test
    public void testGetStepTypeName() {
        assertEquals("JavaScriptStep",
                ChannelXmlDecomposer.getStepTypeName("com.mirth.connect.plugins.javascriptstep.JavaScriptStep"));
        assertEquals("MapperStep",
                ChannelXmlDecomposer.getStepTypeName("com.mirth.connect.plugins.mapper.MapperStep"));
        assertEquals("simpleTag",
                ChannelXmlDecomposer.getStepTypeName("simpleTag"));
    }

    @Test
    public void testDestinationOrderReorderDetection() throws Exception {
        String xml1 = "<channel version=\"3.9.1\">"
                + "<id>test-id</id><name>test</name><revision>1</revision>"
                + "<sourceConnector version=\"3.9.1\">"
                + "  <metaDataId>0</metaDataId><name>sourceConnector</name>"
                + "  <properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\"/>"
                + "  <transformer version=\"3.9.1\"><elements/></transformer>"
                + "  <filter version=\"3.9.1\"><elements/></filter>"
                + "</sourceConnector>"
                + "<destinationConnectors>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>1</metaDataId><name>Dest A</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>2</metaDataId><name>Dest B</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "</destinationConnectors>"
                + "</channel>";

        // Same channel but destinations reordered
        String xml2 = "<channel version=\"3.9.1\">"
                + "<id>test-id</id><name>test</name><revision>1</revision>"
                + "<sourceConnector version=\"3.9.1\">"
                + "  <metaDataId>0</metaDataId><name>sourceConnector</name>"
                + "  <properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\"/>"
                + "  <transformer version=\"3.9.1\"><elements/></transformer>"
                + "  <filter version=\"3.9.1\"><elements/></filter>"
                + "</sourceConnector>"
                + "<destinationConnectors>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>2</metaDataId><name>Dest B</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>1</metaDataId><name>Dest A</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "</destinationConnectors>"
                + "</channel>";

        Map<String, DecomposedComponent> comp1 = ChannelXmlDecomposer.decomposeWithNames(xml1).getComponents();
        Map<String, DecomposedComponent> comp2 = ChannelXmlDecomposer.decomposeWithNames(xml2).getComponents();

        // Destination Order should differ (destinations reordered)
        assertNotEquals(
                comp1.get("Destination Order").getContent(),
                comp2.get("Destination Order").getContent());
        assertEquals("1. Dest A [1]\n2. Dest B [2]",
                comp1.get("Destination Order").getContent());
        assertEquals("1. Dest B [2]\n2. Dest A [1]",
                comp2.get("Destination Order").getContent());

        // Individual destination components should still match (content unchanged)
        assertEquals(
                comp1.get("Destination [1]/Configuration").getContent(),
                comp2.get("Destination [1]/Configuration").getContent());
        assertEquals(
                comp1.get("Destination [2]/Configuration").getContent(),
                comp2.get("Destination [2]/Configuration").getContent());
    }

    @Test
    public void testDestinationRenameMatchesByMetaDataId() throws Exception {
        String xml1 = "<channel version=\"3.9.1\">"
                + "<id>test-id</id><name>test</name><revision>1</revision>"
                + "<sourceConnector version=\"3.9.1\">"
                + "  <metaDataId>0</metaDataId><name>sourceConnector</name>"
                + "  <properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\"/>"
                + "  <transformer version=\"3.9.1\"><elements/></transformer>"
                + "  <filter version=\"3.9.1\"><elements/></filter>"
                + "</sourceConnector>"
                + "<destinationConnectors>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>1</metaDataId><name>Old Name</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "</destinationConnectors>"
                + "</channel>";

        // Same destination (metaDataId=1) but renamed
        String xml2 = "<channel version=\"3.9.1\">"
                + "<id>test-id</id><name>test</name><revision>1</revision>"
                + "<sourceConnector version=\"3.9.1\">"
                + "  <metaDataId>0</metaDataId><name>sourceConnector</name>"
                + "  <properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\"/>"
                + "  <transformer version=\"3.9.1\"><elements/></transformer>"
                + "  <filter version=\"3.9.1\"><elements/></filter>"
                + "</sourceConnector>"
                + "<destinationConnectors>"
                + "  <connector version=\"3.9.1\">"
                + "    <metaDataId>1</metaDataId><name>New Name</name>"
                + "    <properties class=\"com.mirth.connect.connectors.vm.VmDispatcherProperties\"/>"
                + "    <transformer version=\"3.9.1\"><elements/></transformer>"
                + "    <filter version=\"3.9.1\"><elements/></filter>"
                + "  </connector>"
                + "</destinationConnectors>"
                + "</channel>";

        Map<String, DecomposedComponent> comp1 = ChannelXmlDecomposer.decomposeWithNames(xml1).getComponents();
        Map<String, DecomposedComponent> comp2 = ChannelXmlDecomposer.decomposeWithNames(xml2).getComponents();

        // Both versions key by metaDataId — Destination [1] exists in both
        assertNotNull(comp1.get("Destination [1]/Configuration"));
        assertNotNull(comp2.get("Destination [1]/Configuration"));

        // Config differs because the <name> element changed
        assertNotEquals(
                comp1.get("Destination [1]/Configuration").getContent(),
                comp2.get("Destination [1]/Configuration").getContent());

        // Destination Order reflects the name change
        assertEquals("1. Old Name [1]", comp1.get("Destination Order").getContent());
        assertEquals("1. New Name [1]", comp2.get("Destination Order").getContent());
    }

    @Test
    public void testDecomposeWithNamesReturnsDisplayNames() throws Exception {
        String xml = loadResource("channel-for-diffing-version1.xml");
        ChannelXmlDecomposer.DecomposeResult result = ChannelXmlDecomposer.decomposeWithNames(xml);

        Map<String, String> displayNames = result.getGroupDisplayNames();
        assertEquals("Destination: Destination 1 [1]", displayNames.get("Destination [1]"));
        assertEquals("Destination: Destination 2 [2]", displayNames.get("Destination [2]"));

        // Components use stable keys
        assertNotNull(result.getComponents().get("Destination [1]/Script"));
        assertNotNull(result.getComponents().get("Destination [2]/Configuration"));
    }
}
