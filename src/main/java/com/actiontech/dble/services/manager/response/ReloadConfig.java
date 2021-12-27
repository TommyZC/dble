/*
 * Copyright (C) 2016-2021 ActionTech.
 * based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */
package com.actiontech.dble.services.manager.response;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.*;
import com.actiontech.dble.btrace.provider.ClusterDelayProvider;
import com.actiontech.dble.cluster.*;
import com.actiontech.dble.cluster.values.ConfStatus;
import com.actiontech.dble.config.*;
import com.actiontech.dble.config.model.ClusterConfig;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.config.model.sharding.SchemaConfig;
import com.actiontech.dble.config.model.sharding.table.ERTable;
import com.actiontech.dble.config.model.user.RwSplitUserConfig;
import com.actiontech.dble.config.model.user.UserConfig;
import com.actiontech.dble.config.model.user.UserName;
import com.actiontech.dble.config.util.ConfigException;
import com.actiontech.dble.config.util.ConfigUtil;
import com.actiontech.dble.meta.ReloadLogHelper;
import com.actiontech.dble.meta.ReloadManager;
import com.actiontech.dble.net.IOProcessor;
import com.actiontech.dble.net.connection.BackendConnection;
import com.actiontech.dble.net.connection.FrontendConnection;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.route.function.AbstractPartitionAlgorithm;
import com.actiontech.dble.route.parser.ManagerParseConfig;
import com.actiontech.dble.server.variables.SystemVariables;
import com.actiontech.dble.server.variables.VarsExtractorHandler;
import com.actiontech.dble.services.manager.ManagerService;
import com.actiontech.dble.singleton.CronScheduler;
import com.actiontech.dble.singleton.FrontendUserManager;
import com.actiontech.dble.singleton.TraceManager;
import com.actiontech.dble.util.StringUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.actiontech.dble.cluster.ClusterPathUtil.SEPARATOR;
import static com.actiontech.dble.meta.ReloadStatus.TRIGGER_TYPE_COMMAND;

public final class ReloadConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadConfig.class);

    private ReloadConfig() {
    }

    public static void execute(ManagerService service, String stmt, int offset) {
        try {
            ManagerParseConfig parser = new ManagerParseConfig();
            int rs = parser.parse(stmt, offset);
            switch (rs) {
                case ManagerParseConfig.CONFIG:
                case ManagerParseConfig.CONFIG_ALL:
                    ReloadConfig.execute(service, parser.getMode(), true, new ConfStatus(ConfStatus.Status.RELOAD_ALL));
                    break;
                default:
                    service.writeErrMessage(ErrorCode.ER_YES, "Unsupported statement");
            }
        } catch (Exception e) {
            LOGGER.info("reload error", e);
            writeErrorResult(service, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }


    public static void execute(ManagerService service, final int loadAllMode, boolean returnFlag, ConfStatus confStatus) throws Exception {
        try {
            if (ClusterConfig.getInstance().isClusterEnable()) {
                reloadWithCluster(service, loadAllMode, returnFlag, confStatus);
            } else {
                reloadWithoutCluster(service, loadAllMode, returnFlag, confStatus);
            }
        } finally {
            ReloadManager.reloadFinish();
        }
    }


    private static void reloadWithCluster(ManagerService service, int loadAllMode, boolean returnFlag, ConfStatus confStatus) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(service, "reload-with-cluster");
        try {
            DistributeLock distributeLock = null;
            if (!confStatus.getStatus().equals(ConfStatus.Status.MANAGER_INSERT) && !confStatus.getStatus().equals(ConfStatus.Status.MANAGER_UPDATE) &&
                    !confStatus.getStatus().equals(ConfStatus.Status.MANAGER_DELETE)) {
                distributeLock = ClusterHelper.createDistributeLock(ClusterPathUtil.getConfChangeLockPath(), SystemConfig.getInstance().getInstanceName());
                if (!distributeLock.acquire()) {
                    service.writeErrMessage(ErrorCode.ER_YES, "Other instance is reloading, please try again later.");
                    return;
                }
                LOGGER.info("reload config: added distributeLock " + ClusterPathUtil.getConfChangeLockPath() + "");
            }
            ClusterDelayProvider.delayAfterReloadLock();
            if (!ReloadManager.startReload(TRIGGER_TYPE_COMMAND, confStatus)) {
                writeErrorResult(service, "Reload status error ,other client or cluster may in reload");
                return;
            }
            //step 1 lock the local meta ,than all the query depends on meta will be hanging
            final ReentrantReadWriteLock lock = DbleServer.getInstance().getConfig().getLock();
            lock.writeLock().lock();
            try {
                //step 2 reload the local config file
                boolean reloadResult;
                if (confStatus.getStatus().equals(ConfStatus.Status.MANAGER_INSERT) || confStatus.getStatus().equals(ConfStatus.Status.MANAGER_UPDATE) ||
                        confStatus.getStatus().equals(ConfStatus.Status.MANAGER_DELETE)) {
                    reloadResult = reloadByConfig(loadAllMode, true);
                } else {
                    reloadResult = reloadByLocalXml(loadAllMode);
                }
                if (!reloadResult) {
                    writeSpecialError(service, "Reload interruputed by others,config should be reload");
                    return;
                }
                ReloadLogHelper.info("reload config: single instance(self) finished", LOGGER);
                ClusterDelayProvider.delayAfterMasterLoad();

                //step 3 if the reload with no error ,than write the config file into cluster center remote
                ClusterHelper.writeConfToCluster();
                ReloadLogHelper.info("reload config: sent config file to cluster center", LOGGER);

                //step 4 write the reload flag and self reload result into cluster center,notify the other dble to reload
                ConfStatus status = new ConfStatus(SystemConfig.getInstance().getInstanceName(),
                        ConfStatus.Status.RELOAD_ALL, String.valueOf(loadAllMode));
                ClusterHelper.setKV(ClusterPathUtil.getConfStatusOperatorPath(), status.toString());
                ReloadLogHelper.info("reload config: sent config status to cluster center", LOGGER);
                //step 5 start a loop to check if all the dble in cluster is reload finished
                ReloadManager.waitingOthers();
                ClusterHelper.createSelfTempNode(ClusterPathUtil.getConfStatusOperatorPath(), ClusterPathUtil.SUCCESS);
                final String errorMsg = ClusterLogic.waitingForAllTheNode(ClusterPathUtil.getConfStatusOperatorPath(), ClusterPathUtil.SUCCESS);
                ReloadLogHelper.info("reload config: all instances finished ", LOGGER);
                ClusterDelayProvider.delayBeforeDeleteReloadLock();

                if (errorMsg != null) {
                    writeErrorResultForCluster(service, errorMsg);
                    return;
                }
                if (returnFlag) {
                    writeOKResult(service);
                }
            } finally {
                lock.writeLock().unlock();
                ClusterHelper.cleanPath(ClusterPathUtil.getConfStatusOperatorPath() + SEPARATOR);
                if (distributeLock != null) {
                    distributeLock.release();
                }
            }
        } finally {
            TraceManager.finishSpan(service, traceObject);
        }
    }


    private static void reloadWithoutCluster(ManagerService service, final int loadAllMode, boolean returnFlag, ConfStatus confStatus) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.serviceTrace(service, "reload-in-local");
        final ReentrantReadWriteLock lock = DbleServer.getInstance().getConfig().getLock();
        lock.writeLock().lock();
        try {
            if (!ReloadManager.startReload(TRIGGER_TYPE_COMMAND, confStatus)) {
                writeErrorResult(service, "Reload status error ,other client or cluster may in reload");
                return;
            }
            boolean reloadResult;
            if (confStatus.getStatus().equals(ConfStatus.Status.MANAGER_INSERT) || confStatus.getStatus().equals(ConfStatus.Status.MANAGER_UPDATE) ||
                    confStatus.getStatus().equals(ConfStatus.Status.MANAGER_DELETE)) {
                reloadResult = reloadByConfig(loadAllMode, true);
            } else {
                reloadResult = reloadByLocalXml(loadAllMode);
            }
            if (reloadResult && returnFlag) {
                writeOKResult(service);
            } else if (!reloadResult) {
                writeSpecialError(service, "Reload interruputed by others,metadata should be reload");
            }
        } finally {
            lock.writeLock().unlock();
            TraceManager.finishSpan(service, traceObject);
        }
    }


    private static void writeOKResult(ManagerService service) {
        if (LOGGER.isInfoEnabled()) {
            ReloadLogHelper.info("send ok package to client " + service, LOGGER);
        }

        OkPacket ok = new OkPacket();
        ok.setPacketId(1);
        ok.setAffectedRows(1);
        ok.setServerStatus(2);
        ok.setMessage("Reload config success".getBytes());
        ok.write(service.getConnection());
    }

    private static void writeErrorResultForCluster(ManagerService service, String errorMsg) {
        String sb = "Reload config failed partially. The node(s) failed because of:[" + errorMsg + "]";
        LOGGER.warn(sb);
        if (errorMsg.contains("interrupt by command")) {
            service.writeErrMessage(ErrorCode.ER_RELOAD_INTERRUPUTED, sb);
        } else {
            service.writeErrMessage(ErrorCode.ER_CLUSTER_RELOAD, sb);
        }
    }

    private static void writeSpecialError(ManagerService service, String errorMsg) {
        String sb = "Reload config failure.The reason is " + errorMsg;
        LOGGER.warn(sb);
        service.writeErrMessage(ErrorCode.ER_RELOAD_INTERRUPUTED, sb);
    }

    private static void writeErrorResult(ManagerService c, String errorMsg) {
        String sb = "Reload config failure.The reason is " + errorMsg;
        LOGGER.warn(sb);
        c.writeErrMessage(ErrorCode.ER_YES, sb);
    }

    @Deprecated
    public static boolean reloadByLocalXml(final int loadAllMode) throws Exception {
        return reload(loadAllMode, null, null, null, null);
    }

    public static boolean reloadByConfig(final int loadAllMode, boolean isWriteToLocal) throws Exception {
        String userConfig = DbleTempConfig.getInstance().getUserConfig();
        userConfig = StringUtil.isBlank(userConfig) ? DbleServer.getInstance().getConfig().getUserConfig() : userConfig;
        String dbConfig = DbleTempConfig.getInstance().getDbConfig();
        dbConfig = StringUtil.isBlank(dbConfig) ? DbleServer.getInstance().getConfig().getDbConfig() : dbConfig;
        String shardingConfig = DbleTempConfig.getInstance().getShardingConfig();
        shardingConfig = StringUtil.isBlank(shardingConfig) ? DbleServer.getInstance().getConfig().getShardingConfig() : shardingConfig;
        String sequenceConfig = DbleTempConfig.getInstance().getSequenceConfig();
        sequenceConfig = StringUtil.isBlank(sequenceConfig) ? DbleServer.getInstance().getConfig().getSequenceConfig() : sequenceConfig;
        boolean reloadResult = reload(loadAllMode, userConfig, dbConfig, shardingConfig, sequenceConfig);
        DbleTempConfig.getInstance().clean();
        //sync json to local
        DbleServer.getInstance().getConfig().syncJsonToLocal(isWriteToLocal);
        return reloadResult;
    }

    private static boolean reload(final int loadAllMode, String userConfig, String dbConfig, String shardingConfig, String sequenceConfig) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.threadTrace("self-reload");
        try {
            /*
             *  1 load new conf
             *  1.1 ConfigInitializer init adn check itself
             *  1.2 ShardingNode/dbGroup test connection
             */
            ReloadLogHelper.info("reload config: load all xml info start", LOGGER);
            ConfigInitializer loader;
            try {
                if (null == userConfig && null == dbConfig && null == shardingConfig && null == sequenceConfig) {
                    loader = new ConfigInitializer();
                } else {
                    loader = new ConfigInitializer(userConfig, dbConfig, shardingConfig, sequenceConfig);
                }
            } catch (Exception e) {
                throw new Exception(e);
            }

            ReloadLogHelper.info("reload config: load all xml info end", LOGGER);
            // compare changes
            List<ChangeItem> changeItemList = differentiateChanges(loader);

            ReloadLogHelper.info("reload config: get variables from random alive dbGroup start", LOGGER);

            //user in use cannot be deleted
            checkUser(changeItemList);
            try {
                //test connection
                if ((loadAllMode & ManagerParseConfig.OPTR_MODE) != 0 && loader.isFullyConfigured()) {
                    loader.testConnection();
                } else {
                    syncShardingNode(loader);
                    loader.testConnection(changeItemList);
                }
            } catch (Exception e) {
                if ((loadAllMode & ManagerParseConfig.OPTS_MODE) == 0 && loader.isFullyConfigured()) {
                    //default/-f/-r
                    throw new Exception(e);
                } else {
                    //-s
                    LOGGER.debug("just test ,not stop reload, catch exception", e);
                }
            }

            return reloadAll(loadAllMode, loader, changeItemList);
        } finally {
            TraceManager.finishSpan(traceObject);
        }
    }

    private static void syncShardingNode(ConfigInitializer loader) {
        Map<String, ShardingNode> oldShardingNodeMap = DbleServer.getInstance().getConfig().getShardingNodes();
        Map<String, ShardingNode> newShardingNodeMap = loader.getShardingNodes();
        for (Map.Entry<String, ShardingNode> shardingNodeEntry : newShardingNodeMap.entrySet()) {
            //sync schema_exists,only testConn can update schema_exists
            if (oldShardingNodeMap.containsKey(shardingNodeEntry.getKey())) {
                shardingNodeEntry.getValue().setSchemaExists(oldShardingNodeMap.get(shardingNodeEntry.getKey()).isSchemaExists());
            }
        }

    }

    private static boolean reloadAll(int loadAllMode, ConfigInitializer loader, List<ChangeItem> changeItemList) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.threadTrace("self-reload");
        try {
            ServerConfig oldConfig = DbleServer.getInstance().getConfig();
            ServerConfig newConfig = new ServerConfig(loader);
            Map<String, PhysicalDbGroup> newDbGroups = newConfig.getDbGroups();
            boolean forceAllReload = false;

            if ((loadAllMode & ManagerParseConfig.OPTR_MODE) != 0) {
                forceAllReload = true;
            }
            SystemVariables newSystemVariables;

            if (forceAllReload) {
                //check version/packetSize/lowerCase
                ConfigUtil.getAndSyncKeyVariables(newDbGroups, true);
                //system variables
                newSystemVariables = getSystemVariablesFromdbGroup(loader, newDbGroups);
            } else {
                //check version/packetSize/lowerCase
                ConfigUtil.getAndSyncKeyVariables(changeItemList, true);
                //random one node
                //system variables
                PhysicalDbInstance physicalDbInstance = getPhysicalDbInstance(loader);
                newSystemVariables = getSystemVariablesFromDbInstance(loader.isFullyConfigured(), physicalDbInstance);
            }
            ReloadLogHelper.info("reload config: get variables from random node end", LOGGER);


            if (forceAllReload) {
                // recycle old active conn
                recycleOldBackendConnections((loadAllMode & ManagerParseConfig.OPTF_MODE) != 0);
            }


            if (loader.isFullyConfigured()) {
                if (newSystemVariables.isLowerCaseTableNames()) {
                    ReloadLogHelper.info("reload config: dbGroup's lowerCaseTableNames=1, lower the config properties start", LOGGER);
                    newConfig.reviseLowerCase(loader.getSequenceConfig());
                    ReloadLogHelper.info("reload config: dbGroup's lowerCaseTableNames=1, lower the config properties end", LOGGER);
                } else {
                    newConfig.loadSequence(loader.getSequenceConfig());
                    newConfig.selfChecking0();
                }
            }

            Map<UserName, UserConfig> newUsers = newConfig.getUsers();
            Map<String, SchemaConfig> newSchemas = newConfig.getSchemas();
            Map<String, ShardingNode> newShardingNodes = newConfig.getShardingNodes();
            Map<ERTable, Set<ERTable>> newErRelations = newConfig.getErRelations();
            Map<String, Properties> newBlacklistConfig = newConfig.getBlacklistConfig();
            Map<String, AbstractPartitionAlgorithm> newFunctions = newConfig.getFunctions();


            /* 2.2 init the lDbInstance with diff*/
            /* 2.3 apply new conf */
            ReloadLogHelper.info("reload config: apply new config start", LOGGER);
            boolean result;
            try {
                if (forceAllReload) {
                    result = oldConfig.reload(newUsers, newSchemas, newShardingNodes, newDbGroups, oldConfig.getDbGroups(), newErRelations,
                            newSystemVariables, loader.isFullyConfigured(), loadAllMode, newBlacklistConfig, newFunctions,
                            loader.getUserConfig(), loader.getSequenceConfig(), loader.getShardingConfig(), loader.getDbConfig(), changeItemList);
                } else {
                    //replace dbGroup reference
                    for (Map.Entry<String, ShardingNode> shardingNodeEntry : newShardingNodes.entrySet()) {
                        ShardingNode shardingNode = shardingNodeEntry.getValue();
                        PhysicalDbGroup oldDbGroup = oldConfig.getDbGroups().get(shardingNode.getDbGroupName());
                        if (null == oldDbGroup) {
                            oldDbGroup = newDbGroups.get(shardingNode.getDbGroupName());
                        }
                        shardingNode.setDbGroup(oldDbGroup);
                    }
                    result = oldConfig.reload(newUsers, newSchemas, newShardingNodes, oldConfig.getDbGroups(), null, newErRelations,
                            newSystemVariables, loader.isFullyConfigured(), loadAllMode, newBlacklistConfig, newFunctions,
                            loader.getUserConfig(), loader.getSequenceConfig(), loader.getShardingConfig(), loader.getDbConfig(), changeItemList);
                }
                CronScheduler.getInstance().init(oldConfig.getSchemas());
                if (!result) {
                    initFailed(newDbGroups);
                }
                FrontendUserManager.getInstance().changeUser(changeItemList, SystemConfig.getInstance().getMaxCon());
                ReloadLogHelper.info("reload config: apply new config end", LOGGER);
                if (!forceAllReload) {
                    // recycle old active conn
                    recycleOldBackendConnections((loadAllMode & ManagerParseConfig.OPTF_MODE) != 0);
                }
                if (!loader.isFullyConfigured()) {
                    recycleServerConnections();
                }
                return result;
            } catch (Exception e) {
                initFailed(newDbGroups);
                throw e;
            }
        } finally {
            TraceManager.finishSpan(traceObject);
        }
    }

    private static void checkUser(List<ChangeItem> changeItemList) {
        for (ChangeItem changeItem : changeItemList) {
            int type = changeItem.getType();
            Object item = changeItem.getItem();
            if (type == 3 && item instanceof UserName) {
                //check is it in use
                Integer count = FrontendUserManager.getInstance().getUserConnectionMap().get(item);
                if (null != count && count > 0) {
                    throw new ConfigException("user['" + item.toString() + "'] is being used.");
                }
            } else if (type == 2 && changeItem.isAffectEntryDbGroup() && item instanceof UserName) {
                //check is it in use
                Integer count = FrontendUserManager.getInstance().getUserConnectionMap().get(item);
                if (null != count && count > 0) {
                    throw new ConfigException("user['" + item.toString() + "'] is being used.");
                }
            }
        }
    }

    private static List<ChangeItem> differentiateChanges(ConfigInitializer newLoader) {
        List<ChangeItem> changeItemList = Lists.newArrayList();
        //old
        ServerConfig oldServerConfig = DbleServer.getInstance().getConfig();
        Map<UserName, UserConfig> oldUserMap = oldServerConfig.getUsers();
        Map<String, PhysicalDbGroup> oldDbGroupMap = oldServerConfig.getDbGroups();
        //new
        Map<UserName, UserConfig> newUserMap = newLoader.getUsers();
        MapDifference<UserName, UserConfig> userMapDifference = Maps.difference(newUserMap, oldUserMap);
        //delete
        userMapDifference.entriesOnlyOnRight().keySet().stream().map(userConfig -> new ChangeItem(3, userConfig)).forEach(changeItemList::add);
        //add
        userMapDifference.entriesOnlyOnLeft().keySet().stream().map(userConfig -> new ChangeItem(1, userConfig)).forEach(changeItemList::add);
        //update
        userMapDifference.entriesDiffering().entrySet().stream().map(differenceEntry -> {
            UserConfig newUserConfig = differenceEntry.getValue().leftValue();
            UserConfig oldUserConfig = differenceEntry.getValue().rightValue();
            ChangeItem changeItem = new ChangeItem(2, differenceEntry.getKey());
            if (newUserConfig instanceof RwSplitUserConfig && oldUserConfig instanceof RwSplitUserConfig) {
                if (!((RwSplitUserConfig) newUserConfig).getDbGroup().equals(((RwSplitUserConfig) oldUserConfig).getDbGroup())) {
                    changeItem.setAffectEntryDbGroup(true);
                }
            }
            return changeItem;
        }).forEach(changeItemList::add);

        //dbGroup
        Map<String, PhysicalDbGroup> newDbGroupMap = newLoader.getDbGroups();
        Map<String, PhysicalDbGroup> removeDbGroup = new LinkedHashMap<>(oldDbGroupMap);
        for (Map.Entry<String, PhysicalDbGroup> newDbGroupEntry : newDbGroupMap.entrySet()) {
            PhysicalDbGroup oldDbGroup = oldDbGroupMap.get(newDbGroupEntry.getKey());
            PhysicalDbGroup newDbGroup = newDbGroupEntry.getValue();

            if (null == oldDbGroup) {
                //add dbGroup
                changeItemList.add(new ChangeItem(1, newDbGroup));
            } else {
                removeDbGroup.remove(newDbGroupEntry.getKey());
                //change dbGroup
                if (!newDbGroup.equalsBaseInfo(oldDbGroup)) {
                    ChangeItem changeItem = new ChangeItem(2, newDbGroup);
                    if (!newDbGroup.equalsForConnectionPool(oldDbGroup)) {
                        changeItem.setAffectConnectionPool(true);
                    }
                    if (!newDbGroup.equalsForHeartbeat(oldDbGroup)) {
                        changeItem.setAffectHeartbeat(true);
                    }
                    changeItemList.add(changeItem);
                }

                //dbInstance
                Map<String, PhysicalDbInstance> newDbInstanceMap = newDbGroup.getAllDbInstanceMap();
                Map<String, PhysicalDbInstance> oldDbInstanceMap = oldDbGroup.getAllDbInstanceMap();

                MapDifference<String, PhysicalDbInstance> dbInstanceMapDifference = Maps.difference(newDbInstanceMap, oldDbInstanceMap);
                //delete
                dbInstanceMapDifference.entriesOnlyOnRight().values().stream().map(dbInstance -> new ChangeItem(3, dbInstance)).forEach(changeItemList::add);
                //add
                dbInstanceMapDifference.entriesOnlyOnLeft().values().stream().map(dbInstance -> new ChangeItem(1, dbInstance)).forEach(changeItemList::add);
                //update
                dbInstanceMapDifference.entriesDiffering().values().stream().map(physicalDbInstanceValueDifference -> {
                    PhysicalDbInstance newDbInstance = physicalDbInstanceValueDifference.leftValue();
                    PhysicalDbInstance oldDbInstance = physicalDbInstanceValueDifference.rightValue();
                    ChangeItem changeItem = new ChangeItem(2, newDbInstance);
                    if (!newDbInstance.equalsForConnectionPool(oldDbInstance)) {
                        changeItem.setAffectConnectionPool(true);
                    }
                    if (!newDbInstance.equalsForPoolCapacity(oldDbInstance)) {
                        changeItem.setAffectPoolCapacity(true);
                    }
                    if (!newDbInstance.equalsForHeartbeat(oldDbInstance)) {
                        changeItem.setAffectHeartbeat(true);
                    }
                    if (!newDbInstance.equalsForTestConn(oldDbInstance)) {
                        changeItem.setAffectTestConn(true);
                    } else {
                        newDbInstance.setTestConnSuccess(oldDbInstance.isTestConnSuccess());
                    }
                    return changeItem;
                }).forEach(changeItemList::add);
                //testConnSuccess with both
                for (Map.Entry<String, PhysicalDbInstance> dbInstanceEntry : dbInstanceMapDifference.entriesInCommon().entrySet()) {
                    dbInstanceEntry.getValue().setTestConnSuccess(oldDbInstanceMap.get(dbInstanceEntry.getKey()).isTestConnSuccess());
                }

            }
        }
        removeDbGroup.forEach((key, value) -> changeItemList.add(new ChangeItem(3, value)));
        return changeItemList;
    }

    private static PhysicalDbInstance getPhysicalDbInstance(ConfigInitializer loader) {
        PhysicalDbInstance ds = null;
        for (PhysicalDbGroup dbGroup : loader.getDbGroups().values()) {
            PhysicalDbInstance dsTest = dbGroup.getWriteDbInstance();
            if (dsTest.isTestConnSuccess()) {
                ds = dsTest;
            }
            if (ds != null) {
                break;
            }
        }
        if (ds == null) {
            for (PhysicalDbGroup dbGroup : loader.getDbGroups().values()) {
                for (PhysicalDbInstance dsTest : dbGroup.getDbInstances(false)) {
                    if (dsTest.isTestConnSuccess()) {
                        ds = dsTest;
                        break;
                    }
                }
                if (ds != null) {
                    break;
                }
            }
        }
        return ds;
    }

    private static void recycleOldBackendConnections(boolean closeFrontCon) {
        if (closeFrontCon) {
            for (IOProcessor processor : DbleServer.getInstance().getBackendProcessors()) {
                for (BackendConnection con : processor.getBackends().values()) {
                    if (con.getPoolDestroyedTime() != 0) {
                        con.closeWithFront("old active backend conn will be forced closed by closing front conn");
                    }
                }
            }
        }
    }


    private static void initFailed(Map<String, PhysicalDbGroup> newDbGroups) {
        // INIT FAILED
        ReloadLogHelper.info("reload failed, clear previously created dbInstances ", LOGGER);
        for (PhysicalDbGroup dbGroup : newDbGroups.values()) {
            dbGroup.stop("reload fail, stop");
        }
    }

    private static SystemVariables getSystemVariablesFromDbInstance(boolean fullyConfigured, PhysicalDbInstance physicalDbInstance) throws Exception {
        VarsExtractorHandler handler = new VarsExtractorHandler(physicalDbInstance);
        SystemVariables newSystemVariables;
        newSystemVariables = handler.execute();
        if (newSystemVariables == null) {
            if (fullyConfigured) {
                throw new Exception("Can't get variables from any dbInstance, because all of dbGroup can't connect to MySQL correctly");
            } else {
                ReloadLogHelper.info("reload config: no valid dbGroup ,keep variables as old", LOGGER);
                newSystemVariables = DbleServer.getInstance().getSystemVariables();
            }
        }
        return newSystemVariables;
    }

    private static SystemVariables getSystemVariablesFromdbGroup(ConfigInitializer loader, Map<String, PhysicalDbGroup> newDbGroups) throws Exception {
        VarsExtractorHandler handler = new VarsExtractorHandler(newDbGroups);
        SystemVariables newSystemVariables;
        newSystemVariables = handler.execute();
        if (newSystemVariables == null) {
            if (loader.isFullyConfigured()) {
                throw new Exception("Can't get variables from any dbInstance, because all of dbGroup can't connect to MySQL correctly");
            } else {
                ReloadLogHelper.info("reload config: no valid dbGroup ,keep variables as old", LOGGER);
                newSystemVariables = DbleServer.getInstance().getSystemVariables();
            }
        }
        return newSystemVariables;
    }

    private static void recycleServerConnections() {
        TraceManager.TraceObject traceObject = TraceManager.threadTrace("recycle-sharding-connections");
        try {
            for (IOProcessor processor : DbleServer.getInstance().getFrontProcessors()) {
                for (FrontendConnection fcon : processor.getFrontends().values()) {
                    if (!fcon.isManager()) {
                        fcon.close("Reload causes the service to stop");
                    }
                }
            }
        } finally {
            TraceManager.finishSpan(traceObject);
        }
    }

    public static class ChangeItem {
        //1:add  2:update  3:delete
        private int type;
        private Object item;
        private boolean affectHeartbeat;
        private boolean affectConnectionPool;
        private boolean affectTestConn;
        private boolean affectEntryDbGroup;
        //connection pool capacity
        private boolean affectPoolCapacity;

        public ChangeItem(int type, Object item) {
            this.type = type;
            this.item = item;
        }

        public boolean isAffectHeartbeat() {
            return affectHeartbeat;
        }

        public void setAffectHeartbeat(boolean affectHeartbeat) {
            this.affectHeartbeat = affectHeartbeat;
        }

        public boolean isAffectConnectionPool() {
            return affectConnectionPool;
        }

        public void setAffectConnectionPool(boolean affectConnectionPool) {
            this.affectConnectionPool = affectConnectionPool;
        }

        public boolean isAffectPoolCapacity() {
            return affectPoolCapacity;
        }

        public void setAffectPoolCapacity(boolean affectPoolCapacity) {
            this.affectPoolCapacity = affectPoolCapacity;
        }

        public boolean isAffectTestConn() {
            return affectTestConn;
        }

        public void setAffectTestConn(boolean affectTestConn) {
            this.affectTestConn = affectTestConn;
        }

        public boolean isAffectEntryDbGroup() {
            return affectEntryDbGroup;
        }

        public void setAffectEntryDbGroup(boolean affectEntryDbGroup) {
            this.affectEntryDbGroup = affectEntryDbGroup;
        }

        public int getType() {
            return type;
        }

        public void setType(int type) {
            this.type = type;
        }

        public Object getItem() {
            return item;
        }

        public void setItem(Object item) {
            this.item = item;
        }
    }
}
