/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram;

import com.malhartech.api.DAG;
import com.malhartech.api.DAG.OperatorMeta;
import com.malhartech.api.Operator;
import com.malhartech.bufferserver.server.Server;
import com.malhartech.bufferserver.storage.DiskStorage;
import com.malhartech.engine.Node;
import com.malhartech.engine.OperatorContext;
import com.malhartech.engine.WindowGenerator;
import com.malhartech.stram.PhysicalPlan.PTOperator;
import com.malhartech.stram.StramChildAgent.ContainerStartRequest;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.ContainerHeartbeatResponse;
import com.malhartech.stram.StreamingContainerUmbilicalProtocol.StreamingContainerContext;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.net.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launcher for topologies in local mode within a single process.
 * Child containers are mapped to threads.
 */
public class StramLocalCluster implements Runnable
{
  private static final Logger LOG = LoggerFactory.getLogger(StramLocalCluster.class);
  // assumes execution as unit test
  private static File CLUSTER_WORK_DIR = new File("target", StramLocalCluster.class.getName());
  protected final StreamingContainerManager dnmgr;
  private final UmbilicalProtocolLocalImpl umbilical;
  private final InetSocketAddress bufferServerAddress;
  private boolean perContainerBufferServer = false;
  private Server bufferServer = null;
  private final Map<String, LocalStramChild> childContainers = new ConcurrentHashMap<String, LocalStramChild>();
  private int containerSeq = 0;
  private boolean appDone = false;
  private final Map<String, StramChild> injectShutdown = new ConcurrentHashMap<String, StramChild>();
  private boolean heartbeatMonitoringEnabled = true;

  public interface MockComponentFactory
  {
    WindowGenerator setupWindowGenerator();

  }

  private MockComponentFactory mockComponentFactory;

  private class UmbilicalProtocolLocalImpl implements StreamingContainerUmbilicalProtocol
  {
    @Override
    public long getProtocolVersion(String protocol, long clientVersion)
            throws IOException
    {
      throw new UnsupportedOperationException("not implemented in local mode");
    }

    @Override
    public ProtocolSignature getProtocolSignature(String protocol,
            long clientVersion, int clientMethodsHash) throws IOException
    {
      throw new UnsupportedOperationException("not implemented in local mode");
    }

    @Override
    public void log(String containerId, String msg) throws IOException
    {
      LOG.info("child msg: {} context: {}", msg, dnmgr.getContainerAgent(containerId).container);
    }

    @Override
    public StreamingContainerContext getInitContext(String containerId)
            throws IOException
    {
      StramChildAgent sca = dnmgr.getContainerAgent(containerId);
      StreamingContainerContext scc = sca.getInitContext();
      scc.deployBufferServer = perContainerBufferServer;
      return scc;
    }

    @Override
    public ContainerHeartbeatResponse processHeartbeat(ContainerHeartbeat msg)
    {
      if (injectShutdown.containsKey(msg.getContainerId())) {
        ContainerHeartbeatResponse r = new ContainerHeartbeatResponse();
        r.shutdown = true;
        return r;
      }
      try {
        //LOG.debug("processing heartbeat " + msg.getContainerId());
        return dnmgr.processHeartbeat(msg);
      }
      finally {
        LocalStramChild c = childContainers.get(msg.getContainerId());
        synchronized (c.heartbeatCount) {
          c.heartbeatCount.incrementAndGet();
          c.heartbeatCount.notifyAll();
        }
      }
    }

    @Override
    public ContainerHeartbeatResponse pollRequest(String containerId)
    {
      StramChildAgent sca = dnmgr.getContainerAgent(containerId);
      return sca.pollRequest();
    }

  }

  public static class LocalStramChild extends StramChild
  {
    /**
     * Count heartbeat from container and allow other threads to wait for it.
     */
    private final AtomicInteger heartbeatCount = new AtomicInteger();
    private final WindowGenerator windowGenerator;

    public LocalStramChild(String containerId, StreamingContainerUmbilicalProtocol umbilical, WindowGenerator winGen)
    {
      super(containerId, new Configuration(), umbilical);
      this.windowGenerator = winGen;
    }

    public static void run(StramChild stramChild, StreamingContainerContext ctx) throws Exception
    {
      LOG.debug("Got context: " + ctx);
      stramChild.setup(ctx);
      boolean hasError = true;
      try {
        // main thread enters heartbeat loop
        stramChild.monitorHeartbeat();
        hasError = false;
      }
      finally {
        // teardown
        try {
          stramChild.teardown();
        } catch (Exception e) {
          if (!hasError) {
            throw e;
          }
        }
      }
    }

    public void waitForHeartbeat(int waitMillis) throws InterruptedException
    {
      synchronized (heartbeatCount) {
        heartbeatCount.wait(waitMillis);
      }
    }

    @Override
    public void teardown()
    {
      super.teardown();
    }

    @Override
    protected WindowGenerator setupWindowGenerator(long smallestWindowId)
    {
      if (windowGenerator != null) {
        return windowGenerator;
      }
      return super.setupWindowGenerator(smallestWindowId);
    }

    OperatorContext getNodeContext(int id)
    {
      return activeNodes.get(id);
    }

    Operator getNode(int id)
    {
      return nodes.get(id).getOperator();
    }

    Map<Integer, Node<?>> getNodes()
    {
      return nodes;
    }

  }

  /**
   * Starts the child "container" as thread.
   */
  private class LocalStramChildLauncher implements Runnable
  {
    final String containerId;
    final LocalStramChild child;

    @SuppressWarnings("CallToThreadStartDuringObjectConstruction")
    private LocalStramChildLauncher(ContainerStartRequest cdr)
    {
      this.containerId = "container-" + containerSeq++;
      WindowGenerator wingen = null;
      if (mockComponentFactory != null) {
        wingen = mockComponentFactory.setupWindowGenerator();
      }
      this.child = new LocalStramChild(containerId, umbilical, wingen);
      dnmgr.assignContainer(cdr, containerId, "localhost", perContainerBufferServer ? null : NetUtils.getConnectAddress(bufferServerAddress));
      Thread launchThread = new Thread(this, containerId);
      launchThread.start();
      childContainers.put(containerId, child);
      LOG.info("Started container {}", containerId);
    }

    @Override
    public void run()
    {
      try {
        StreamingContainerContext ctx = umbilical.getInitContext(containerId);
        LocalStramChild.run(child, ctx);
      }
      catch (Exception e) {
        LOG.error("Container {} failed", containerId, e);
        throw new RuntimeException(e);
      }
      finally {
        childContainers.remove(containerId);
        LOG.info("Container {} terminating.", containerId);
      }
    }

  }

  public StramLocalCluster(DAG dag) throws Exception
  {
    dag.validate();
    // convert to URI so we always write to local file system,
    // even when the environment has a default HDFS location.
    String pathUri = CLUSTER_WORK_DIR.toURI().toString();
    try {
      FileContext.getLocalFSFileContext().delete(
              new Path(pathUri/*CLUSTER_WORK_DIR.getAbsolutePath()*/), true);
    }
    catch (Exception e) {
      throw new RuntimeException("could not cleanup test dir", e);
    }

    dag.getAttributes().attr(DAG.STRAM_APP_ID).set("app_local_" + System.currentTimeMillis());
    dag.getAttributes().attr(DAG.STRAM_APP_PATH).setIfAbsent(pathUri);
    this.dnmgr = new StreamingContainerManager(dag);
    this.umbilical = new UmbilicalProtocolLocalImpl();

    // start buffer server
    this.bufferServer = new Server(0, 1024 * 1024, 8);
    bufferServer.setSpoolStorage(new DiskStorage());
    SocketAddress bindAddr = this.bufferServer.run();
    this.bufferServerAddress = ((InetSocketAddress)bindAddr);
    LOG.info("Buffer server started: {}", bufferServerAddress);
  }

  LocalStramChild getContainer(String id)
  {
    return this.childContainers.get(id);
  }

  public StreamingContainerManager getStreamingContainerManager()
  {
    return dnmgr;
  }

  public StramLocalCluster(DAG dag, MockComponentFactory mcf) throws Exception
  {
    this(dag);
    this.mockComponentFactory = mcf;
  }

  /**
   * Simulate container failure for testing purposes.
   *
   * @param c
   */
  void failContainer(StramChild c)
  {
    injectShutdown.put(c.getContainerId(), c);
    c.triggerHeartbeat();
    LOG.info("Container {} failed, launching new container.", c.getContainerId());
    dnmgr.scheduleContainerRestart(c.getContainerId());
    // simplify testing: remove immediately rather than waiting for thread to exit
    this.childContainers.remove(c.getContainerId());
  }

  public PTOperator findByLogicalNode(OperatorMeta logicalNode)
  {
    List<PTOperator> nodes = dnmgr.getPhysicalPlan().getOperators(logicalNode);
    if (nodes.isEmpty()) {
      return null;
    }
    return nodes.get(0);
  }

  List<PTOperator> getPlanOperators(OperatorMeta logicalNode)
  {
    return dnmgr.getPhysicalPlan().getOperators(logicalNode);
  }

  /**
   * Return the container that has the given operator deployed.
   * Returns null if the specified operator is not deployed.
   *
   * @param planOperator
   * @param timeout
   * @return
   * @throws InterruptedException
   */
  public LocalStramChild getContainer(PTOperator planOperator)
  {
    LocalStramChild container;
    if (planOperator.container.containerId != null) {
      if ((container = getContainer(planOperator.container.containerId)) != null) {
        if (container.getNodeContext(planOperator.getId()) != null) {
          return container;
        }
      }
    }
    return null;
  }

  StramChildAgent getContainerAgent(StramChild c)
  {
    return this.dnmgr.getContainerAgent(c.getContainerId());
  }

  public void runAsync()
  {
    new Thread(this, "master").start();
  }

  public void shutdown()
  {
    appDone = true;
  }

  public void setHeartbeatMonitoringEnabled(boolean enabled)
  {
    this.heartbeatMonitoringEnabled = enabled;
  }

  public void setPerContainerBufferServer(boolean perContainerBufferServer)
  {
    this.perContainerBufferServer = perContainerBufferServer;
  }

  @Override
  public void run() {
    run(0);
  }


  @SuppressWarnings({"SleepWhileInLoop", "ResultOfObjectAllocationIgnored"})
  public void run(long runMillis)
  {
    long endMillis = System.currentTimeMillis() + runMillis;

    while (!appDone) {

      for (String containerIdStr: dnmgr.containerStopRequests.values()) {
        // teardown child thread
        StramChild c = childContainers.get(containerIdStr);
        if (c != null) {
          ContainerHeartbeatResponse r = new ContainerHeartbeatResponse();
          r.shutdown = true;
          c.processHeartbeatResponse(r);
        }
        dnmgr.containerStopRequests.remove(containerIdStr);
        LOG.info("Container {} failed, launching new container.", containerIdStr);
        dnmgr.scheduleContainerRestart(containerIdStr);
      }

      // start containers
      while (!dnmgr.containerStartRequests.isEmpty()) {
        ContainerStartRequest cdr = dnmgr.containerStartRequests.poll();
        if (cdr != null) {
          new LocalStramChildLauncher(cdr);
        }
      }

      if (heartbeatMonitoringEnabled) {
        // monitor child containers
        dnmgr.monitorHeartbeat();
      }

      if (childContainers.isEmpty() && dnmgr.containerStartRequests.isEmpty()) {
        appDone = true;
      }

      if (runMillis > 0 && System.currentTimeMillis() > endMillis) {
        appDone = true;
      }

      if (!appDone) {
        try {
          Thread.sleep(1000);
        }
        catch (InterruptedException e) {
          LOG.info("Sleep interrupted " + e.getMessage());
        }
      }
    }

    for (LocalStramChild lsc: childContainers.values()) {
      injectShutdown.put(lsc.getContainerId(), lsc);
      lsc.triggerHeartbeat();
    }

    LOG.info("Application finished.");
    bufferServer.shutdown();
  }

}
