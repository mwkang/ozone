/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.ozone.container.common.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.scm.container.common.helpers
    .StorageContainerException;
import org.apache.hadoop.ozone.container.common.interfaces.Container;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;


/**
 * Class that manages Containers created on the datanode.
 */
public class ContainerSet {

  private static final Logger LOG = LoggerFactory.getLogger(ContainerSet.class);

  private final ConcurrentSkipListMap<Long, Container<?>> containerMap = new
      ConcurrentSkipListMap<>();
  private final ConcurrentSkipListSet<Long> missingContainerSet =
      new ConcurrentSkipListSet<>();
  /**
   * Add Container to container map.
   * @param container container to be added
   * @return If container is added to containerMap returns true, otherwise
   * false
   */
  public boolean addContainer(Container<?> container) throws
      StorageContainerException {
    Preconditions.checkNotNull(container, "container cannot be null");

    long containerId = container.getContainerData().getContainerID();
    if (containerMap.putIfAbsent(containerId, container) == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Container with container Id {} is added to containerMap",
            containerId);
      }
      // wish we could have done this from ContainerData.setState
      container.getContainerData().commitSpace();
      return true;
    } else {
      LOG.warn("Container already exists with container Id {}", containerId);
      throw new StorageContainerException("Container already exists with " +
          "container Id " + containerId,
          ContainerProtos.Result.CONTAINER_EXISTS);
    }
  }

  /**
   * Returns the Container with specified containerId.
   * @param containerId ID of the container to get
   * @return Container
   */
  public Container<?> getContainer(long containerId) {
    Preconditions.checkState(containerId >= 0,
        "Container Id cannot be negative.");
    return containerMap.get(containerId);
  }

  /**
   * Removes the Container matching with specified containerId.
   * @param containerId ID of the container to remove
   * @return If container is removed from containerMap returns true, otherwise
   * false
   */
  public boolean removeContainer(long containerId) {
    Preconditions.checkState(containerId >= 0,
        "Container Id cannot be negative.");
    Container<?> removed = containerMap.remove(containerId);
    if (removed == null) {
      LOG.debug("Container with containerId {} is not present in " +
          "containerMap", containerId);
      return false;
    } else {
      LOG.debug("Container with containerId {} is removed from containerMap",
          containerId);
      return true;
    }
  }

  /**
   * Return number of containers in container map.
   * @return container count
   */
  @VisibleForTesting
  public int containerCount() {
    return containerMap.size();
  }

  public void handleVolumeFailures() {
    containerMap.values().forEach(c -> {
      if (c.getContainerData().getVolume().isFailed()) {
        try {
          c.markContainerUnhealthy();
          LOG.info("Marking Container {} UNHEALTHY as the Volume {} " +
              "has failed", c.getContainerData().getContainerID(),
              c.getContainerData().getVolume());
        } catch (StorageContainerException e) {
          LOG.error("Failed to move container {} to UNHEALTHY state in "
                  + "volume {}", c.getContainerData().getContainerID(),
              c.getContainerData().getVolume(), e);
        }
      }
    });
  }

  /**
   * Return an container Iterator over {@link ContainerSet#containerMap}.
   * @return {@literal Iterator<Container<?>>}
   */
  public Iterator<Container<?>> getContainerIterator() {
    return containerMap.values().iterator();
  }

  /**
   * Return an iterator of containers associated with the specified volume.
   * The iterator is sorted by last data scan timestamp in increasing order.
   *
   * @param  volume the HDDS volume which should be used to filter containers
   * @return {@literal Iterator<Container<?>>}
   */
  public Iterator<Container<?>> getContainerIterator(HddsVolume volume) {
    Preconditions.checkNotNull(volume);
    Preconditions.checkNotNull(volume.getStorageID());
    String volumeUuid = volume.getStorageID();
    return containerMap.values().stream()
        .filter(x -> volumeUuid.equals(x.getContainerData().getVolume()
            .getStorageID()))
        .sorted(ContainerDataScanOrder.INSTANCE)
        .iterator();
  }

  /**
   * Return an containerMap iterator over {@link ContainerSet#containerMap}.
   * @return containerMap Iterator
   */
  public Iterator<Map.Entry<Long, Container<?>>> getContainerMapIterator() {
    return containerMap.entrySet().iterator();
  }

  /**
   * Return a copy of the containerMap.
   * @return containerMap
   */
  @VisibleForTesting
  public Map<Long, Container<?>> getContainerMapCopy() {
    return ImmutableMap.copyOf(containerMap);
  }

  public Map<Long, Container<?>> getContainerMap() {
    return Collections.unmodifiableMap(containerMap);
  }

  /**
   * A simple interface for container Iterations.
   * <p>
   * This call make no guarantees about consistency of the data between
   * different list calls. It just returns the best known data at that point of
   * time. It is possible that using this iteration you can miss certain
   * container from the listing.
   *
   * @param startContainerId - Return containers with Id &gt;= startContainerId.
   * @param count - how many to return
   * @param data - Actual containerData
   */
  public void listContainer(long startContainerId, long count,
                            List<ContainerData> data) throws
      StorageContainerException {
    Preconditions.checkNotNull(data,
        "Internal assertion: data cannot be null");
    Preconditions.checkState(startContainerId >= 0,
        "Start container Id cannot be negative");
    Preconditions.checkState(count > 0,
        "max number of containers returned " +
            "must be positive");
    LOG.debug("listContainer returns containerData starting from {} of count " +
        "{}", startContainerId, count);
    ConcurrentNavigableMap<Long, Container<?>> map;
    if (startContainerId == 0) {
      map = containerMap;
    } else {
      map = containerMap.tailMap(startContainerId, true);
    }
    int currentCount = 0;
    for (Container<?> entry : map.values()) {
      if (currentCount < count) {
        data.add(entry.getContainerData());
        currentCount++;
      } else {
        return;
      }
    }
  }

  /**
   * Get container report.
   *
   * @return The container report.
   */
  public ContainerReportsProto getContainerReport() throws IOException {
    LOG.debug("Starting container report iteration.");

    ContainerReportsProto.Builder crBuilder =
        ContainerReportsProto.newBuilder();
    // No need for locking since containerMap is a ConcurrentSkipListMap
    // And we can never get the exact state since close might happen
    // after we iterate a point.
    List<Container<?>> containers = new ArrayList<>(containerMap.values());
    // Incremental Container reports can read stale container information
    // This is to make sure FCR and ICR can be linearized and processed by
    // consumers such as SCM.
    synchronized (this) {
      for (Container<?> container : containers) {
        crBuilder.addReports(container.getContainerReport());
      }
    }
    return crBuilder.build();
  }

  public Set<Long> getMissingContainerSet() {
    return missingContainerSet;
  }

  /**
   * Builds the missing container set by taking a diff between total no
   * containers actually found and number of containers which actually
   * got created. It also validates the BCSID stored in the snapshot file
   * for each container as against what is reported in containerScan.
   * This will only be called during the initialization of Datanode Service
   * when  it still not a part of any write Pipeline.
   * @param container2BCSIDMap Map of containerId to BCSID persisted in the
   *                           Ratis snapshot
   */
  public void buildMissingContainerSetAndValidate(
      Map<Long, Long> container2BCSIDMap) {
    container2BCSIDMap.entrySet().parallelStream().forEach((mapEntry) -> {
      long id = mapEntry.getKey();
      if (!containerMap.containsKey(id)) {
        LOG.warn("Adding container {} to missing container set.", id);
        missingContainerSet.add(id);
      } else {
        Container<?> container = containerMap.get(id);
        long containerBCSID = container.getBlockCommitSequenceId();
        long snapshotBCSID = mapEntry.getValue();
        if (containerBCSID < snapshotBCSID) {
          LOG.warn(
              "Marking container {} unhealthy as reported BCSID {} is smaller"
                  + " than ratis snapshot recorded value {}", id,
              containerBCSID, snapshotBCSID);
          // just mark the container unhealthy. Once the DatanodeStateMachine
          // thread starts it will send container report to SCM where these
          // unhealthy containers would be detected
          try {
            container.markContainerUnhealthy();
          } catch (StorageContainerException sce) {
            // The container will still be marked unhealthy in memory even if
            // exception occurs. It won't accept any new transactions and will
            // be handled by SCM. Eve if dn restarts, it will still be detected
            // as unheathy as its BCSID won't change.
            LOG.error("Unable to persist unhealthy state for container {}", id);
          }
        }
      }
    });

  }
}
