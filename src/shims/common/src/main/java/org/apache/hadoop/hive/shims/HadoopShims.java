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
package org.apache.hadoop.hive.shims;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.List;

import javax.security.auth.login.LoginException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobProfile;
import org.apache.hadoop.mapred.JobStatus;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TaskCompletionEvent;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Progressable;

/**
 * In order to be compatible with multiple versions of Hadoop, all parts
 * of the Hadoop interface that are not cross-version compatible are
 * encapsulated in an implementation of this class. Users should use
 * the ShimLoader class as a factory to obtain an implementation of
 * HadoopShims corresponding to the version of Hadoop currently on the
 * classpath.
 */
public interface HadoopShims {

  static final Log LOG = LogFactory.getLog(HadoopShims.class);

  /**
   * Return true if the current version of Hadoop uses the JobShell for
   * command line interpretation.
   */
  boolean usesJobShell();

  /**
   * Constructs and Returns TaskAttempt Log Url
   * or null if the TaskLogServlet is not available
   *
   *  @return TaskAttempt Log Url
   */
  String getTaskAttemptLogUrl(JobConf conf,
    String taskTrackerHttpAddress,
    String taskAttemptId)
    throws MalformedURLException;

  /**
   * Return true if the job has not switched to RUNNING state yet
   * and is still in PREP state
   */
  boolean isJobPreparing(RunningJob job) throws IOException;

  /**
   * Calls fs.deleteOnExit(path) if such a function exists.
   *
   * @return true if the call was successful
   */
  boolean fileSystemDeleteOnExit(FileSystem fs, Path path) throws IOException;

  /**
   * Calls fmt.validateInput(conf) if such a function exists.
   */
  void inputFormatValidateInput(InputFormat fmt, JobConf conf) throws IOException;

  /**
   * If JobClient.getCommandLineConfig exists, sets the given
   * property/value pair in that Configuration object.
   *
   * This applies for Hadoop 0.17 through 0.19
   */
  void setTmpFiles(String prop, String files);

  /**
   * return the last access time of the given file.
   * @param file
   * @return last access time. -1 if not supported.
   */
  long getAccessTime(FileStatus file);

  /**
   * return the Kerberos short name
   * @param full Kerberos name
   * @return short Kerberos name
   * @throws IOException
   */
  String getKerberosShortName(String kerberosName) throws IOException;

  /**
   * Returns a shim to wrap MiniMrCluster
   */
  public MiniMrShim getMiniMrCluster(Configuration conf, int numberOfTaskTrackers,
                                     String nameNode, int numDir) throws IOException;

  /**
   * Shim for MiniMrCluster
   */
  public interface MiniMrShim {
    public int getJobTrackerPort() throws UnsupportedOperationException;
    public void shutdown() throws IOException;
    public void setupConfiguration(Configuration conf);
  }

  /**
   * Returns a shim to wrap MiniDFSCluster. This is necessary since this class
   * was moved from org.apache.hadoop.dfs to org.apache.hadoop.hdfs
   */
  MiniDFSShim getMiniDfs(Configuration conf,
      int numDataNodes,
      boolean format,
      String[] racks) throws IOException;

  /**
   * Shim around the functions in MiniDFSCluster that Hive uses.
   */
  public interface MiniDFSShim {
    FileSystem getFileSystem() throws IOException;

    void shutdown() throws IOException;
  }

  /**
   * We define this function here to make the code compatible between
   * hadoop 0.17 and hadoop 0.20.
   *
   * Hive binary that compiled Text.compareTo(Text) with hadoop 0.20 won't
   * work with hadoop 0.17 because in hadoop 0.20, Text.compareTo(Text) is
   * implemented in org.apache.hadoop.io.BinaryComparable, and Java compiler
   * references that class, which is not available in hadoop 0.17.
   */
  int compareText(Text a, Text b);

  CombineFileInputFormatShim getCombineFileInputFormat();

  String getInputFormatClassName();

  /**
   * Wrapper for Configuration.setFloat, which was not introduced
   * until 0.20.
   */
  void setFloatConf(Configuration conf, String varName, float val);

  /**
   * getTaskJobIDs returns an array of String with two elements. The first
   * element is a string representing the task id and the second is a string
   * representing the job id. This is necessary as TaskID and TaskAttemptID
   * are not supported in Haddop 0.17
   */
  String[] getTaskJobIDs(TaskCompletionEvent t);

  int createHadoopArchive(Configuration conf, Path parentDir, Path destDir,
      String archiveName) throws Exception;

  public URI getHarUri(URI original, URI base, URI originalBase)
        throws URISyntaxException;
  /**
   * Hive uses side effect files exclusively for it's output. It also manages
   * the setup/cleanup/commit of output from the hive client. As a result it does
   * not need support for the same inside the MR framework
   *
   * This routine sets the appropriate options related to bypass setup/cleanup/commit
   * support in the MR framework, but does not set the OutputFormat class.
   */
  void prepareJobOutput(JobConf conf);

  /**
   * Used by TaskLogProcessor to Remove HTML quoting from a string
   * @param item the string to unquote
   * @return the unquoted string
   *
   */
  public String unquoteHtmlChars(String item);



  public void closeAllForUGI(UserGroupInformation ugi);

  /**
   * Get the UGI that the given job configuration will run as.
   *
   * In secure versions of Hadoop, this simply returns the current
   * access control context's user, ignoring the configuration.
   */
  public UserGroupInformation getUGIForConf(Configuration conf) throws LoginException, IOException;

  /**
   * Used by metastore server to perform requested rpc in client context.
   * @param <T>
   * @param ugi
   * @param pvea
   * @throws IOException
   * @throws InterruptedException
   */
  public <T> T doAs(UserGroupInformation ugi, PrivilegedExceptionAction<T> pvea) throws
    IOException, InterruptedException;

  /**
   * Once a delegation token is stored in a file, the location is specified
   * for a child process that runs hadoop operations, using an environment
   * variable .
   * @return Return the name of environment variable used by hadoop to find
   *  location of token file
   */
  public String getTokenFileLocEnvName();


  /**
   * Get delegation token from filesystem and write the token along with
   * metastore tokens into a file
   * @param conf
   * @return Path of the file with token credential
   * @throws IOException
   */
  public Path createDelegationTokenFile(final Configuration conf) throws IOException;


  /**
   * Used by metastore server to creates UGI object for a remote user.
   * @param userName remote User Name
   * @param groupNames group names associated with remote user name
   * @return UGI created for the remote user.
   */

  public UserGroupInformation createRemoteUser(String userName, List<String> groupNames);
  /**
   * Get the short name corresponding to the subject in the passed UGI
   *
   * In secure versions of Hadoop, this returns the short name (after
   * undergoing the translation in the kerberos name rule mapping).
   * In unsecure versions of Hadoop, this returns the name of the subject
   */
  public String getShortUserName(UserGroupInformation ugi);

  /**
   * Return true if the Shim is based on Hadoop Security APIs.
   */
  public boolean isSecureShimImpl();

  /**
   * Return true if the hadoop configuration has security enabled
   * @return
   */
  public boolean isSecurityEnabled();

  /**
   * Get the string form of the token given a token signature.
   * The signature is used as the value of the "service" field in the token for lookup.
   * Ref: AbstractDelegationTokenSelector in Hadoop. If there exists such a token
   * in the token cache (credential store) of the job, the lookup returns that.
   * This is relevant only when running against a "secure" hadoop release
   * The method gets hold of the tokens if they are set up by hadoop - this should
   * happen on the map/reduce tasks if the client added the tokens into hadoop's
   * credential store in the front end during job submission. The method will
   * select the hive delegation token among the set of tokens and return the string
   * form of it
   * @param tokenSignature
   * @return the string form of the token found
   * @throws IOException
   */
  public String getTokenStrForm(String tokenSignature) throws IOException;

  /**
   * Add a delegation token to the given ugi
   * @param ugi
   * @param tokenStr
   * @param tokenService
   * @throws IOException
   */
  public void setTokenStr(UserGroupInformation ugi, String tokenStr, String tokenService)
    throws IOException;

  /**
   * Add given service to the string format token
   * @param tokenStr
   * @param tokenService
   * @return
   * @throws IOException
   */
  public String addServiceToToken(String tokenStr, String tokenService)
    throws IOException;

  enum JobTrackerState { INITIALIZING, RUNNING };

  /**
   * Convert the ClusterStatus to its Thrift equivalent: JobTrackerState.
   * See MAPREDUCE-2455 for why this is a part of the shim.
   * @param clusterStatus
   * @return the matching JobTrackerState
   * @throws Exception if no equivalent JobTrackerState exists
   */
  public JobTrackerState getJobTrackerState(ClusterStatus clusterStatus) throws Exception;

  public TaskAttemptContext newTaskAttemptContext(Configuration conf, final Progressable progressable);

  public TaskAttemptID newTaskAttemptID(JobID jobId, boolean isMap, int taskId, int id);

  public JobContext newJobContext(Job job);

  /**
   * Check wether MR is configured to run in local-mode
   * @param conf
   * @return
   */
  public boolean isLocalMode(Configuration conf);

  /**
   * All retrieval of jobtracker/resource manager rpc address
   * in the configuration should be done through this shim
   * @param conf
   * @return
   */
  public String getJobLauncherRpcAddress(Configuration conf);

  /**
   * All updates to jobtracker/resource manager rpc address
   * in the configuration should be done through this shim
   * @param conf
   * @return
   */
  public void setJobLauncherRpcAddress(Configuration conf, String val);

  /**
   * Return the MR framework if MR2 mode. (local or yarn)
   * In upstream, 23shim automatically resets this value if address manager
   * rpc address has changed.  In CDH, there is only one shim for both MR1/MR2,
   * so it doesn't have the context to do so.  We need to explicitly track and
   * reset this value.
   * @param conf
   * @return
   */
  public String getMRFramework(Configuration conf);

  /**
   * Set the MR framework as it is understood by MR2. (local or yarn)
   * In upstream, 23shim automatically resets this value if address manager
   * rpc address has changed.  In CDH, there is only one shim for both MR1/MR2,
   * so it doesn't have the context to do so.  We need to explicitly track and
   * reset this value.
   * @param conf
   * @param framework
   */
  public void setMRFramework(Configuration conf, String framework);

  /**
   * All references to jobtracker/resource manager http address
   * in the configuration should be done through this shim
   * @param conf
   * @return
   */
  public String getJobLauncherHttpAddress(Configuration conf);


  /**
   *  Perform kerberos login using the given principal and keytab
   * @throws IOException
   */
  public void loginUserFromKeytab(String principal, String keytabFile) throws IOException;

  /**
   * Perform kerberos re-login using the given principal and keytab, to renew
   * the credentials
   * @throws IOException
   */
  public void reLoginUserFromKeytab() throws IOException;

  /***
   * Check if the current UGI is keytab based
   * @return
   * @throws IOException
   */
  public boolean isLoginKeytabBased() throws IOException;

  /**
   * Move the directory/file to trash. In case of the symlinks or mount points, the file is
   * moved to the trashbin in the actual volume of the path p being deleted
   * @param fs
   * @param path
   * @param conf
   * @return false if the item is already in the trash or trash is disabled
   * @throws IOException
   */
  public boolean moveToAppropriateTrash(FileSystem fs, Path path, Configuration conf)
          throws IOException;

  /**
   * Get the default block size for the path. FileSystem alone is not sufficient to
   * determine the same, as in case of CSMT the underlying file system determines that.
   * @param fs
   * @param path
   * @return
   */
  public long getDefaultBlockSize(FileSystem fs, Path path);

  /**
   * Get the default replication for a path. In case of CSMT the given path will be used to
   * locate the actual filesystem.
   * @param fs
   * @param path
   * @return
   */
  public short getDefaultReplication(FileSystem fs, Path path);

  /**
   * Create the proxy ugi for the given userid
   * @param userName
   * @return
   */
  public UserGroupInformation createProxyUser(String userName) throws IOException;

  /**
   * Verify proxy access to given UGI for given user
   * @param ugi
   */
  public void authorizeProxyAccess(String proxyUser, UserGroupInformation realUserUgi,
      String ipAddress, Configuration conf) throws IOException;

  /**
   * Reset the default scheduler queue if applicable
   */
  public void refreshDefaultQueue(Configuration conf, String userName) throws IOException;

  /**
   * The method sets to set the partition file has a different signature between
   * hadoop versions.
   * @param jobConf
   * @param partition
   */
  void setTotalOrderPartitionFile(JobConf jobConf, Path partition);
  /**
   * InputSplitShim.
   *
   */
  public interface InputSplitShim extends InputSplit {
    JobConf getJob();

    long getLength();

    /** Returns an array containing the startoffsets of the files in the split. */
    long[] getStartOffsets();

    /** Returns an array containing the lengths of the files in the split. */
    long[] getLengths();

    /** Returns the start offset of the i<sup>th</sup> Path. */
    long getOffset(int i);

    /** Returns the length of the i<sup>th</sup> Path. */
    long getLength(int i);

    /** Returns the number of Paths in the split. */
    int getNumPaths();

    /** Returns the i<sup>th</sup> Path. */
    Path getPath(int i);

    /** Returns all the Paths in the split. */
    Path[] getPaths();

    /** Returns all the Paths where this input-split resides. */
    String[] getLocations() throws IOException;

    void shrinkSplit(long length);

    String toString();

    void readFields(DataInput in) throws IOException;

    void write(DataOutput out) throws IOException;
  }

  /**
   * CombineFileInputFormatShim.
   *
   * @param <K>
   * @param <V>
   */
  interface CombineFileInputFormatShim<K, V> {
    Path[] getInputPathsShim(JobConf conf);

    void createPool(JobConf conf, PathFilter... filters);

    InputSplitShim[] getSplits(JobConf job, int numSplits) throws IOException;

    InputSplitShim getInputSplitShim() throws IOException;

    RecordReader getRecordReader(JobConf job, InputSplitShim split, Reporter reporter,
        Class<RecordReader<K, V>> rrClass) throws IOException;
  }

  /**
   * For a given file, return a file status
   * @param conf
   * @param fs
   * @param file
   * @return
   * @throws IOException
   */
  public HdfsFileStatus getFullFileStatus(Configuration conf, FileSystem fs, Path file) throws IOException;

  /**
   * For a given file, set a given file status.
   * @param conf
   * @param sourceStatus
   * @param fs
   * @param target
   * @throws IOException
   */
  public void setFullFileStatus(Configuration conf, HdfsFileStatus sourceStatus,
    FileSystem fs, Path target) throws IOException;

  /**
   * Includes the vanilla FileStatus, and AclStatus if it applies to this version of hadoop.
   */
  public interface HdfsFileStatus {
    public FileStatus getFileStatus();
    public void debugLog();
  }

  public HCatHadoopShims getHCatShim();
  public interface HCatHadoopShims {

    enum PropertyName {CACHE_ARCHIVES, CACHE_FILES, CACHE_SYMLINK}

    public TaskID createTaskID();

    public TaskAttemptID createTaskAttemptID();

    public org.apache.hadoop.mapreduce.TaskAttemptContext createTaskAttemptContext(Configuration conf,
                                                                                   TaskAttemptID taskId);

    public org.apache.hadoop.mapred.TaskAttemptContext createTaskAttemptContext(JobConf conf,
                                                                                org.apache.hadoop.mapred.TaskAttemptID taskId, Progressable progressable);

    public JobContext createJobContext(Configuration conf, JobID jobId);

    public org.apache.hadoop.mapred.JobContext createJobContext(JobConf conf, JobID jobId, Progressable progressable);

    public void commitJob(OutputFormat outputFormat, Job job) throws IOException;

    public void abortJob(OutputFormat outputFormat, Job job) throws IOException;

    /* Referring to job tracker in 0.20 and resource manager in 0.23 */
    public InetSocketAddress getResourceManagerAddress(Configuration conf);

    public String getPropertyName(PropertyName name);

    /**
     * Checks if file is in HDFS filesystem.
     *
     * @param fs
     * @param path
     * @return true if the file is in HDFS, false if the file is in other file systems.
     */
    public boolean isFileInHDFS(FileSystem fs, Path path) throws IOException;
  }
  /**
   * Provides a Hadoop JobTracker shim.
   * @param conf not {@code null}
   */
  public WebHCatJTShim getWebHCatShim(Configuration conf, UserGroupInformation ugi) throws IOException;
  public interface WebHCatJTShim {
    /**
     * Grab a handle to a job that is already known to the JobTracker.
     *
     * @return Profile of the job, or null if not found.
     */
    public JobProfile getJobProfile(org.apache.hadoop.mapred.JobID jobid) throws IOException;
    /**
     * Grab a handle to a job that is already known to the JobTracker.
     *
     * @return Status of the job, or null if not found.
     */
    public JobStatus getJobStatus(org.apache.hadoop.mapred.JobID jobid) throws IOException;
    /**
     * Kill a job.
     */
    public void killJob(org.apache.hadoop.mapred.JobID jobid) throws IOException;
    /**
     * Get all the jobs submitted.
     */
    public JobStatus[] getAllJobs() throws IOException;
    /**
     * Close the connection to the Job Tracker.
     */
    public void close();
  }

  /**
   * Create a proxy file system that can serve a given scheme/authority using some
   * other file system.
   */
  public FileSystem createProxyFileSystem(FileSystem fs, URI uri);
}
