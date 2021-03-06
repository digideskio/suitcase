package org.opendatakit.suitcase.ui;

import org.opendatakit.suitcase.model.AggregateInfo;
import org.opendatakit.suitcase.model.CsvConfig;
import org.opendatakit.suitcase.model.ODKCsv;
import org.apache.commons.cli.*;
import org.apache.wink.json4j.JSONException;
import org.opendatakit.suitcase.net.*;
import org.opendatakit.suitcase.utils.FieldsValidatorUtils;
import org.opendatakit.suitcase.utils.FileUtils;

import java.net.MalformedURLException;

import static org.opendatakit.suitcase.ui.MessageString.*;

public class SuitcaseCLI {
  
  public static final int PARAM_ERROR_CODE = 1;
  private enum Operation {
    DOWNLOAD, UPLOAD, UPDATE, RESET, INFO, TABLE_OP, PERMISSION
  }

  private static final String[] REQUIRED_ARGS = new String[]{"aggregateUrl", "tableId", "appId"};

  private String[] args;

  private Options cliOptions;
  private AggregateInfo aggInfo;

  private String tableId;
  private String version;
  private String path;
  private String tableOp;
  private boolean downloadAttachment;
  private boolean scanFormatting;
  private boolean extraMetadata;
  private boolean force;

  public SuitcaseCLI(String[] args) {
    this.args = args;

    this.cliOptions = buildOptions();
  }

  public int startCLI() {
    int retCode = 0;
    Operation operation = parseArgs(args, cliOptions);

    if (operation == null) {
      // this means some error was found when parsing arguments
      retCode = PARAM_ERROR_CODE;
      return retCode;
    }

    String error;

    switch (operation) {
    case DOWNLOAD:
      AttachmentManager attMngr = new AttachmentManager(aggInfo, tableId, path);
      ODKCsv csv = null;
      try {
        csv = new ODKCsv(attMngr, aggInfo, tableId);
      } catch (JSONException e) { /* should never happen */} 
      CsvConfig config = new CsvConfig(downloadAttachment, scanFormatting, extraMetadata);

      error = FieldsValidatorUtils.checkDownloadFields(tableId, path, aggInfo);
      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new DownloadTask(aggInfo, csv, config, path, false).blockingExecute();
      }
      break;
    case UPLOAD:
      error = FieldsValidatorUtils.checkUploadFields(version, path);

      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new UploadTask(aggInfo, path, version, false).blockingExecute();
      }
      break;
    case RESET:
      error = FieldsValidatorUtils.checkResetFields(version);

      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new ResetTask(version, false).blockingExecute();
      }
      break;

    case UPDATE:
      error = FieldsValidatorUtils.checkUpdateFields(tableId, path, version);

      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new UpdateTask(aggInfo, path, version, tableId, null, false).blockingExecute();
      }
      break;
      
    case TABLE_OP:
      error = FieldsValidatorUtils.checkTableOpFields(tableId, path, version, tableOp);

      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new TableTask(aggInfo, tableId, path, version, tableOp, false).blockingExecute();
      }
      break;
    
    case PERMISSION:
      error = FieldsValidatorUtils.checkPermissionFields(version, path);

      if (error != null) {
        DialogUtils.showError(error, false);
        retCode = PARAM_ERROR_CODE;
      } else {
        retCode = new PermissionTask(aggInfo, path, version, false).blockingExecute();
      }
      
      break;
    }
    
    return retCode;
  }

  private Options buildOptions() {
    Options opt = new Options();

    //operations
    OptionGroup operation = new OptionGroup();
    operation.addOption(new Option("download", false, "Download csv"));
    operation.addOption(new Option("upload", false, "Upload csv"));
    operation.addOption(new Option("reset", false, "Reset server"));
    operation.addOption(new Option("update", false, "Update tableId using csv specified by path"));
    operation.addOption(new Option("tableop", true, "Create, delete, or clear tableId using csv specified by path"));
    operation.addOption(new Option("permission", false, "Upload user permissions using csv specified by path"));
    operation.addOption(new Option("h", "help", false, "print this message"));
    operation.addOption(new Option("v", "version", false, "prints version information"));
    operation.setRequired(true);
    opt.addOptionGroup(operation);

    //aggregate related
    Option aggUrl = new Option("aggregateUrl", true, "url to Aggregate server");
    opt.addOption(aggUrl);

    Option appId = new Option("appId", true, "app id");
    opt.addOption(appId);

    Option tableId = new Option("tableId", true, "table id");
    opt.addOption(tableId);

    opt.addOption("username", true, "username"); // not required
    opt.addOption("password", true, "password"); // not required

    // not required for download, check later
    opt.addOption("dataVersion", true, "version of data, usually 1 or 2");
    

    //csv options
    opt.addOption("a", "attachment", false, "download attachments");
    opt.addOption("s", "scan", false, "apply Scan formatting");
    opt.addOption("e", "extra", false, "add extra metadata columns");

    opt.addOption("path", true, "Specify a custom path to output csv or to upload from. "
                              + "Default csv directory is ./Download/ "
                              + "Default upload directory is ./Upload/ ");

    //UI
    opt.addOption("f", "force", false, "do not prompt, overwrite existing files");

    return opt;
  }

  /**
   * Parses user arguments from pre-specified Options.
   *
   * @param args    Arguments passed to by user
   * @param options Options to parse from
   * @return false when either "-h" or "-v" is passed, otherwise true
   */
  private Operation parseArgs(String[] args, Options options) {
    Operation operation = null;

    try {
      CommandLineParser parser = new DefaultParser();
      CommandLine line = parser.parse(options, args);

      //handle -h and --help
      if (line.hasOption('h')) {
        HelpFormatter hf = new HelpFormatter();
        hf.printHelp("suitcase", options);
        return Operation.INFO;
      }

      //handle -v
      if (line.hasOption('v')) {
        System.out.println("ODK org.opendatakit.suitcase.Suitcase 2.0");
        return Operation.INFO;
      }

      if (line.hasOption("upload")) {
        operation = Operation.UPLOAD;
      } else if (line.hasOption("reset")) {
        operation = Operation.RESET;
      } else if (line.hasOption("update")){
        operation = Operation.UPDATE;
      } else if (line.hasOption("tableop")) {
        operation = Operation.TABLE_OP;
      } else if (line.hasOption("permission")) {
        operation = Operation.PERMISSION;
      }
      else {
        operation = Operation.DOWNLOAD;
      }

      if (operation != Operation.DOWNLOAD && !line.hasOption("dataVersion")) {
        throw new ParseException("Data version is required for upload, update, tableop, permission and reset");
      }

      for (String arg : REQUIRED_ARGS) {
        if (!line.hasOption(arg)) {
          throw new ParseException(arg + "is required");
        }
      }

      //Aggregate related
      String username = line.getOptionValue("username", "");
      String password = line.getOptionValue("password", "");
      tableOp = line.getOptionValue("tableop", null);

      // validate fields before creating AggregateInfo object
      String error = FieldsValidatorUtils.checkLoginFields(
          line.getOptionValue("aggregateUrl"), line.getOptionValue("appId"),
          username, password,
          username.isEmpty() && password.isEmpty()
      );
      if (error != null) {
        DialogUtils.showError(error, false);
        // return early when validation fails
        return null;
      }

      aggInfo = new AggregateInfo(
          line.getOptionValue("aggregateUrl"), line.getOptionValue("appId"),
          username, password
      );

      new LoginTask(aggInfo, false).blockingExecute();

      tableId = line.getOptionValue("tableId");

      if (operation == Operation.DOWNLOAD) {
        //CSV options
        downloadAttachment = line.hasOption("a");
        scanFormatting = line.hasOption("s");
        extraMetadata = line.hasOption("e");
      }

      path = line.getOptionValue("path", FileUtils.getDefaultSavePath().toString());

      version = line.getOptionValue("dataVersion");

      force = line.hasOption("f");
    } catch (ParseException e) {
      e.printStackTrace();
    } catch (MalformedURLException e) {
      DialogUtils.showError(BAD_URL, false);
      e.printStackTrace();
    }

    return operation;
  }
}
