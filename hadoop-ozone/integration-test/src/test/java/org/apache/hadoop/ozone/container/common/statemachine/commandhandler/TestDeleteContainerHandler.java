/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.ozone.container.common.statemachine.commandhandler;

import org.apache.hadoop.hdds.HddsConfigKeys;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.client.StandaloneReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.conf.StorageUnit;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.scm.container.ContainerID;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.node.NodeManager;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.HddsDatanodeService;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.common.impl.ContainerData;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.om.helpers.OmKeyArgs;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.protocol.commands.CloseContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.DeleteContainerCommand;
import org.apache.hadoop.ozone.protocol.commands.SCMCommand;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

import org.junit.Rule;
import org.junit.rules.Timeout;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor.ONE;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_CONTAINER_SIZE;

/**
 * Tests DeleteContainerCommand Handler.
 */
public class TestDeleteContainerHandler {

  /**
    * Set a timeout for each test.
    */
  @Rule
  public Timeout timeout = Timeout.seconds(300);


  private static MiniOzoneCluster cluster;
  private static OzoneConfiguration conf;
  private static ObjectStore objectStore;
  private static String volumeName = UUID.randomUUID().toString();
  private static String bucketName = UUID.randomUUID().toString();

  @BeforeClass
  public static void setup() throws Exception {
    conf = new OzoneConfiguration();
    conf.set(OZONE_SCM_CONTAINER_SIZE, "1GB");
    conf.setStorageSize(OZONE_DATANODE_RATIS_VOLUME_FREE_SPACE_MIN,
        0, StorageUnit.MB);
    conf.setBoolean(HddsConfigKeys.HDDS_SCM_SAFEMODE_PIPELINE_CREATION, false);
    cluster = MiniOzoneCluster.newBuilder(conf)
        .setNumDatanodes(1).build();
    cluster.waitForClusterToBeReady();
    cluster.waitForPipelineTobeReady(ONE, 30000);

    OzoneClient client = OzoneClientFactory.getRpcClient(conf);
    objectStore = client.getObjectStore();
    objectStore.createVolume(volumeName);
    objectStore.getVolume(volumeName).createBucket(bucketName);
  }

  @AfterClass
  public static void shutdown() {
    if (cluster != null) {
      try {
        cluster.shutdown();
      } catch (Exception e) {
        // do nothing.
      }
    }
  }

  @Test(timeout = 60000)
  public void testDeleteContainerRequestHandlerOnClosedContainer()
      throws Exception {

    //the easiest way to create an open container is creating a key

    String keyName = UUID.randomUUID().toString();

    // create key
    createKey(keyName);

    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);

    ContainerInfo container = cluster.getStorageContainerManager()
        .getContainerManager().getContainer(containerId);

    Pipeline pipeline = cluster.getStorageContainerManager()
        .getPipelineManager().getPipeline(container.getPipelineID());

    // We need to close the container because delete container only happens
    // on closed containers with force flag set to false.

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);

    Assert.assertFalse(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    DatanodeDetails datanodeDetails = hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();

    //send the order to close the container
    SCMCommand<?> command = new CloseContainerCommand(
        containerId.getId(), pipeline.getId());
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerClosed(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    //double check if it's really closed (waitFor also throws an exception)
    Assert.assertTrue(isContainerClosed(hddsDatanodeService,
        containerId.getId()));

    // Check container exists before sending delete container command
    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

    // send delete container to the datanode
    command = new DeleteContainerCommand(containerId.getId(), false);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

  }


  @Test
  public void testDeleteContainerRequestHandlerOnOpenContainer()
      throws Exception {

    //the easiest way to create an open container is creating a key
    String keyName = UUID.randomUUID().toString();

    // create key
    createKey(keyName);

    // get containerID of the key
    ContainerID containerId = getContainerID(keyName);

    HddsDatanodeService hddsDatanodeService =
        cluster.getHddsDatanodes().get(0);
    DatanodeDetails datanodeDetails =
        hddsDatanodeService.getDatanodeDetails();

    NodeManager nodeManager =
        cluster.getStorageContainerManager().getScmNodeManager();

    // Send delete container command with force flag set to false.
    SCMCommand<?> command = new DeleteContainerCommand(
        containerId.getId(), false);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    // Here it should not delete it, and the container should exist in the
    // containerset
    int count = 1;
    // Checking for 5 seconds, whether it is containerSet, as after command
    // is issued, giving some time for it to process.
    while (!isContainerDeleted(hddsDatanodeService, containerId.getId())) {
      Thread.sleep(1000);
      count++;
      if (count == 5) {
        break;
      }
    }

    Assert.assertFalse(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));


    // Now delete container with force flag set to true. now it should delete
    // container
    command = new DeleteContainerCommand(containerId.getId(), true);
    command.setTerm(
        cluster.getStorageContainerManager().getScmContext().getTermOfLeader());
    nodeManager.addDatanodeCommand(datanodeDetails.getUuid(), command);

    GenericTestUtils.waitFor(() ->
            isContainerDeleted(hddsDatanodeService, containerId.getId()),
        500, 5 * 1000);

    Assert.assertTrue(isContainerDeleted(hddsDatanodeService,
        containerId.getId()));

  }

  /**
   * create a key with specified name.
   * @param keyName
   * @throws IOException
   */
  private void createKey(String keyName) throws IOException {
    OzoneOutputStream key = objectStore.getVolume(volumeName)
        .getBucket(bucketName)
        .createKey(keyName, 1024, ReplicationType.RATIS,
            ReplicationFactor.ONE, new HashMap<>());
    key.write("test".getBytes(UTF_8));
    key.close();
  }

  /**
   * Return containerID of the key.
   * @param keyName
   * @return ContainerID
   * @throws IOException
   */
  private ContainerID getContainerID(String keyName) throws IOException {
    OmKeyArgs keyArgs =
        new OmKeyArgs.Builder().setVolumeName(volumeName)
            .setBucketName(bucketName)
            .setReplicationConfig(StandaloneReplicationConfig.getInstance(ONE))
            .setKeyName(keyName)
            .setRefreshPipeline(true)
            .build();

    OmKeyLocationInfo omKeyLocationInfo =
        cluster.getOzoneManager().lookupKey(keyArgs).getKeyLocationVersions()
            .get(0).getBlocksLatestVersionOnly().get(0);

    return ContainerID.valueOf(
        omKeyLocationInfo.getContainerID());
  }

  /**
   * Checks whether is closed or not on a datanode.
   * @param hddsDatanodeService
   * @param containerID
   * @return true - if container is closes, else returns false.
   */
  private Boolean isContainerClosed(HddsDatanodeService hddsDatanodeService,
      long containerID) {
    ContainerData containerData;
    containerData = hddsDatanodeService
        .getDatanodeStateMachine().getContainer().getContainerSet()
        .getContainer(containerID).getContainerData();
    return !containerData.isOpen();
  }

  /**
   * Checks whether container is deleted from the datanode or not.
   * @param hddsDatanodeService
   * @param containerID
   * @return true - if container is deleted, else returns false
   */
  private Boolean isContainerDeleted(HddsDatanodeService hddsDatanodeService,
      long containerID) {
    Container container;
    // if container is not in container set, it means container got deleted.
    container = hddsDatanodeService
        .getDatanodeStateMachine().getContainer().getContainerSet()
        .getContainer(containerID);
    return container == null;
  }
}
