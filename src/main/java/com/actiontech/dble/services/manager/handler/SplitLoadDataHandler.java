package com.actiontech.dble.services.manager.handler;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.config.model.sharding.table.BaseTableConfig;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.services.manager.ManagerService;
import com.actiontech.dble.services.manager.dump.ErrorMsg;
import com.actiontech.dble.services.manager.response.DumpFileError;
import com.actiontech.dble.services.manager.split.loaddata.DumpFileHandler;
import com.actiontech.dble.services.manager.split.loaddata.DumpFileReader;
import com.actiontech.dble.services.manager.split.loaddata.ShardingNodeWriter;
import com.actiontech.dble.util.ExecutorUtil;
import com.actiontech.dble.util.NameableExecutor;
import com.actiontech.dble.util.StringUtil;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SplitLoadDataHandler {
    private static final Pattern SPLIT_STMT = Pattern.compile("([^\\s]+)\\s+([^\\s]+)(((\\s*(-s([^\\s]+))?)|(\\s+(-t([^\\s]+))?)|(\\s+(-fs(\\d+))?)|(\\s+(-ds(\\d+))?)|(\\s+(-et(\\d+))?))+)", Pattern.CASE_INSENSITIVE);
    public static final Logger LOGGER = LoggerFactory.getLogger("dumpFileLog");

    private SplitLoadDataHandler() {
    }


    public static void handle(String stmt, ManagerService service, int offset) {
        Config config = parseOption(stmt.substring(offset).trim());
        if (config == null) {
            LOGGER.info("split load data syntax is error.");
            service.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "You have an error in your SQL syntax");
            return;
        }
        if (StringUtil.isBlank(config.getSourcePath()) || StringUtil.isBlank(config.getTargetPath()) || StringUtil.isBlank(config.getSchemaName()) || StringUtil.isBlank(config.getTableName())) {
            LOGGER.info("split load data syntax is error.");
            service.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "You have an error in your SQL syntax");
            return;
        }
        BaseTableConfig tableConfig = DbleServer.getInstance().getConfig().getSchemas().get(config.getSchemaName()).getTables().get(config.getTableName());
        if (null == tableConfig) {
            LOGGER.info("schema `{}` table `{}` configuration does not exist.", config.getSchemaName(), config.getTableName());
            service.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "schema `{" + config.getSchemaName() + "}` table `{" + config.getTableName() + "}` configuration does not exist.");
            return;
        }
        config.setTableConfig(tableConfig);

        List<ErrorMsg> errorMsgList = Lists.newCopyOnWriteArrayList();
        AtomicBoolean errorFlag = new AtomicBoolean();
        AtomicInteger nodeCount = new AtomicInteger(-1);

        Map<String, ShardingNodeWriter> writerMap = new ConcurrentHashMap<>();
        NameableExecutor fileReadExecutor = ExecutorUtil.createFixed("Split_Reader", 1);
        NameableExecutor fileHandlerExecutor = ExecutorUtil.createFixed("Split_Handler", 1);


        //randomAccessFile + buffer
        BlockingQueue<String> handleQueue = new ArrayBlockingQueue<>(512);
        DumpFileReader reader = new DumpFileReader(handleQueue, errorMsgList, errorFlag);
        DumpFileHandler fileHandler = new DumpFileHandler(handleQueue, config, nodeCount, writerMap, errorMsgList, errorFlag);
        // start read
        try {
            reader.open(config.getSourcePath(), config.getFileBufferSize());
            fileHandler.open();

            fileReadExecutor.execute(reader);
            fileHandlerExecutor.execute(fileHandler);
        } catch (IOException e) {
            LOGGER.info("finish to split dump file because " + e.getMessage());
            service.writeErrMessage(ErrorCode.ER_IO_EXCEPTION, e.getMessage());
            return;
        }


        while (!errorFlag.get() && nodeCount.get() != 0) {
            LockSupport.parkNanos(1000);
        }

        //recycleThread
        fileReadExecutor.shutdownNow();
        fileHandlerExecutor.shutdownNow();
        fileHandler.shutDownExecutor();
        for (ShardingNodeWriter shardingNodeWriter : writerMap.values()) {
            shardingNodeWriter.shutdown();
        }
        writerMap.clear();

        //handle error
        if (errorFlag.get()) {
            DumpFileError.execute(service, errorMsgList);
            return;
        }

        OkPacket packet = new OkPacket();
        packet.setServerStatus(2);
        packet.setPacketId(1);
        packet.write(service.getConnection());
    }


    private static Config parseOption(String stmt) {
        Matcher m = SPLIT_STMT.matcher(stmt);
        if (m.matches()) {
            Config config = new Config();
            config.setSourcePath(m.group(1));
            config.setTargetPath(m.group(2));
            config.setSchemaName(m.group(7));
            config.setTableName(m.group(10));
            if (null != m.group(13)) {
                config.setFileBufferSize(Integer.parseInt(m.group(13)));
            }
            if (null != m.group(16)) {
                config.setDisruptorBufferSize(Integer.parseInt(m.group(16)));
            }
            if (null != m.group(19)) {
                config.setExecutorCount(Integer.parseInt(m.group(19)));
            }
            return config;
        }
        return null;
    }

    public static class Config {
        private String sourcePath;
        private String targetPath;
        private String schemaName;
        private String tableName;
        private BaseTableConfig tableConfig;
        private int fileBufferSize = 1048576;
        private int disruptorBufferSize = 512;
        private int executorCount = 2;

        public Config() {
        }

        public void setSourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
        }

        public void setTargetPath(String targetPath) {
            this.targetPath = targetPath;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public void setTableConfig(BaseTableConfig tableConfig) {
            this.tableConfig = tableConfig;
        }

        public int getFileBufferSize() {
            return fileBufferSize;
        }

        public void setFileBufferSize(int fileBufferSize) {
            this.fileBufferSize = fileBufferSize;
        }

        public int getDisruptorBufferSize() {
            return disruptorBufferSize;
        }

        public void setDisruptorBufferSize(int disruptorBufferSize) {
            this.disruptorBufferSize = disruptorBufferSize;
        }

        public int getExecutorCount() {
            return executorCount;
        }

        public void setExecutorCount(int executorCount) {
            this.executorCount = executorCount;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public String getTableName() {
            return tableName;
        }

        public BaseTableConfig getTableConfig() {
            return tableConfig;
        }
    }


}

