// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.model.Channel;
import com.mirth.connect.model.User;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.UserController;
import com.mirth.connect.server.util.SqlConfig;

/**
 * Database-backed repository for storing channel and code template history.
 */
public class DatabaseHistoryRepository {

    private static DatabaseHistoryRepository instance;
    private static final Logger log = LoggerFactory.getLogger(DatabaseHistoryRepository.class);

    private static final String NAMESPACE = "ChannelHistory";

    private ObjectXMLSerializer serializer;
    private UserController userController;

    private DatabaseHistoryRepository() {
    }

    public static synchronized void init(ObjectXMLSerializer serializer) {
        if (instance == null) {
            instance = new DatabaseHistoryRepository();
            instance.serializer = serializer;
            instance.userController = ControllerFactory.getFactory().createUserController();
            log.info("DatabaseHistoryRepository initialized");
        }
    }

    public static DatabaseHistoryRepository getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DatabaseHistoryRepository not initialized, call init() first");
        }
        return instance;
    }

    public static synchronized void close() {
        instance = null;
    }

    private static String stmt(String id) {
        return NAMESPACE + "." + id;
    }

    // ========== Channel History Methods ==========

    public void saveChannelHistory(Channel channel, int userId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("revision", channel.getRevision());
            params.put("channelId", channel.getId());
            params.put("userId", userId);
            params.put("dateCreated", new Timestamp(System.currentTimeMillis()));
            params.put("channel", serializer.serialize(channel));

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertChannelHistory"), params);
            log.debug("Saved channel history for channel {} revision {}", channel.getId(), channel.getRevision());
        } catch (Exception e) {
            // Fail silent - don't block channel save if history save fails
            log.error("Failed to save channel history for channel {}", channel.getId(), e);
        }
    }

    public List<RevisionInfo> getChannelHistory(String channelId) {
        List<RevisionInfo> history = new ArrayList<>();

        try {
            List<Map<String, Object>> results = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("getChannelHistory"), channelId);

            for (Map<String, Object> row : results) {
                RevisionInfo ri = new RevisionInfo();
                ri.setHash(String.valueOf(row.get("id")));
                ri.setRevision((Integer) row.get("revision"));
                Timestamp ts = (Timestamp) row.get("dateCreated");
                ri.setTime(ts != null ? ts.getTime() : 0L);
                ri.setCommitterName(getUserName((Integer) row.get("userId")));
                history.add(ri);
            }
        } catch (Exception e) {
            log.error("Failed to get channel history for {}", channelId, e);
            throw new RuntimeException(e);
        }

        return history;
    }

    public String getChannelContent(String channelId, String historyId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", Long.parseLong(historyId));
            params.put("channelId", channelId);

            return SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getChannelContent"), params);
        } catch (Exception e) {
            log.error("Failed to get channel content for {} at history {}", channelId, historyId, e);
            throw new RuntimeException(e);
        }
    }

    public int getChannelRevisionNumber(String channelId, String historyId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", Long.parseLong(historyId));
            params.put("channelId", channelId);

            Integer revision = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getChannelRevisionNumber"), params);
            return revision != null ? revision : -1;
        } catch (Exception e) {
            log.error("Failed to get revision number for {} at history {}", channelId, historyId, e);
        }

        return -1;
    }

    public Channel getChannelAtRevision(String channelId, String historyId) throws Exception {
        // First verify this isn't the latest entry
        List<RevisionInfo> history = getChannelHistory(channelId);
        if (!history.isEmpty() && history.get(0).getHash().equals(historyId)) {
            throw new IllegalArgumentException("cannot revert to the same revision");
        }

        String content = getChannelContent(channelId, historyId);
        if (content == null) {
            throw new IllegalArgumentException("no history " + historyId + " of Channel " + channelId + " exists");
        }

        return serializer.deserialize(content, Channel.class);
    }

    public void deleteChannelHistory(String channelId) {
        try {
            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("deleteChannelHistory"), channelId);
            log.debug("Deleted {} history entries for channel {}", deleted, channelId);
        } catch (Exception e) {
            // Fail silent - don't block channel delete if history cleanup fails
            log.error("Failed to delete channel history for {}", channelId, e);
        }
    }

    // ========== Code Template History Methods ==========

    public void saveCodeTemplateHistory(CodeTemplate codeTemplate, int userId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("revision", codeTemplate.getRevision());
            params.put("codeTemplateId", codeTemplate.getId());
            params.put("userId", userId);
            params.put("dateCreated", new Timestamp(System.currentTimeMillis()));
            params.put("codeTemplate", serializer.serialize(codeTemplate));

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertCodeTemplateHistory"), params);
            log.debug("Saved code template history for {} revision {}", codeTemplate.getId(), codeTemplate.getRevision());
        } catch (Exception e) {
            // Fail silent - don't block code template save if history save fails
            log.error("Failed to save code template history for {}", codeTemplate.getId(), e);
        }
    }

    public List<RevisionInfo> getCodeTemplateHistory(String codeTemplateId) {
        List<RevisionInfo> history = new ArrayList<>();

        try {
            List<Map<String, Object>> results = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("getCodeTemplateHistory"), codeTemplateId);

            for (Map<String, Object> row : results) {
                RevisionInfo ri = new RevisionInfo();
                ri.setHash(String.valueOf(row.get("id")));
                ri.setRevision((Integer) row.get("revision"));
                Timestamp ts = (Timestamp) row.get("dateCreated");
                ri.setTime(ts != null ? ts.getTime() : 0L);
                ri.setCommitterName(getUserName((Integer) row.get("userId")));
                history.add(ri);
            }
        } catch (Exception e) {
            log.error("Failed to get code template history for {}", codeTemplateId, e);
            throw new RuntimeException(e);
        }

        return history;
    }

    public String getCodeTemplateContent(String codeTemplateId, String historyId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("id", Long.parseLong(historyId));
            params.put("codeTemplateId", codeTemplateId);

            return SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getCodeTemplateContent"), params);
        } catch (Exception e) {
            log.error("Failed to get code template content for {} at history {}", codeTemplateId, historyId, e);
            throw new RuntimeException(e);
        }
    }

    public void deleteCodeTemplateHistory(String codeTemplateId) {
        try {
            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("deleteCodeTemplateHistory"), codeTemplateId);
            log.debug("Deleted {} history entries for code template {}", deleted, codeTemplateId);
        } catch (Exception e) {
            // Fail silent - don't block code template delete if history cleanup fails
            log.error("Failed to delete code template history for {}", codeTemplateId, e);
        }
    }

    public CodeTemplate getCodeTemplateAtRevision(String codeTemplateId, String historyId) throws Exception {
        // First verify this isn't the latest entry
        List<RevisionInfo> history = getCodeTemplateHistory(codeTemplateId);
        if (!history.isEmpty() && history.get(0).getHash().equals(historyId)) {
            throw new IllegalArgumentException("cannot revert to the same revision");
        }

        String content = getCodeTemplateContent(codeTemplateId, historyId);
        if (content == null) {
            throw new IllegalArgumentException("no history " + historyId + " of CodeTemplate " + codeTemplateId + " exists");
        }

        return serializer.deserialize(content, CodeTemplate.class);
    }

    // ========== Prune Methods ==========

    /** @return whether historyId is one of this channel's history rows (false for a non-numeric id) */
    public boolean isChannelHistoryRow(String channelId, String historyId) {
        return isHistoryRow("getChannelRevisionNumber", "channelId", channelId, historyId);
    }

    /** @return whether historyId is one of this code template's history rows (false for a non-numeric id) */
    public boolean isCodeTemplateHistoryRow(String codeTemplateId, String historyId) {
        return isHistoryRow("getCodeTemplateRevisionNumber", "codeTemplateId", codeTemplateId, historyId);
    }

    private boolean isHistoryRow(String statement, String itemKey, String itemId, String historyId) {
        long id;
        try {
            id = Long.parseLong(historyId);
        } catch (NumberFormatException e) {
            return false;
        }
        Map<String, Object> params = new HashMap<>();
        params.put(itemKey, itemId);
        params.put("id", id);
        return SqlConfig.getInstance().getSqlSessionManager().selectOne(stmt(statement), params) != null;
    }

    public int pruneChannelHistoryOlderThan(String channelId, String historyId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channelId);
            params.put("id", Long.parseLong(historyId));

            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("pruneChannelHistory"), params);
            log.info("Pruned {} older history entries for channel {}", deleted, channelId);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to prune channel history for {}", channelId, e);
            throw new RuntimeException(e);
        }
    }

    public int pruneCodeTemplateHistoryOlderThan(String codeTemplateId, String historyId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("codeTemplateId", codeTemplateId);
            params.put("id", Long.parseLong(historyId));

            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("pruneCodeTemplateHistory"), params);
            log.info("Pruned {} older history entries for code template {}", deleted, codeTemplateId);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to prune code template history for {}", codeTemplateId, e);
            throw new RuntimeException(e);
        }
    }

    // ========== Deleted Item Methods ==========

    public void saveDeletedChannel(Channel channel, int userId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("channelId", channel.getId());
            params.put("name", channel.getName());
            params.put("userId", userId);
            params.put("dateDeleted", new Timestamp(System.currentTimeMillis()));
            params.put("content", serializer.serialize(channel));

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertDeletedChannel"), params);
            log.info("Saved deleted channel snapshot for {} ({})", channel.getName(), channel.getId());
        } catch (Exception e) {
            // Fail silent - don't block channel delete if snapshot save fails
            // The caller deletes the history even then: an insert failing here means the database itself is failing.
            log.error("Failed to save deleted channel snapshot for {}", channel.getId(), e);
        }
    }

    public void saveDeletedCodeTemplate(CodeTemplate codeTemplate, int userId) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("codeTemplateId", codeTemplate.getId());
            params.put("name", codeTemplate.getName());
            params.put("userId", userId);
            params.put("dateDeleted", new Timestamp(System.currentTimeMillis()));
            params.put("content", serializer.serialize(codeTemplate));

            SqlConfig.getInstance().getSqlSessionManager().insert(stmt("insertDeletedCodeTemplate"), params);
            log.info("Saved deleted code template snapshot for {} ({})", codeTemplate.getName(), codeTemplate.getId());
        } catch (Exception e) {
            // Fail silent - don't block code template delete if snapshot save fails
            // The caller deletes the history even then: an insert failing here means the database itself is failing.
            log.error("Failed to save deleted code template snapshot for {}", codeTemplate.getId(), e);
        }
    }

    public List<DeletedItemInfo> getDeletedChannels() {
        List<DeletedItemInfo> items = new ArrayList<>();

        try {
            List<Map<String, Object>> results = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("getDeletedChannels"));

            for (Map<String, Object> row : results) {
                DeletedItemInfo info = new DeletedItemInfo();
                info.setId((Long) row.get("id"));
                info.setItemId((String) row.get("channelId"));
                info.setName((String) row.get("name"));
                info.setDeletedBy(getUserName((Integer) row.get("userId")));
                Timestamp ts = (Timestamp) row.get("dateDeleted");
                info.setDateDeleted(ts != null ? ts.getTime() : 0L);
                items.add(info);
            }
        } catch (Exception e) {
            log.error("Failed to get deleted channels", e);
            throw new RuntimeException(e);
        }

        return items;
    }

    public List<DeletedItemInfo> getDeletedCodeTemplates() {
        List<DeletedItemInfo> items = new ArrayList<>();

        try {
            List<Map<String, Object>> results = SqlConfig.getInstance().getSqlSessionManager()
                    .selectList(stmt("getDeletedCodeTemplates"));

            for (Map<String, Object> row : results) {
                DeletedItemInfo info = new DeletedItemInfo();
                info.setId((Long) row.get("id"));
                info.setItemId((String) row.get("codeTemplateId"));
                info.setName((String) row.get("name"));
                info.setDeletedBy(getUserName((Integer) row.get("userId")));
                Timestamp ts = (Timestamp) row.get("dateDeleted");
                info.setDateDeleted(ts != null ? ts.getTime() : 0L);
                items.add(info);
            }
        } catch (Exception e) {
            log.error("Failed to get deleted code templates", e);
            throw new RuntimeException(e);
        }

        return items;
    }

    public String getDeletedChannelContent(long id) {
        try {
            return SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getDeletedChannelContent"), id);
        } catch (Exception e) {
            log.error("Failed to get deleted channel content for id {}", id, e);
            throw new RuntimeException(e);
        }
    }

    public String getDeletedCodeTemplateContent(long id) {
        try {
            return SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getDeletedCodeTemplateContent"), id);
        } catch (Exception e) {
            log.error("Failed to get deleted code template content for id {}", id, e);
            throw new RuntimeException(e);
        }
    }

    public DeletedItemInfo getDeletedChannelInfo(long id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getDeletedChannelInfoById"), id);
            if (row == null) return null;

            DeletedItemInfo info = new DeletedItemInfo();
            info.setId((Long) row.get("id"));
            info.setItemId((String) row.get("channelId"));
            info.setName((String) row.get("name"));
            info.setDeletedBy(getUserName((Integer) row.get("userId")));
            Timestamp ts = (Timestamp) row.get("dateDeleted");
            info.setDateDeleted(ts != null ? ts.getTime() : 0L);
            return info;
        } catch (Exception e) {
            log.error("Failed to get deleted channel info for id {}", id, e);
            return null;
        }
    }

    public DeletedItemInfo getDeletedCodeTemplateInfo(long id) {
        try {
            Map<String, Object> row = SqlConfig.getInstance().getSqlSessionManager()
                    .selectOne(stmt("getDeletedCodeTemplateInfoById"), id);
            if (row == null) return null;

            DeletedItemInfo info = new DeletedItemInfo();
            info.setId((Long) row.get("id"));
            info.setItemId((String) row.get("codeTemplateId"));
            info.setName((String) row.get("name"));
            info.setDeletedBy(getUserName((Integer) row.get("userId")));
            Timestamp ts = (Timestamp) row.get("dateDeleted");
            info.setDateDeleted(ts != null ? ts.getTime() : 0L);
            return info;
        } catch (Exception e) {
            log.error("Failed to get deleted code template info for id {}", id, e);
            return null;
        }
    }

    /** @return the number of snapshots deleted: 1, or 0 if there was none with that id */
    public int purgeDeletedChannel(long id) {
        try {
            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("purgeDeletedChannel"), id);
            log.info("Purged {} deleted channel snapshot(s) with id {}", deleted, id);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to purge deleted channel id {}", id, e);
            throw new RuntimeException(e);
        }
    }

    /** @return the number of snapshots deleted: 1, or 0 if there was none with that id */
    public int purgeDeletedCodeTemplate(long id) {
        try {
            int deleted = SqlConfig.getInstance().getSqlSessionManager()
                    .delete(stmt("purgeDeletedCodeTemplate"), id);
            log.info("Purged {} deleted code template snapshot(s) with id {}", deleted, id);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to purge deleted code template id {}", id, e);
            throw new RuntimeException(e);
        }
    }

    // ========== Helper Methods ==========

    public String getUserName(int userId) {
        try {
            User user = userController.getUser(userId, null);
            if (user != null) {
                return user.getUsername();
            }
        } catch (Exception e) {
            log.debug("Could not get username for user {}", userId, e);
        }
        return "Unknown";
    }
}
