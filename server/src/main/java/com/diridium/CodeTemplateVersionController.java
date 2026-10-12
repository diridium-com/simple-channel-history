// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.plugins.CodeTemplateServerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class CodeTemplateVersionController implements CodeTemplateServerPlugin {

    private static final Logger log = LoggerFactory.getLogger(CodeTemplateVersionController.class);

    private DatabaseHistoryRepository repo;

    @Override
    public String getPluginPointName() {
        return ChannelHistoryServletInterface.PLUGIN_NAME;
    }

    @Override
    public void start() {
        log.info("starting simple-channel-history CodeTemplate version controller");
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        DatabaseHistoryRepository.init(serializer);
        repo = DatabaseHistoryRepository.getInstance();
    }

    @Override
    public void stop() {
        // do not close the repo here, it gets called from ChannelVersionController
    }

    @Override
    public void remove(CodeTemplate ct, ServerEventContext sec) {
        // The engine passes the full template: removeCodeTemplate looks it up before deleting the row.
        repo.saveDeletedCodeTemplate(ct, sec.getUserId());
        repo.deleteCodeTemplateHistory(ct.getId());
    }

    @Override
    public void remove(CodeTemplateLibrary ctLib, ServerEventContext sec) {
    }

    @Override
    public void save(CodeTemplate ct, ServerEventContext sec) {
        repo.saveCodeTemplateHistory(ct, sec.getUserId());
    }

    @Override
    public void save(CodeTemplateLibrary ctLib, ServerEventContext sec) {
    }
}
