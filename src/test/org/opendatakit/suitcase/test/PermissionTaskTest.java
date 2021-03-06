package org.opendatakit.suitcase.test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;

import junit.framework.TestCase;

import org.opendatakit.suitcase.model.AggregateInfo;
import org.opendatakit.suitcase.net.LoginTask;
import org.opendatakit.suitcase.net.PermissionTask;
import org.opendatakit.suitcase.net.SuitcaseSwingWorker;
import org.opendatakit.sync.client.SyncClient;

public class PermissionTaskTest extends TestCase {
  
  AggregateInfo aggInfo = null;
  String serverUrl = null;
  String appId = null;
  String userName = null;
  String password = null;
  String version = null;
  
  @Override
  protected void setUp() throws MalformedURLException {
    serverUrl = "";
    appId = "default";
    userName = "";
    password = "";
    version = "2";
    aggInfo = new AggregateInfo(serverUrl, appId, userName, password); 
  }
  
  public void testCreateUserPermission_ExpectPass() {
    String dataPath = "testfiles/permissions/perm-file.csv";
    boolean foundUser = false;
    String testUserName = "mailto:testerodk@gmail.com";
    String userIdStr = "user_id";
    int retCode;
    
    try {
      SyncClient sc = new SyncClient();
      
      String agg_url = aggInfo.getHostUrl();
      agg_url = agg_url.substring(0, agg_url.length()-1);
      
      URL url = new URL(agg_url);
      String host = url.getHost();
      
      sc.init(host, aggInfo.getUserName(), aggInfo.getPassword());
      
      LoginTask lTask = new LoginTask(aggInfo, false);
      retCode = lTask.blockingExecute();
      assertEquals(retCode, SuitcaseSwingWorker.okCode);
      
      PermissionTask pTask = new PermissionTask(aggInfo, dataPath, version, false);
      retCode = pTask.blockingExecute();
      assertEquals(retCode, SuitcaseSwingWorker.okCode);
      
      // Check that user exists
      ArrayList<Map<String, Object>> result = sc.getUsers(agg_url);

      if (result != null) {

        for (int i = 0; i < result.size(); i++) {
          Map<String, Object> userMap = result.get(i);
          if (userMap.containsKey(userIdStr) && testUserName.equals(userMap.get(userIdStr))) {
            foundUser = true;
            break;
          }
        }

        assertTrue(foundUser);
      }

      sc.close();
    } catch (Exception e) {
      System.out.println("PermissionTaskTest: Exception thrown in testCreateUserPermission_ExpectPass");
      e.printStackTrace();
      fail(); 
    }
  }
  
}
