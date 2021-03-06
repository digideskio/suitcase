package org.opendatakit.suitcase.model;

import org.opendatakit.suitcase.net.AttachmentManager;
import org.opendatakit.suitcase.net.SyncWrapper;
import org.opendatakit.sync.data.ColumnDefinition;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.*;

import static org.opendatakit.sync.client.SyncClient.*;

//!!!ATTENTION!!! One per aggInfo
public class ODKCsv implements Iterable<String[]> {
  public class ODKCSVIterator implements Iterator<String[]> {
    private int cursor;

    ODKCSVIterator() {
      this.cursor = 0;
    }

    @Override
    public boolean hasNext() {
      return this.cursor < size;
    }

    @Override
    public String[] next() {
      return next(new CsvConfig());
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    public String[] next(CsvConfig config) {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }

      String[] nextLine = null;

      try {
        nextLine = get(cursor++, config);
      } catch (Exception e) {
        e.printStackTrace();
      }

      return nextLine;
    }

    public int getIndex() {
      return this.cursor;
    }
  }

  private enum Action {
    KEEP,       //No modification
    FILTER,     //Remove
    LINK,       //Convert to a hyperlink
    SCAN_RAW,   //Insert Scan's raw output
    EXTRA       //Extra metadata columns
  }

  //Maps Position to metadata
  private enum Position {
    FRONT, END
  }

  //Some metadata fields are placed before row data, some are placed after
  private static final Map<Position, List<String>> METADATA_POSITION;

  static {
    METADATA_POSITION = new HashMap<>();
    List<String> frontList = new ArrayList<>();
    List<String> endList = new ArrayList<>();

    frontList.add(ID_ROW_DEF);
    frontList.add(FORM_ID_ROW_DEF);
    frontList.add(LOCALE_ROW_DEF);
    frontList.add(SAVEPOINT_TYPE_ROW_DEF);
    frontList.add(SAVEPOINT_TIMESTAMP_ROW_DEF);
    frontList.add(SAVEPOINT_CREATOR_ROW_DEF);
    frontList.add("_create_user");
    frontList.add("_last_update_user");
    METADATA_POSITION.put(Position.FRONT, frontList);

    endList.add(ROW_ETAG_ROW_DEF);
    endList.add(FILTER_TYPE_ROW_DEF);
    endList.add(FILTER_VALUE_ROW_DEF);
    METADATA_POSITION.put(Position.END, endList);
  }

  //Maps metadata to its json identifier
  private static final Map<String, String> METADATA_JSON_NAME;

  static {
    METADATA_JSON_NAME = new HashMap<>();

    METADATA_JSON_NAME.put(ID_ROW_DEF, ID_JSON);
    METADATA_JSON_NAME.put(FORM_ID_ROW_DEF, FORM_ID_JSON);
    METADATA_JSON_NAME.put(LOCALE_ROW_DEF, LOCALE_JSON);
    METADATA_JSON_NAME.put(SAVEPOINT_TYPE_ROW_DEF, SAVEPOINT_TYPE_JSON);
    METADATA_JSON_NAME.put(SAVEPOINT_TIMESTAMP_ROW_DEF, SAVEPOINT_TIMESTAMP_JSON);
    METADATA_JSON_NAME.put(SAVEPOINT_CREATOR_ROW_DEF, SAVEPOINT_CREATOR_JSON);
    METADATA_JSON_NAME.put(ROW_ETAG_ROW_DEF, ROW_ETAG_JSON);
    METADATA_JSON_NAME.put(FILTER_TYPE_ROW_DEF, FILTER_SCOPE_JSON + ": type");
    METADATA_JSON_NAME.put(FILTER_VALUE_ROW_DEF, FILTER_SCOPE_JSON + ": value");
    METADATA_JSON_NAME.put("_create_user", "createUser");
    METADATA_JSON_NAME.put("_last_update_user", "lastUpdateUser");
  }

  private static final String NULL = "null";
  private static final String CONTENT_TYPE_ELEMENT_NAME = "contentType";
  private static final String URI_FRAG_ELEMENT_NAME = "uriFragment";
  private static final String SCAN_RAW_PREFIX = "raw_";
  //Due to optimization and how Scan is designed, 1 column is always filtered
  private static final int NUM_FILTERED = 1; //TODO: generalize this?

  private AttachmentManager attMngr;
  private AggregateInfo aggInfo;
  private String tableId;

  private List<JSONArray> jsonRows;
  private String[] completeCSVHeader;
  //Header of row data only
  private String[] completeDataHeader;
  private int size;
  private Map<String, Action> colAction;

  /**
   * Initialize ODKCsv with rows
   *
   * @param rows
   * @param attMngr
   * @param aggInfo
   * @throws JSONException
   */
  public ODKCsv(JSONArray rows, AttachmentManager attMngr, AggregateInfo aggInfo, String tableId)
      throws JSONException {
    if (attMngr == null) {
      throw new IllegalArgumentException("AttachmentManager cannot be null");
    }
    if (aggInfo == null) {
      throw new IllegalArgumentException("AggregateInfo cannot be null");
    }
    if (tableId == null || tableId.isEmpty()) {
      throw new IllegalArgumentException("Table Id cannot be null or empty");
    }
    if (!aggInfo.tableIdExists(tableId)) {
      throw new IllegalArgumentException("tableId: " + tableId + " does not exist");
    }

    this.attMngr = attMngr;
    this.aggInfo = aggInfo;
    this.tableId = tableId;

    this.size = 0;
    this.jsonRows = new ArrayList<>();

    if (rows != null) {
      this.size = rows.size();
      this.jsonRows.add(rows);

      this.completeDataHeader = extractDataHeader(rows.getJSONObject(0));
      this.completeCSVHeader = buildCSVHeader();
      this.colAction = buildActionMap();
    }
  }

  /**
   * Initialize an empty ODKCsv
   *
   * @param attMngr
   * @param aggInfo
   */
  public ODKCsv(AttachmentManager attMngr, AggregateInfo aggInfo, String tableId)
      throws JSONException {
    this(null, attMngr, aggInfo, tableId);
  }

  /**
   * Returns header of csv
   *
   * @param config
   * @return
   */
  public String[] getHeader(CsvConfig config) {
    if (this.size < 0) {
      throw new IllegalStateException();
    }

    if (!config.isScanFormatting() && config.isExtraMetadata()) {
      return this.completeCSVHeader;
    }

    List<String> header = new ArrayList<>();
    for (String col : this.completeCSVHeader) {
      Action act = this.colAction.get(col);

      switch (act) {
      case KEEP:
      case LINK:
        //KEEP and LINK both don't affect header
        header.add(col);
        break;
      case SCAN_RAW:
        if (config.isScanFormatting()) {
          header.add(SCAN_RAW_PREFIX + header.get(header.size() - 1));
        }
        //falls through
      case FILTER:
        if (!config.isScanFormatting()) {
          header.add(col);
        }
        break;
      case EXTRA:
        if (config.isExtraMetadata()) {
          header.add(col);
        }
        break;
      default:
        throw new IllegalStateException("This should not happen");
      }
    }

    return header.toArray(new String[] {});
  }

  /**
   * Tries to merge rows into this ODKCsv instance
   *
   * @param rows
   * @return True if merge is successful
   * @throws JSONException
   * @throws IOException 
   */
  public boolean tryAdd(JSONArray rows) throws JSONException, IOException {
    if (!isCompatible(rows)) {
      return false;
    }

    if (this.size < 1) {
      // current instance is empty
      if (rows.size() > 0) {
        // initialize with rows
        this.completeDataHeader = extractDataHeader(rows.getJSONObject(0));

      } else {
        // initialize with column definitions from table definition
        SyncWrapper syncWrapper = SyncWrapper.getInstance();
        ArrayList<ColumnDefinition> cols = syncWrapper.buildColumnDefinitions(tableId);
        this.completeDataHeader = extractDataHeaderWithListOfCols(cols);
      }
      
      this.completeCSVHeader = buildCSVHeader();
      this.colAction = buildActionMap();
    }

    this.jsonRows.add(rows);
    this.size += rows.size();

    return true;
  }

  /**
   * Retrieves 1 row, including data and metadata
   *
   * @param rowIndex
   * @param config
   * @return
   * @throws JSONException 
   * @throws IOException 
   * @throws Exception
   */
  public String[] get(int rowIndex, CsvConfig config) throws JSONException, IOException {
    if (rowIndex >= size) {
      throw new NoSuchElementException();
    }

    //converts rowIndex to listIndex-rowIndex form
    int listIndex = 0;
    while (rowIndex >= this.jsonRows.get(listIndex).size()) {
      rowIndex -= this.jsonRows.get(listIndex).size();
      listIndex++;
    }

    JSONObject row = this.jsonRows.get(listIndex).getJSONObject(rowIndex);
    String[] front = getMetadata(row, Position.FRONT, config);
    String[] middle = getData(row, config);
    String[] end = getMetadata(row, Position.END, config);

    //TODO: try to avoid copying arrays
    String[] sum = new String[front.length + middle.length + end.length];
    System.arraycopy(front, 0, sum, 0, front.length);
    System.arraycopy(middle, 0, sum, front.length, middle.length);
    System.arraycopy(end, 0, sum, front.length + middle.length, end.length);

    return sum;
  }

  public int getSize() {
    return this.size;
  }

  public String getTableId() {
    return this.tableId;
  }

//  public void setAttachmentManager(AttachmentManager mngr) {
//    this.attMngr = mngr;
//  }

  /**
   * Extract data header from 1 row of JSON
   *
   * @param oneRow
   * @return
   * @throws JSONException
   */
  private String[] extractDataHeader(JSONObject oneRow) throws JSONException {
    JSONArray orderedColumns = oneRow.getJSONArray(ORDERED_COLUMNS_DEF);
    String[] columns = new String[orderedColumns.size()];

    for (int i = 0; i < columns.length; i++) {
      columns[i] = orderedColumns.getJSONObject(i).getString("column");
    }

    return columns;
  }
  
  /**
   * Extract data header from 1 row of JSON
   *
   * @param oneRow
   * @return
   * @throws JSONException
   */
  private String[] extractDataHeaderWithListOfCols(ArrayList<ColumnDefinition> listOfColDefs) throws JSONException {
    if (listOfColDefs == null || listOfColDefs.size() == 0) {
      throw new IllegalArgumentException("extractDataHeaderWithListOfCols: columns cannot be null or empty");
    }

    ArrayList<String> columns = new ArrayList<String>();
    String[] retCols = null;

    for (int i = 0; i < listOfColDefs.size(); i++) {
      ColumnDefinition colDef = listOfColDefs.get(i);
      if (colDef.isUnitOfRetention()) {
        columns.add(colDef.getElementKey());
      }
    }

    if (columns.size() > 0) {
      retCols = new String[columns.size()];
      retCols = columns.toArray(retCols);
    }
    
    return retCols;
  }

  /**
   * Builds complete CSV header
   *
   * @return
   */
  private String[] buildCSVHeader() {
    int frontHeaderSize = METADATA_POSITION.get(Position.FRONT).size();
    int endHeaderSize = METADATA_POSITION.get(Position.END).size();
    int headerSize = frontHeaderSize + endHeaderSize + this.completeDataHeader.length;

    String[] header = new String[headerSize];
    for (int i = 0; i < headerSize; i++) {
      String headerCol;

      if (i < frontHeaderSize) {
        headerCol = METADATA_POSITION.get(Position.FRONT).get(i);
      } else if (i < frontHeaderSize + this.completeDataHeader.length) {
        headerCol = this.completeDataHeader[i - frontHeaderSize];
      } else {
        headerCol = METADATA_POSITION.get(Position.END)
            .get(i - frontHeaderSize - this.completeDataHeader.length);
      }

      header[i] = headerCol;
    }

    return header;
  }

  /**
   * Infers if rows is compatible with this ODKCsv instance
   *
   * @param rows
   * @return
   * @throws JSONException
   */
  private boolean isCompatible(JSONArray rows) throws JSONException {
    if (this.size < 1) {
      //this instance is empty -> always compatible
      return true;
    }

    String[] newDataHeader = extractDataHeader(rows.getJSONObject(0));

    return newDataHeader.length == this.completeDataHeader.length && Arrays
        .deepEquals(newDataHeader, this.completeDataHeader);

  }

  /**
   * Retrieves metadata for 1 row
   *
   * @param row
   * @param pos
   * @param config
   * @return
   * @throws JSONException
   */
  private String[] getMetadata(JSONObject row, Position pos, CsvConfig config) throws
      JSONException {
    List<String> metadataList = METADATA_POSITION.get(pos);

    List<String> metadata = new ArrayList<>();

    for (String colName : metadataList) {
      String jsonName = METADATA_JSON_NAME.get(colName);

      if (jsonName.startsWith(FILTER_SCOPE_JSON)) {
        metadata
            .add(row.getJSONObject(FILTER_SCOPE_JSON).optString(jsonName.split(":")[1].trim(), NULL));
      } else if (config.isExtraMetadata() || (this.colAction.get(colName) != Action.EXTRA)) {
        metadata.add(row.optString(jsonName, NULL));
      }
      //everything else ignored
    }

    return metadata.toArray(new String[] {});
  }

  /**
   * Retrieves data for 1 row
   *
   * @param row
   * @param config
   * @return
   * @throws IOException 
   * @throws JSONException 
   * @throws Exception
   */
  private String[] getData(JSONObject row, CsvConfig config) throws IOException, JSONException {
    String rowId = row.optString(ID_JSON);

    ScanJson scanRaw = null;
    if (config.isScanFormatting() || config.isDownloadAttachment()) {
      this.attMngr.getListOfRowAttachments(rowId);

      if (config.isScanFormatting()) {
        this.attMngr.downloadAttachments(rowId, true);
        scanRaw = new ScanJson(this.attMngr.getScanRawJsonStream(rowId));
      }
    }

    int dataLength = this.completeDataHeader.length;
    if (config.isScanFormatting()) {
      dataLength -= NUM_FILTERED;
    }
    String[] data = new String[dataLength];

    JSONArray columns = row.getJSONArray(ORDERED_COLUMNS_DEF);
    int offset = 0;
    for (int i = 0; i < this.completeDataHeader.length; i++) {
      String colName = this.completeDataHeader[i];
      Action act = this.colAction.get(colName);
      String value = columns.getJSONObject(i).optString("value", NULL);

      switch (act) {
      case KEEP:
        data[i - offset] = value;
        break;
      case FILTER:
        if (!config.isScanFormatting()) {
          data[i - offset] = value;
        } else {
          offset++;
          //value is ignored when scanFormatting is high
        }
        break;
      case LINK:
        data[i - offset] = makeLink(value, row, config.isDownloadAttachment());
        break;
      case SCAN_RAW:
        if (config.isScanFormatting()) {
          value = scanRaw.getValue(this.completeDataHeader[i - 1]);
        }
        data[i - offset] = value;
        break;
      }
    }

    if (config.isDownloadAttachment()) {
      this.attMngr.downloadAttachments(rowId, false);
    }

    return data;
  }

  /**
   * Builds "Excel compatible" hyperlink using inferred path
   *
   * @param fileName
   * @param row
   * @param localLink
   * @return
   * @throws IOException
   */
  private String makeLink(String fileName, JSONObject row, boolean localLink) throws IOException {
    String template = "=HYPERLINK(\"%s\", \"Ctrl + Click to view\")";
    String attachmentUrlStr;
    if (localLink) {
      URL attachmentUrl = this.attMngr.getAttachmentUrl(row.optString(ID_JSON), fileName, true);
      if (attachmentUrl == null) {
        return "File is missing on Aggregate server.";
      }
      attachmentUrlStr = attachmentUrl.toString();
    } else {
      attachmentUrlStr =
          this.aggInfo.getServerUrl() + "/" + "tables" + "/" + this.aggInfo.getAppId() + "/" +
              this.tableId + "/ref/" +
              this.aggInfo.getSchemaETag(this.tableId) + "/attachments/" +
              row.optString(ID_JSON) + "/file/" + fileName;
    }

    return String.format(template, attachmentUrlStr);
  }

  /**
   * Associates column name to its Action
   *
   * @return
   */
  private Map<String, Action> buildActionMap() {
    Map<String, Action> actionMap = new HashMap<>();

    for (String s : this.completeCSVHeader) {
      if (s.endsWith(CONTENT_TYPE_ELEMENT_NAME)) {
        if (s.equals("raw_contentType")) {
          actionMap.put(s, Action.FILTER); //TODO: handle this better
        } else {
          actionMap.put(s, Action.SCAN_RAW);
        }
      } else if (s.endsWith(URI_FRAG_ELEMENT_NAME)) {
        actionMap.put(s, Action.LINK);
      } else if (s.equals("_create_user") || s.equals("_last_update_user")) {
        actionMap.put(s, Action.EXTRA);
      } else {
        actionMap.put(s, Action.KEEP);
      }
    }

    return actionMap;
  }

  @Override
  public Iterator<String[]> iterator() {
    return getODKCSVIterator();
  }

  public ODKCSVIterator getODKCSVIterator() {
    return new ODKCSVIterator();
  }
}
