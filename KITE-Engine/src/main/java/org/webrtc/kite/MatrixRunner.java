/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.webrtc.kite;

import static io.cosmosoftware.kite.util.ReportUtils.getStackTrace;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.FileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.webrtc.kite.config.client.Client;
import org.webrtc.kite.config.test.TestConfig;
import org.webrtc.kite.config.test.Tuple;
import io.cosmosoftware.kite.report.Container;
import io.cosmosoftware.kite.report.KiteLogger;

// TODO: Auto-generated Javadoc
/**
 * A class to manage the asynchronous execution of TestManager objects.
 */
public class MatrixRunner {

  /** The logger. */
  private final KiteLogger logger = KiteLogger.getLogger(MatrixRunner.class.getName());

  /** The test config. */
  private final TestConfig testConfig;

  /** The interrupted. */
  private boolean interrupted;

  /** The multi executor service. */
  private ExecutorService multiExecutorService;

  /** The tuple list. */
  private List<Tuple> tupleList = new ArrayList<>();

  /** The test suite. */
  private Container testSuite;

  /** The test manager list. */
  private List<TestManager> testManagerList = new ArrayList<>();

  /**
   * Constructs a new MatrixRunner with the given TestConfig and List<Tuple>.
   *
   * @param testConfig TestConfig
   * @param listOfTuples a list of tuples (containing 1 or multiples kite config objects).
   * @param parentSuiteName the parent suite name
   */
  public MatrixRunner(TestConfig testConfig, List<Tuple> listOfTuples, String parentSuiteName) {
    this.testConfig = testConfig;
    this.tupleList.addAll(listOfTuples);
    for (Tuple tuple : this.tupleList) {
      for (Client client : tuple.getClients()) {
        if (client.getBrowserName().equals("firefox")) {
          if (client.getProfile() != null && !client.getProfile().isEmpty()) {
            client.setProfile(testConfig.getFirefoxProfile());
          }
        }
        if (client.getBrowserName().equals("chrome") && client.getVersion() != null && !client.getVersion().contains("electron")) {
          if (client.getExtension() != null && !client.getExtension().isEmpty()) {
            client.setExtension(testConfig.getChromeExtension());
          }
        }
      }
    }
    testSuite = new Container(testConfig.getNameWithTS());
    testSuite.setParentSuite(parentSuiteName);
    testSuite.setReporter(testConfig.getReporter());

  }

  /**
   * Returns a sublist of the given futureList exclusive of the type of objects specified by the
   * objectClass.
   *
   * @param futureList List of Future<Object>
   * @param objectClass The class for the undesired required object.
   * @return A sublist of the given futureList exclusive of the type of objects specified by the
   *         objectClass.
   */
  private List<Future<Object>> getExclusiveSubList(List<Future<Object>> futureList,
      Class<?> objectClass) {
    List<Future<Object>> listOfFutureObjects = new ArrayList<Future<Object>>();
    for (Future<Object> future : futureList) {
      try {
        Object object = future.get();
        if (!objectClass.isInstance(object)) {
          listOfFutureObjects.add(future);
        }
      } catch (InterruptedException | ExecutionException e) {
        logger.error(getStackTrace(e));
      }
    }
    return listOfFutureObjects;
  }

  /**
   * Returns a sublist from the given list of the type of objects specified by the objectClass.
   *
   * @param futureList List of Future<Object>
   * @param objectClass The class for the desired required object list.
   * @return A sublist from the given list of the type of objects specified by the objectClass.
   */
  private List<?> getSubList(List<Future<Object>> futureList, Class<?> objectClass) {
    List<Object> listOfObject = new ArrayList<Object>();
    for (Future<Object> future : futureList) {
      try {
        Object object = future.get();
        if (objectClass.isInstance(object)) {
          listOfObject.add(object);
        }
      } catch (InterruptedException | ExecutionException e) {
        logger.error(getStackTrace(e));
      }
    }
    return listOfObject;
  }

  /**
   * Interrupt.
   */
  public void interrupt() {
    this.interrupted = true;
    this.shutdownExecutors();
  }

  /**
   * Executes the test contained inside the TestManager for the provided matrix.
   *
   * @return List<Future < Object>>
   */
  public List<Future<Object>> run() {
    int totalTestCases = this.tupleList.size();
    long start = System.currentTimeMillis();
    if (totalTestCases < 1) {
      return null;
    }

    List<TestManager> testManagerList = new ArrayList<>();
    List<Future<Object>> futureList = new ArrayList<>();
    this.multiExecutorService = Executors.newFixedThreadPool(this.testConfig.getNoOfThreads());

    logger.info("Executing " + this.testConfig + " for " + totalTestCases
        + " browser tuples with size :" + tupleList.get(0).size());
    testConfig.setLogger(createTestLogger(testConfig.getKiteRequestId(), testConfig.getTestClassName()));
    try {
      for (int index = 0; index < this.tupleList.size(); index++) {
        TestManager manager = new TestManager(this.testConfig, this.tupleList.get(index));
        manager.setTestSuite(testSuite);
        manager.setId((index + 1));
        manager.setTotal(this.tupleList.size());
        testManagerList.add(manager);
      }

      List<Future<Object>> tempFutureList;

      while (testManagerList.size() > 0) {
        tempFutureList = multiExecutorService.invokeAll(testManagerList);
        testManagerList = (List<TestManager>) this.getSubList(tempFutureList, TestManager.class);
        futureList.addAll(this.getExclusiveSubList(tempFutureList, TestManager.class));
      }

      testManagerList.clear();

    } catch (Exception e) {
      logger.error(getStackTrace(e));
    } finally {
      testSuite.setStopTimestamp();
      testConfig.getReporter().generateReportFiles();
      this.shutdownExecutors();
    }

    return futureList;
  }

  /**
   * Shutdown executors.
   */
  synchronized private void shutdownExecutors() {
    if (this.multiExecutorService != null && !this.multiExecutorService.isShutdown()) {
      for (TestManager manager : this.testManagerList) {
        manager.terminate();
      }
      this.multiExecutorService.shutdownNow();
      this.multiExecutorService = null;
    }
    logger.info("shutdownExecutors() done.");
  }


  /**
   * Create a common test logger for all test cases of a given test
   *
   * @return the logger for tests
   * @throws IOException if the FileAppender fails
   */
  private KiteLogger createTestLogger(String kiteRequestId, String testName) {
    KiteLogger testLogger = KiteLogger.getLogger(new SimpleDateFormat("yyyy-MM-dd-HHmmss").format(new Date()));
    String logFileName = ((kiteRequestId == null || kiteRequestId.equals("null")) ? 
      "" : (kiteRequestId + "_")) + testName + "/test_" + testLogger.getName() + ".log";
    try {
      FileAppender fileAppender = new FileAppender(
        new PatternLayout("%d %-5p - %m%n"), "logs/" + logFileName, false);    
      fileAppender.setThreshold(Level.INFO);
      testLogger.addAppender(fileAppender);
    } catch (IOException e) {
      logger.error(getStackTrace(e));
    }
    return testLogger;
  }
}
