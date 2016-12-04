/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.backup.impl;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupInfo;
import org.apache.hadoop.hbase.backup.BackupRequest;
import org.apache.hadoop.hbase.backup.BackupRestoreConstants;
import org.apache.hadoop.hbase.backup.BackupType;
import org.apache.hadoop.hbase.backup.util.BackupClientUtil;
import org.apache.hadoop.hbase.backup.util.BackupSet;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;

import com.google.common.collect.Lists;

/**
 * General backup commands, options and usage messages
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class BackupCommands implements BackupRestoreConstants {

  public final static String INCORRECT_USAGE = "Incorrect usage";

  public static final String USAGE = "Usage: bin/hbase backup COMMAND [command-specific arguments]\n"
      + "where COMMAND is one of:\n"
      + "  create          Create a new backup image\n"
      + "  delete          Delete an existing backup image\n"
      + "  describe        Show detailed information of a backup image\n"
      + "  history         Show history of all successful backups\n"
      + "  progress        Show the progress of the latest backup request\n"
      + "  set             Backup set management\n"
      + "Run \'bin/hbase backup COMMAND -h\' to see help message for each command\n";

  public static final String CREATE_CMD_USAGE =
      "Usage: bin/hbase backup create <type> <backup_path> [tables] [options]\n"
      + "  type           \"full\" to create a full backup image\n"
      + "                 \"incremental\" to create an incremental backup image\n"
      + "  backup_path     Full path to store the backup image\n"
      + "  tables          If no tables (\"\") are specified, all tables are backed up.\n"
      + "                  otherwise it is a comma separated list of tables.";


  public static final String PROGRESS_CMD_USAGE = "Usage: bin/hbase backup progress <backup_id>\n"
      + "  backup_id       Backup image id\n";
  public static final String NO_INFO_FOUND = "No info was found for backup id: ";

  public static final String DESCRIBE_CMD_USAGE = "Usage: bin/hbase backup describe <backup_id>\n"
      + "  backup_id       Backup image id\n";

  public static final String HISTORY_CMD_USAGE =
      "Usage: bin/hbase backup history [options]";



  public static final String DELETE_CMD_USAGE = "Usage: bin/hbase backup delete <backup_id>\n"
      + "  backup_id       Backup image id\n";

  public static final String CANCEL_CMD_USAGE = "Usage: bin/hbase backup cancel <backup_id>\n"
      + "  backup_id       Backup image id\n";

  public static final String SET_CMD_USAGE = "Usage: bin/hbase backup set COMMAND [name] [tables]\n"
      + "  name            Backup set name\n"
      + "  tables          If no tables (\"\") are specified, all tables will belong to the set.\n"
      + "                  Otherwise it is a comma separated list of tables.\n"
      + "COMMAND is one of:\n"
      + "  add             Add tables to a set, create a set if needed\n"
      + "  remove          Remove tables from a set\n"
      + "  list            List all backup sets in the system\n"
      + "  describe        Describe backup set\n"
      + "  delete          Delete backup set\n";

  public static final String USAGE_FOOTER = "";

  public static abstract class Command extends Configured {
    CommandLine cmdline;

    Command(Configuration conf) {
      super(conf);
    }

    public void execute() throws IOException
    {
      if (cmdline.hasOption("h") || cmdline.hasOption("help")) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
    }

    protected abstract void printUsage();
  }

  private BackupCommands() {
    throw new AssertionError("Instantiating utility class...");
  }

  public static Command createCommand(Configuration conf, BackupCommand type, CommandLine cmdline) {
    Command cmd = null;
    switch (type) {
    case CREATE:
      cmd = new CreateCommand(conf, cmdline);
      break;
    case DESCRIBE:
      cmd = new DescribeCommand(conf, cmdline);
      break;
    case PROGRESS:
      cmd = new ProgressCommand(conf, cmdline);
      break;
    case DELETE:
      cmd = new DeleteCommand(conf, cmdline);
      break;
    case CANCEL:
      cmd = new CancelCommand(conf, cmdline);
      break;
    case HISTORY:
      cmd = new HistoryCommand(conf, cmdline);
      break;
    case SET:
      cmd = new BackupSetCommand(conf, cmdline);
      break;
    case HELP:
    default:
      cmd = new HelpCommand(conf, cmdline);
      break;
    }
    return cmd;
  }

  static int numOfArgs(String[] args) {
    if (args == null) return 0;
    return args.length;
  }

  public static class CreateCommand extends Command {

    CreateCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      if (cmdline == null || cmdline.getArgs() == null) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      String[] args = cmdline.getArgs();
      if (args.length < 3 || args.length > 4) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      if (!BackupType.FULL.toString().equalsIgnoreCase(args[1])
          && !BackupType.INCREMENTAL.toString().equalsIgnoreCase(args[1])) {
        System.out.println("ERROR: invalid backup type: "+ args[1]);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      if(!verifyPath(args[2])) {
        System.out.println("ERROR: invalid backup destination: "+ args[2]);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String tables = null;
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();

      // Check if we have both: backup set and list of tables
      if(args.length == 4 && cmdline.hasOption(OPTION_SET)) {
        System.out.println("ERROR: You can specify either backup set or list"+
            " of tables, but not both");
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      // Check backup set
      String setName = null;
      if (cmdline.hasOption(OPTION_SET)) {
        setName = cmdline.getOptionValue(OPTION_SET);
        tables = getTablesForSet(setName, conf);

        if (tables == null) {
          System.out.println("ERROR: Backup set '" + setName+ "' is either empty or does not exist");
          printUsage();
          throw new IOException(INCORRECT_USAGE);
        }
      } else {
        tables = (args.length == 4) ? args[3] : null;
      }
      int bandwidth = cmdline.hasOption(OPTION_BANDWIDTH) ?
          Integer.parseInt(cmdline.getOptionValue(OPTION_BANDWIDTH)) : -1;
      int workers = cmdline.hasOption(OPTION_WORKERS) ?
          Integer.parseInt(cmdline.getOptionValue(OPTION_WORKERS)) : -1;

      try (Connection conn = ConnectionFactory.createConnection(getConf());
          HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);) {
        BackupRequest request = new BackupRequest();
        request.setBackupType(BackupType.valueOf(args[1].toUpperCase()))
        .setTableList(tables != null?Lists.newArrayList(BackupClientUtil.parseTableNames(tables)): null)
        .setTargetRootDir(args[2]).setWorkers(workers).setBandwidth(bandwidth)
        .setBackupSetName(setName);

        String backupId = admin.backupTables(request);
        System.out.println("Backup session "+ backupId+" finished. Status: SUCCESS");
      } catch (IOException e) {
        System.out.println("Backup session finished. Status: FAILURE");
        throw e;
      }
    }

    private boolean verifyPath(String path) {
      try{
        Path p = new Path(path);
        Configuration conf = getConf() != null? getConf():
            HBaseConfiguration.create();
        URI uri = p.toUri();
        if(uri.getScheme() == null) return false;
        FileSystem fs = FileSystem.get(uri, conf);
        return true;
      } catch(Exception e){
        return false;
      }
    }

    private String getTablesForSet(String name, Configuration conf)
        throws IOException {
      try (final Connection conn = ConnectionFactory.createConnection(conf);
          final BackupSystemTable table = new BackupSystemTable(conn)) {
        List<TableName> tables = table.describeBackupSet(name);
        if (tables == null) return null;
        return StringUtils.join(tables, BackupRestoreConstants.TABLENAME_DELIMITER_IN_COMMAND);
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(CREATE_CMD_USAGE);
      Options options = new Options();
      options.addOption(OPTION_WORKERS, true, OPTION_WORKERS_DESC);
      options.addOption(OPTION_BANDWIDTH, true, OPTION_BANDWIDTH_DESC);
      options.addOption(OPTION_SET, true, OPTION_SET_RESTORE_DESC);

      HelpFormatter helpFormatter = new HelpFormatter();
      helpFormatter.setLeftPadding(2);
      helpFormatter.setDescPadding(8);
      helpFormatter.setWidth(100);
      helpFormatter.setSyntaxPrefix("Options:");
      helpFormatter.printHelp(" ", null, options, USAGE_FOOTER);

    }
  }

  private static class HelpCommand extends Command {

    HelpCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      if (cmdline == null) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String[] args = cmdline.getArgs();
      if (args == null || args.length == 0) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      if (args.length != 2) {
        System.out.println("ERROR: Only supports help message of a single command type");
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String type = args[1];

      if (BackupCommand.CREATE.name().equalsIgnoreCase(type)) {
        System.out.println(CREATE_CMD_USAGE);
      } else if (BackupCommand.DESCRIBE.name().equalsIgnoreCase(type)) {
        System.out.println(DESCRIBE_CMD_USAGE);
      } else if (BackupCommand.HISTORY.name().equalsIgnoreCase(type)) {
        System.out.println(HISTORY_CMD_USAGE);
      } else if (BackupCommand.PROGRESS.name().equalsIgnoreCase(type)) {
        System.out.println(PROGRESS_CMD_USAGE);
      } else if (BackupCommand.DELETE.name().equalsIgnoreCase(type)) {
        System.out.println(DELETE_CMD_USAGE);
      } else if (BackupCommand.CANCEL.name().equalsIgnoreCase(type)) {
        System.out.println(CANCEL_CMD_USAGE);
      } else if (BackupCommand.SET.name().equalsIgnoreCase(type)) {
        System.out.println(SET_CMD_USAGE);
      } else {
        System.out.println("Unknown command : " + type);
        printUsage();
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(USAGE);
    }
  }

  private static class DescribeCommand extends Command {

    DescribeCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      if (cmdline == null || cmdline.getArgs() == null) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      String[] args = cmdline.getArgs();
      if (args.length != 2) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String backupId = args[1];
      Configuration conf = getConf() != null ? getConf() : HBaseConfiguration.create();
      try (final Connection conn = ConnectionFactory.createConnection(conf);
          final BackupSystemTable sysTable = new BackupSystemTable(conn);) {
        BackupInfo info = sysTable.readBackupInfo(backupId);
        if (info == null) {
          System.out.println("ERROR: " + backupId + " does not exist");
          printUsage();
          throw new IOException(INCORRECT_USAGE);
        }
        System.out.println(info.getShortDescription());
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(DESCRIBE_CMD_USAGE);
    }
  }

  private static class ProgressCommand extends Command {

    ProgressCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();

      if (cmdline == null || cmdline.getArgs() == null ||
          cmdline.getArgs().length == 1) {
        System.err.println("No backup id was specified, "
            + "will retrieve the most recent (ongoing) sessions");
      }
      String[] args = cmdline == null ? null : cmdline.getArgs();
      if (args != null && args.length > 2) {
        System.err.println("ERROR: wrong number of arguments: " + args.length);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String backupId = (args == null || args.length <= 1) ? null : args[1];
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          final BackupSystemTable sysTable = new BackupSystemTable(conn);){
        BackupInfo info = sysTable.readBackupInfo(backupId);
        int progress = info == null? -1: info.getProgress();
        if(progress < 0){
          System.out.println(NO_INFO_FOUND + backupId);
        } else{
          System.out.println(backupId+" progress=" + progress+"%");
        }
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(PROGRESS_CMD_USAGE);
    }
  }

  private static class DeleteCommand extends Command {

    DeleteCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      if (cmdline == null || cmdline.getArgs() == null || cmdline.getArgs().length < 2) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String[] args = cmdline.getArgs();

      String[] backupIds = new String[args.length - 1];
      System.arraycopy(args, 1, backupIds, 0, backupIds.length);
      Configuration conf = getConf() != null ? getConf() : HBaseConfiguration.create();
      try (final Connection conn = ConnectionFactory.createConnection(conf);
          HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);) {
        int deleted = admin.deleteBackups(args);
        System.out.println("Deleted " + deleted + " backups. Total requested: " + args.length);
      }

    }

    @Override
    protected void printUsage() {
      System.out.println(DELETE_CMD_USAGE);
    }
  }

// TODO Cancel command

  private static class CancelCommand extends Command {

    CancelCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      if (cmdline == null || cmdline.getArgs() == null || cmdline.getArgs().length < 2) {
        System.out.println("No backup id(s) was specified, will use the most recent one");
      }
      Configuration conf = getConf() != null ? getConf() : HBaseConfiguration.create();
      try (final Connection conn = ConnectionFactory.createConnection(conf);
          HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);) {
        // TODO cancel backup
      }
    }

    @Override
    protected void printUsage() {
    }
  }

  private static class HistoryCommand extends Command {

    private final static int DEFAULT_HISTORY_LENGTH = 10;

    HistoryCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {

      super.execute();

      int n = parseHistoryLength();
      final TableName tableName = getTableName();
      final String setName = getTableSetName();
      BackupInfo.Filter tableNameFilter = new BackupInfo.Filter() {
        @Override
        public boolean apply(BackupInfo info) {
          if (tableName == null) return true;
          List<TableName> names = info.getTableNames();
          return names.contains(tableName);
        }
      };
      BackupInfo.Filter tableSetFilter = new BackupInfo.Filter() {
        @Override
        public boolean apply(BackupInfo info) {
          if (setName == null) return true;
          String backupId = info.getBackupId();
          return backupId.startsWith(setName);
        }
      };
      Path backupRootPath = getBackupRootPath();
      List<BackupInfo> history = null;
      Configuration conf = getConf() != null ? getConf() : HBaseConfiguration.create();
      if (backupRootPath == null) {
        // Load from hbase:backup
        try (final Connection conn = ConnectionFactory.createConnection(conf);
             final BackupSystemTable sysTable = new BackupSystemTable(conn);) {

          history = sysTable.getBackupHistory(n, tableNameFilter, tableSetFilter);
        }
      } else {
        // load from backup FS
        history = BackupClientUtil.getHistory(conf, n, backupRootPath,
          tableNameFilter, tableSetFilter);
      }
      for (BackupInfo info : history) {
        System.out.println(info.getShortDescription());
      }
    }

    private Path getBackupRootPath() throws IOException {
      String value = null;
      try{
        value = cmdline.getOptionValue(OPTION_PATH);
        if (value == null) return null;
        return new Path(value);
      } catch (IllegalArgumentException e) {
        System.out.println("ERROR: Illegal argument for backup root path: "+ value);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
    }

    private TableName getTableName() throws IOException {
      String value = cmdline.getOptionValue(OPTION_TABLE);
      if (value == null) return null;
      try{
        return TableName.valueOf(value);
      } catch (IllegalArgumentException e){
        System.out.println("Illegal argument for table name: "+ value);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
    }

    private String getTableSetName() throws IOException {
      String value = cmdline.getOptionValue(OPTION_SET);
      return value;
    }

    private int parseHistoryLength() throws IOException {
      String value = cmdline.getOptionValue(OPTION_RECORD_NUMBER);
      try{
        if (value == null) return DEFAULT_HISTORY_LENGTH;
        return Integer.parseInt(value);
      } catch(NumberFormatException e) {
        System.out.println("Illegal argument for history length: "+ value);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(HISTORY_CMD_USAGE);
      Options options = new Options();
      options.addOption(OPTION_RECORD_NUMBER, true, OPTION_RECORD_NUMBER_DESC);
      options.addOption(OPTION_PATH, true, OPTION_PATH_DESC);
      options.addOption(OPTION_TABLE, true, OPTION_TABLE_DESC);
      options.addOption(OPTION_SET, true, OPTION_SET_DESC);

      HelpFormatter helpFormatter = new HelpFormatter();
      helpFormatter.setLeftPadding(2);
      helpFormatter.setDescPadding(8);
      helpFormatter.setWidth(100);
      helpFormatter.setSyntaxPrefix("Options:");
      helpFormatter.printHelp(" ", null, options, USAGE_FOOTER);
    }
  }

  private static class BackupSetCommand extends Command {
    private final static String SET_ADD_CMD = "add";
    private final static String SET_REMOVE_CMD = "remove";
    private final static String SET_DELETE_CMD = "delete";
    private final static String SET_DESCRIBE_CMD = "describe";
    private final static String SET_LIST_CMD = "list";

    BackupSetCommand(Configuration conf, CommandLine cmdline) {
      super(conf);
      this.cmdline = cmdline;
    }

    @Override
    public void execute() throws IOException {
      super.execute();
      // Command-line must have at least one element
      if (cmdline == null || cmdline.getArgs() == null || cmdline.getArgs().length < 2) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String[] args = cmdline.getArgs();
      String cmdStr = args[1];
      BackupCommand cmd = getCommand(cmdStr);

      switch (cmd) {
      case SET_ADD:
        processSetAdd(args);
        break;
      case SET_REMOVE:
        processSetRemove(args);
        break;
      case SET_DELETE:
        processSetDelete(args);
        break;
      case SET_DESCRIBE:
        processSetDescribe(args);
        break;
      case SET_LIST:
        processSetList(args);
        break;
      default:
        break;

      }
    }

    private void processSetList(String[] args) throws IOException {
      // List all backup set names
      // does not expect any args
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);){
        List<BackupSet> list = admin.listBackupSets();
        for(BackupSet bs: list){
          System.out.println(bs);
        }
      }
    }

    private void processSetDescribe(String[] args) throws IOException {
      if (args == null || args.length != 3) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      String setName = args[2];
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          final BackupSystemTable sysTable = new BackupSystemTable(conn);){
        List<TableName> tables = sysTable.describeBackupSet(setName);
        BackupSet set = tables == null? null : new BackupSet(setName, tables);
        if(set == null) {
          System.out.println("Set '"+setName+"' does not exist.");
        } else{
          System.out.println(set);
        }
      }
    }

    private void processSetDelete(String[] args) throws IOException {
      if (args == null || args.length != 3) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      String setName = args[2];
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          final HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);){
        boolean result = admin.deleteBackupSet(setName);
        if(result){
          System.out.println("Delete set "+setName+" OK.");
        } else{
          System.out.println("Set "+setName+" does not exist");
        }
      }
    }

    private void processSetRemove(String[] args) throws IOException {
      if (args == null || args.length != 4) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }

      String setName = args[2];
      String[] tables = args[3].split(",");
      Configuration conf = getConf() != null? getConf(): HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          final HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);){
        admin.removeFromBackupSet(setName, tables);
      }
    }

    private void processSetAdd(String[] args) throws IOException {
      if (args == null || args.length != 4) {
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
      String setName = args[2];
      String[] tables = args[3].split(",");
      TableName[] tableNames = new TableName[tables.length];
      for(int i=0; i < tables.length; i++){
        tableNames[i] = TableName.valueOf(tables[i]);
      }
      Configuration conf = getConf() != null? getConf():HBaseConfiguration.create();
      try(final Connection conn = ConnectionFactory.createConnection(conf);
          final HBaseBackupAdmin admin = new HBaseBackupAdmin(conn);){
        admin.addToBackupSet(setName, tableNames);
      }

    }

    private BackupCommand getCommand(String cmdStr) throws IOException {
      if (cmdStr.equals(SET_ADD_CMD)) {
        return BackupCommand.SET_ADD;
      } else if (cmdStr.equals(SET_REMOVE_CMD)) {
        return BackupCommand.SET_REMOVE;
      } else if (cmdStr.equals(SET_DELETE_CMD)) {
        return BackupCommand.SET_DELETE;
      } else if (cmdStr.equals(SET_DESCRIBE_CMD)) {
        return BackupCommand.SET_DESCRIBE;
      } else if (cmdStr.equals(SET_LIST_CMD)) {
        return BackupCommand.SET_LIST;
      } else {
        System.out.println("ERROR: Unknown command for 'set' :" + cmdStr);
        printUsage();
        throw new IOException(INCORRECT_USAGE);
      }
    }

    @Override
    protected void printUsage() {
      System.out.println(SET_CMD_USAGE);
    }

  }
}