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

package org.apache.hive.service.cli.operation;

import java.io.IOException;
import java.io.Serializable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils.ObjectInspectorCopyOption;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationState;
import org.apache.hive.service.cli.OperationStatus;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.session.HiveSession;

/**
 * SQLOperation.
 *
 */
public class SQLOperation extends ExecuteStatementOperation {

  private Driver driver = null;
  private CommandProcessorResponse response;
  private TableSchema resultSchema = null;
  private Schema mResultSchema = null;
  private SerDe serde = null;
  private final boolean runAsync;
  private volatile Future<?> backgroundHandle;
  private boolean fetchStarted = false;

  public SQLOperation(HiveSession parentSession, String statement, Map<String,
      String> confOverlay, boolean runInBackground) {
    // TODO: call setRemoteUser in ExecuteStatementOperation or higher.
    super(parentSession, statement, confOverlay);
    this.runAsync = runInBackground;
  }

  /***
   * Compile the query and extract metadata
   * @param sqlOperationConf
   * @throws HiveSQLException
   */
  public void prepare(HiveConf sqlOperationConf) throws HiveSQLException {
    setState(OperationState.RUNNING);

    try {
      driver = new Driver(sqlOperationConf, getParentSession().getUserName(),  getParentSession().getIpAddress());
      // In Hive server mode, we are not able to retry in the FetchTask
      // case, when calling fetch queries since execute() has returned.
      // For now, we disable the test attempts.
      driver.setTryCount(Integer.MAX_VALUE);

      String subStatement = new VariableSubstitution().substitute(sqlOperationConf, statement);
      response = driver.compileAndRespond(subStatement);
      if (0 != response.getResponseCode()) {
        throw new HiveSQLException("Error while compiling statement: "
            + response.getErrorMessage(), response.getSQLState(), response.getResponseCode());
      }

      mResultSchema = driver.getSchema();

      // hasResultSet should be true only if the query has a FetchTask
      // "explain" is an exception for now
      if(driver.getPlan().getFetchTask() != null) {
        //Schema has to be set
        if (mResultSchema == null || !mResultSchema.isSetFieldSchemas()) {
          throw new HiveSQLException("Error compiling query: Schema and FieldSchema " +
              "should be set when query plan has a FetchTask");
        }
        resultSchema = new TableSchema(mResultSchema);
        setHasResultSet(true);
      } else {
        setHasResultSet(false);
      }
      // Set hasResultSet true if the plan has ExplainTask
      // TODO explain should use a FetchTask for reading
      for (Task<? extends Serializable> task: driver.getPlan().getRootTasks()) {
        if (task.getClass() == ExplainTask.class) {
          resultSchema = new TableSchema(mResultSchema);
          setHasResultSet(true);
          break;
        }
      }
    } catch (HiveSQLException e) {
      setState(OperationState.ERROR);
      throw e;
    } catch (Exception e) {
      setState(OperationState.ERROR);
      throw new HiveSQLException("Error running query: " + e.toString(), e);
    }
  }

  private void runInternal(HiveConf sqlOperationConf) throws HiveSQLException {
    try {
      // In Hive server mode, we are not able to retry in the FetchTask
      // case, when calling fetch queries since execute() has returned.
      // For now, we disable the test attempts.
      driver.setTryCount(Integer.MAX_VALUE);

      response = driver.run();
      if (0 != response.getResponseCode()) {
        throw new HiveSQLException("Error while processing statement: "
            + response.getErrorMessage(), response.getSQLState(), response.getResponseCode());
      }

    } catch (HiveSQLException e) {
      setState(OperationState.ERROR);
      throw e;
    } catch (Exception e) {
      setState(OperationState.ERROR);
      throw new HiveSQLException("Error running query: " + e.toString(), e);
    }
    setState(OperationState.FINISHED);
  }

  @Override
  public void run() throws HiveSQLException {
    setState(OperationState.PENDING);
    prepare(getConfigForOperation());
    if (!runAsync) {
      runInternal(getConfigForOperation());
    } else {
      Runnable backgroundOperation = new Runnable() {
        SessionState ss = SessionState.get();
        @Override
        public void run() {
          SessionState.setCurrentSessionState(ss);
          getParentSession().getSessionManager().
              getLogManager().unregisterCurrentThread();
          try {
            getParentSession().getSessionManager().
                getLogManager().registerCurrentThread(getHandle());
            runInternal(getConfigForOperation());
          } catch (HiveSQLException e) {
            setOperationException(e);
            LOG.error("Error: ", e);
          } finally {
            getParentSession().getSessionManager().
                getLogManager().unregisterCurrentThread();
          }
        }
      };
      try {
        // This submit blocks if no background threads are available to run this operation
        backgroundHandle =
            getParentSession().getSessionManager().submitBackgroundOperation(backgroundOperation);
      } catch (RejectedExecutionException rejected) {
        setState(OperationState.ERROR);
        throw new HiveSQLException("All the asynchronous threads are currently busy, " +
            "please retry the operation", rejected);
      }
    }
  }

  private void cleanup(OperationState state) throws HiveSQLException {
    setState(state);
    if (runAsync) {
      if (backgroundHandle != null) {
        backgroundHandle.cancel(true);
      }
    }
    if (driver != null) {
      driver.close();
      driver.destroy();
    }

    SessionState ss = SessionState.get();
    if (ss.getTmpOutputFile() != null) {
      ss.getTmpOutputFile().delete();
    }
  }

  @Override
  public void cancel() throws HiveSQLException {
    cleanup(OperationState.CANCELED);
  }

  @Override
  public void close() throws HiveSQLException {
    cleanup(OperationState.CLOSED);
  }

  @Override
  public TableSchema getResultSetSchema() throws HiveSQLException {
    assertState(OperationState.FINISHED);
    if (resultSchema == null) {
      resultSchema = new TableSchema(driver.getSchema());
    }
    return resultSchema;
  }


  @Override
  public RowSet getNextRowSet(FetchOrientation orientation, long maxRows) throws HiveSQLException {
    assertState(OperationState.FINISHED);
    validateDefaultFetchOrientation(orientation);
    ArrayList<String> rows = new ArrayList<String>();
    driver.setMaxRows((int)maxRows);

    try {
      /* if client is requesting fetch-from-start and its not the first time reading from this operation
       * then reset the fetch position to beginging
       */
      if (orientation.equals(FetchOrientation.FETCH_FIRST) && fetchStarted) {
        driver.resetFetch();
      }
      fetchStarted = true;
      driver.getResults(rows);

      getSerDe();
      StructObjectInspector soi = (StructObjectInspector) serde.getObjectInspector();
      List<? extends StructField> fieldRefs = soi.getAllStructFieldRefs();
      RowSet rowSet = new RowSet();

      Object[] deserializedFields = new Object[fieldRefs.size()];
      Object rowObj;
      ObjectInspector fieldOI;

      for (String rowString : rows) {
        rowObj = serde.deserialize(new BytesWritable(rowString.getBytes()));
        for (int i = 0; i < fieldRefs.size(); i++) {
          StructField fieldRef = fieldRefs.get(i);
          fieldOI = fieldRef.getFieldObjectInspector();
          deserializedFields[i] = convertLazyToJava(soi.getStructFieldData(rowObj, fieldRef), fieldOI);
        }
        rowSet.addRow(resultSchema, deserializedFields);
      }
      return rowSet;
    } catch (IOException e) {
      throw new HiveSQLException(e);
    } catch (CommandNeedRetryException e) {
      throw new HiveSQLException(e);
    } catch (Exception e) {
      throw new HiveSQLException(e);
    }
  }

  /**
   * Convert a LazyObject to a standard Java object in compliance with JDBC 3.0 (see JDBC 3.0
   * Specification, Table B-3: Mapping from JDBC Types to Java Object Types).
   *
   * This method is kept consistent with {@link HiveResultSetMetaData#hiveTypeToSqlType}.
   */
  private static Object convertLazyToJava(Object o, ObjectInspector oi) {
    Object obj = ObjectInspectorUtils.copyToStandardObject(o, oi, ObjectInspectorCopyOption.JAVA);

    if (obj == null) {
      return null;
    }
    if(oi.getTypeName().equals(serdeConstants.BINARY_TYPE_NAME)) {
      return new String((byte[])obj);
    }
    // for now, expose non-primitive as a string
    // TODO: expose non-primitive as a structured object while maintaining JDBC compliance
    if (oi.getCategory() != ObjectInspector.Category.PRIMITIVE) {
      return SerDeUtils.getJSONString(o, oi);
    }
    return obj;
  }


  private SerDe getSerDe() throws SQLException {
    if (serde != null) {
      return serde;
    }
    try {
      List<FieldSchema> fieldSchemas = mResultSchema.getFieldSchemas();
      List<String> columnNames = new ArrayList<String>();
      List<String> columnTypes = new ArrayList<String>();
      StringBuilder namesSb = new StringBuilder();
      StringBuilder typesSb = new StringBuilder();

      if (fieldSchemas != null && !fieldSchemas.isEmpty()) {
        for (int pos = 0; pos < fieldSchemas.size(); pos++) {
          if (pos != 0) {
            namesSb.append(",");
            typesSb.append(",");
          }
          columnNames.add(fieldSchemas.get(pos).getName());
          columnTypes.add(fieldSchemas.get(pos).getType());
          namesSb.append(fieldSchemas.get(pos).getName());
          typesSb.append(fieldSchemas.get(pos).getType());
        }
      }
      String names = namesSb.toString();
      String types = typesSb.toString();

      serde = new LazySimpleSerDe();
      Properties props = new Properties();
      if (names.length() > 0) {
        LOG.debug("Column names: " + names);
        props.setProperty(serdeConstants.LIST_COLUMNS, names);
      }
      if (types.length() > 0) {
        LOG.debug("Column types: " + types);
        props.setProperty(serdeConstants.LIST_COLUMN_TYPES, types);
      }
      serde.initialize(new HiveConf(), props);

    } catch (Exception ex) {
      ex.printStackTrace();
      throw new SQLException("Could not create ResultSet: " + ex.getMessage(), ex);
    }
    return serde;
  }

  /**
   * If there are query specific settings to overlay, then create a copy of config
   * There are two cases we need to clone the session config that's being passed to hive driver
   * 1. Async query -
   *    If the client changes a config setting, that shouldn't reflect in the execution already underway
   * 2. confOverlay -
   *    The query specific settings should only be applied to the query config and not session
   * @return new configuration
   * @throws HiveSQLException
   */
  private HiveConf getConfigForOperation() throws HiveSQLException {
    HiveConf sqlOperationConf = getParentSession().getHiveConf();
    if (!getConfOverlay().isEmpty() || runAsync) {
      // clone the partent session config for this query
      sqlOperationConf = new HiveConf(sqlOperationConf);

      // apply overlay query specific settings, if any
      for (Map.Entry<String, String> confEntry : getConfOverlay().entrySet()) {
        try {
          sqlOperationConf.verifyAndSet(confEntry.getKey(), confEntry.getValue());
        } catch (IllegalArgumentException e) {
          throw new HiveSQLException("Error applying statement specific settings", e);
        }
      }
    }
    return sqlOperationConf;
  }

}
