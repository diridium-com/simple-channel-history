// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelMetadata;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEvent.Level;
import com.mirth.connect.model.ServerEvent.Outcome;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.server.api.CheckAuthorizedChannelId;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kiran Ayyagari (kayyagari@apache.org)
 */
public class ChannelHistoryServlet extends MirthServlet implements ChannelHistoryServletInterface {

    private static final Logger log = LoggerFactory.getLogger(ChannelHistoryServlet.class);

    private DatabaseHistoryRepository repo;

    private ChannelController channelController;
    private CodeTemplateController codeTemplateController;
    private EventController eventController;
    private String serverId;

    public ChannelHistoryServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_NAME);
        repo = DatabaseHistoryRepository.getInstance();
        channelController = ChannelController.getInstance();
        codeTemplateController = ControllerFactory.getFactory().createCodeTemplateController();
        eventController = ControllerFactory.getFactory().createEventController();
        serverId = ConfigurationController.getInstance().getServerId();
    }

    @Override
    @CheckAuthorizedChannelId
    public List<RevisionInfo> getHistory(String channelId) throws ClientException {
        try {
            return repo.getChannelHistory(channelId);
        }
        catch(Exception e) {
            log.warn("failed to get the history of channel {}", channelId, e);
            throw new ClientException(e);
        }
    }

    @Override
    @CheckAuthorizedChannelId
    public String getContent(String channelId, String revision) throws ClientException {
        try {
            return repo.getChannelContent(channelId, revision);
        }
        catch(Exception e) {
            log.warn("failed to get the content of channel {} at revision {}", channelId, revision, e);
            throw new ClientException(e);
        }
    }

    private static final String REVERT_HISTORY_BEGIN = "--- BEGIN REVERT HISTORY (do not delete these tags) ---";
    private static final String REVERT_HISTORY_END = "--- END REVERT HISTORY ---";

    @Override
    @CheckAuthorizedChannelId
    public boolean revertChannel(String channelId, String revision) throws ClientException {
        try {
            Channel channel = repo.getChannelAtRevision(channelId, revision);

            // Get original description from the revision we're reverting to
            String oldDesc = channel.getDescription();
            if (oldDesc == null) {
                oldDesc = "";
            }
            // Remove any existing revert history from the old description
            int beginIdx = oldDesc.indexOf(REVERT_HISTORY_BEGIN);
            if (beginIdx >= 0) {
                oldDesc = oldDesc.substring(0, beginIdx).trim();
            }

            // Extract existing revert history from current channel (if any)
            String existingHistory = "";
            Channel currentChannel = channelController.getChannelById(channelId);
            if (currentChannel != null && currentChannel.getDescription() != null) {
                String currentDesc = currentChannel.getDescription();
                int currentBegin = currentDesc.indexOf(REVERT_HISTORY_BEGIN);
                int currentEnd = currentDesc.indexOf(REVERT_HISTORY_END);
                if (currentBegin >= 0 && currentEnd > currentBegin) {
                    existingHistory = currentDesc.substring(currentBegin + REVERT_HISTORY_BEGIN.length(), currentEnd).trim();
                }
            }

            // Build new revert entry using the revision number from the history table
            String username = repo.getUserName(context.getUserId());
            int revisionNumber = repo.getChannelRevisionNumber(channelId, revision);
            String newEntry = "(reverted to Rev " + revisionNumber + " by " + username + " at " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + ")";

            // Combine: old description + revert history section at the end (newest first)
            StringBuilder newDesc = new StringBuilder(oldDesc);
            newDesc.append("\n\n").append(REVERT_HISTORY_BEGIN).append("\n");
            newDesc.append(newEntry).append("\n");
            if (!existingHistory.isEmpty()) {
                newDesc.append(existingHistory).append("\n");
            }
            newDesc.append(REVERT_HISTORY_END);

            channel.setDescription(newDesc.toString());
            // Read just before the save, so a change made meanwhile (enable/disable, a tag edit) is kept.
            keepCurrentSettings(channel, channelId);
            boolean result = channelController.updateChannel(channel, context, true, Calendar.getInstance());
            log.debug("reverted Channel {} to revision {}", channelId, revision);

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("channel", "Channel[id=" + channelId + ",name=" + channel.getName() + "]");
            attributes.put("Reverted to revision", String.valueOf(revisionNumber));
            audit(attributes);

            return result;
        }
        catch (Exception e) {
            log.warn("failed to revert Channel {} to revision {}", channelId, revision);
            throw new ClientException(e);
        }
    }

    /**
     * A history row contains only the channel's source: the engine strips its settings and tags
     * before the plugin's save hook runs. updateChannel stores whatever settings and tags arrive
     * with the channel, so attach the current ones, as the channel editor does on every save.
     * Otherwise a revert stores blank defaults: enabled, no pruning, and no tags.
     */
    private void keepCurrentSettings(Channel channel, String channelId) {
        ConfigurationController configurationController = ConfigurationController.getInstance();

        // getChannelMetadata() deserializes a fresh map on every call, so the channel's own metadata
        // is reused as is: every setting in it, including any a later engine adds, stays unchanged.
        ChannelMetadata metadata = configurationController.getChannelMetadata().get(channelId);
        if (metadata == null) {
            metadata = new ChannelMetadata(); // the engine treats missing metadata as enabled
        }
        // Stamped like an editor save, so another user's open editor sees the channel changed.
        metadata.setLastModified(Calendar.getInstance());
        // Recorded as saved by the reverting user, as an editor save is.
        metadata.setUserId(context.getUserId());
        channel.getExportData().setMetadata(metadata);

        List<ChannelTag> tags = new ArrayList<>();
        for (ChannelTag tag : configurationController.getChannelTags()) {
            if (tag.getChannelIds().contains(channelId)) {
                tags.add(tag);
            }
        }
        channel.getExportData().setChannelTags(tags);
    }

    @Override
    public List<RevisionInfo> getCodeTemplateHistory(String codeTemplateId) throws ClientException {
        try {
            return repo.getCodeTemplateHistory(codeTemplateId);
        }
        catch(Exception e) {
            log.warn("failed to get the history of code template {}", codeTemplateId, e);
            throw new ClientException(e);
        }
    }

    @Override
    public String getCodeTemplateContent(String codeTemplateId, String revision) throws ClientException {
        try {
            return repo.getCodeTemplateContent(codeTemplateId, revision);
        }
        catch(Exception e) {
            log.warn("failed to get the content of code template {} at revision {}", codeTemplateId, revision, e);
            throw new ClientException(e);
        }
    }

    @Override
    public boolean revertCodeTemplate(String codeTemplateId, String revision) throws ClientException {
        try {
            CodeTemplate codeTemplate = repo.getCodeTemplateAtRevision(codeTemplateId, revision);
            // Read before the update: the engine sets the template's revision to the next number.
            int revisionNumber = codeTemplate.getRevision();

            // Update the code template
            codeTemplateController.updateCodeTemplate(codeTemplate, context, true);
            log.debug("reverted CodeTemplate {} to revision {}", codeTemplateId, revision);

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Code Template", codeTemplate.getName());
            attributes.put("Code Template ID", codeTemplateId);
            attributes.put("Reverted to revision", String.valueOf(revisionNumber));
            audit(attributes);

            return true;
        }
        catch (Exception e) {
            log.warn("failed to revert CodeTemplate {} to revision {}", codeTemplateId, revision, e);
            throw new ClientException(e);
        }
    }

    @Override
    @CheckAuthorizedChannelId
    public int pruneChannelHistory(String channelId, String revision) throws ClientException {
        checkPruneCutoff(repo.isChannelHistoryRow(channelId, revision), "channel", channelId, revision);
        try {
            int deleted = repo.pruneChannelHistoryOlderThan(channelId, revision);
            log.info("Pruned {} older revisions for channel {}", deleted, channelId);

            String channelName = "Unknown";
            try {
                Channel ch = channelController.getChannelById(channelId);
                if (ch != null) channelName = ch.getName();
            } catch (Exception ignore) {}

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("channel", "Channel[id=" + channelId + ",name=" + channelName + "]");
            attributes.put("Revisions pruned", String.valueOf(deleted));
            audit(attributes);

            return deleted;
        }
        catch (Exception e) {
            log.warn("failed to prune channel history for {} at revision {}", channelId, revision, e);
            throw new ClientException(e);
        }
    }

    @Override
    public int pruneCodeTemplateHistory(String codeTemplateId, String revision) throws ClientException {
        checkPruneCutoff(repo.isCodeTemplateHistoryRow(codeTemplateId, revision), "code template", codeTemplateId, revision);
        try {
            int deleted = repo.pruneCodeTemplateHistoryOlderThan(codeTemplateId, revision);
            log.info("Pruned {} older revisions for code template {}", deleted, codeTemplateId);

            String templateName = "Unknown";
            try {
                CodeTemplate ct = codeTemplateController.getCodeTemplateById(codeTemplateId);
                if (ct != null) templateName = ct.getName();
            } catch (Exception ignore) {}

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Code Template", templateName);
            attributes.put("Code Template ID", codeTemplateId);
            attributes.put("Revisions pruned", String.valueOf(deleted));
            audit(attributes);

            return deleted;
        }
        catch (Exception e) {
            log.warn("failed to prune code template history for {} at revision {}", codeTemplateId, revision, e);
            throw new ClientException(e);
        }
    }

    /**
     * Prune deletes every row older than the cut-off, so the cut-off must be one of the item's own
     * rows. Any other number would cut the history at an arbitrary point, and a large one would delete
     * every row, the newest too. The administrators always send a row from the item's own list.
     */
    private void checkPruneCutoff(boolean isOwnRow, String kind, String itemId, String revision) {
        if (!isOwnRow) {
            throw new MirthApiException(Response.status(Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN)
                    .entity("History " + revision + " is not a revision of " + kind + " " + itemId + "; nothing was pruned.").build());
        }
    }

    // ========== Deleted Item Endpoints ==========

    // A deleted channel's snapshot is addressed by its row number, so the engine's
    // @CheckAuthorizedChannelId can't see which channel it belongs to. These endpoints apply the
    // user's channel restrictions themselves (an authorization plugin such as rbac sets them).

    @Override
    public List<DeletedItemInfo> getDeletedChannels() throws ClientException {
        try {
            List<DeletedItemInfo> items = repo.getDeletedChannels();
            if (doesUserHaveChannelRestrictions()) {
                items.removeIf(item -> isChannelRedacted(item.getItemId()));
            }
            return items;
        } catch (Exception e) {
            log.warn("failed to get deleted channels", e);
            throw new ClientException(e);
        }
    }

    @Override
    public String getDeletedChannelContent(long id) throws ClientException {
        checkDeletedChannelAuthorized(id, null);
        try {
            return repo.getDeletedChannelContent(id);
        } catch (Exception e) {
            log.warn("failed to get deleted channel content for id {}", id, e);
            throw new ClientException(e);
        }
    }

    @Override
    public boolean purgeDeletedChannel(long id) throws ClientException {
        DeletedItemInfo info = repo.getDeletedChannelInfo(id);
        checkDeletedChannelAuthorized(id, info);
        try {
            // Nothing to purge: report it rather than a success.
            if (info == null || repo.purgeDeletedChannel(id) == 0) {
                return false;
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("channel", "Channel[id=" + info.getItemId() + ",name=" + info.getName() + "]");
            audit(attributes);

            return true;
        } catch (Exception e) {
            log.warn("failed to purge deleted channel id {}", id, e);
            throw new ClientException(e);
        }
    }

    /**
     * Refuses a restricted user a snapshot of a channel outside their restrictions. A snapshot that
     * can't be found is refused too, so a restricted user can't learn which row numbers exist.
     * Unrestricted users skip the lookup.
     */
    private void checkDeletedChannelAuthorized(long id, DeletedItemInfo info) {
        if (doesUserHaveChannelRestrictions()) {
            if (info == null) {
                info = repo.getDeletedChannelInfo(id);
            }
            if (info == null || isChannelRedacted(info.getItemId())) {
                throw new MirthApiException(Status.FORBIDDEN);
            }
        }
    }

    @Override
    public List<DeletedItemInfo> getDeletedCodeTemplates() throws ClientException {
        try {
            return repo.getDeletedCodeTemplates();
        } catch (Exception e) {
            log.warn("failed to get deleted code templates", e);
            throw new ClientException(e);
        }
    }

    @Override
    public String getDeletedCodeTemplateContent(long id) throws ClientException {
        try {
            return repo.getDeletedCodeTemplateContent(id);
        } catch (Exception e) {
            log.warn("failed to get deleted code template content for id {}", id, e);
            throw new ClientException(e);
        }
    }

    @Override
    public boolean purgeDeletedCodeTemplate(long id) throws ClientException {
        try {
            DeletedItemInfo info = repo.getDeletedCodeTemplateInfo(id);
            // Nothing to purge: report it rather than a success.
            if (info == null || repo.purgeDeletedCodeTemplate(id) == 0) {
                return false;
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("Code Template", info.getName());
            attributes.put("Code Template ID", info.getItemId());
            audit(attributes);

            return true;
        } catch (Exception e) {
            log.warn("failed to purge deleted code template id {}", id, e);
            throw new ClientException(e);
        }
    }

    /** Records one of the plugin's actions in the engine's event log, with the user and address that made it. */
    private void audit(Map<String, String> attributes) {
        ServerEvent event = new ServerEvent(serverId, PLUGIN_NAME, Level.INFORMATION, Outcome.SUCCESS, attributes);
        event.setUserId(getCurrentUserId());
        event.setIpAddress(getRequestIpAddress());
        eventController.dispatchEvent(event);
    }
}
