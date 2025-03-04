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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hadoop.ozone.om.request.bucket;

import java.util.UUID;

import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.apache.ozone.test.GenericTestUtils;
import org.junit.Assert;
import org.junit.Test;

import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.
    BucketArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .SetBucketPropertyRequest;

import static org.apache.hadoop.ozone.OzoneConsts.GB;

/**
 * Tests OMBucketSetPropertyRequest class which handles OMSetBucketProperty
 * request.
 */
public class TestOMBucketSetPropertyRequest extends TestBucketRequest {

  @Test
  public void testPreExecute() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    OMRequest preExecuteRequest =
        omBucketSetPropertyRequest.preExecute(ozoneManager);
    // When preExecute() of bucket setProperty,
    // the new modification time is greater than origin one.
    long originModTime = omRequest.getSetBucketPropertyRequest()
        .getModificationTime();
    long newModTime = preExecuteRequest.getSetBucketPropertyRequest()
        .getModificationTime();
    Assert.assertTrue(newModTime > originModTime);

    // As user info gets added.
    Assert.assertNotEquals(omRequest,
        omBucketSetPropertyRequest.preExecute(ozoneManager));
  }

  @Test
  public void testValidateAndUpdateCache() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    // Create with default BucketInfo values
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName,
        omMetadataManager);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    OMClientResponse omClientResponse =
        omBucketSetPropertyRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(true,
        omMetadataManager.getBucketTable().get(
            omMetadataManager.getBucketKey(volumeName, bucketName))
            .getIsVersionEnabled());

    Assert.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omClientResponse.getOMResponse().getStatus());
  }

  @Test
  public void testValidateAndUpdateCacheFails() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    OMClientResponse omClientResponse =
        omBucketSetPropertyRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(OzoneManagerProtocolProtos.Status.BUCKET_NOT_FOUND,
        omClientResponse.getOMResponse().getStatus());

    Assert.assertNull(omMetadataManager.getBucketTable().get(
        omMetadataManager.getBucketKey(volumeName, bucketName)));

  }

  private OMRequest createSetBucketPropertyRequest(String volumeName,
      String bucketName, boolean isVersionEnabled, long quotaInBytes) {
    return OMRequest.newBuilder().setSetBucketPropertyRequest(
        SetBucketPropertyRequest.newBuilder().setBucketArgs(
            BucketArgs.newBuilder().setBucketName(bucketName)
                .setVolumeName(volumeName)
                .setQuotaInBytes(quotaInBytes)
                .setQuotaInNamespace(1000L)
                .setIsVersionEnabled(isVersionEnabled).build()))
        .setCmdType(OzoneManagerProtocolProtos.Type.SetBucketProperty)
        .setClientId(UUID.randomUUID().toString()).build();
  }

  @Test
  public void testValidateAndUpdateCacheWithQuota() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeToDB(
        volumeName, omMetadataManager, 10 * GB);
    OMRequestTestUtils.addBucketToDB(
        volumeName, bucketName, omMetadataManager, 8 * GB);
    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, 20 * GB);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    GenericTestUtils.LogCapturer logs = GenericTestUtils.LogCapturer
        .captureLogs(LogFactory.getLog(OMBucketSetPropertyRequest.class));

    OMClientResponse omClientResponse = omBucketSetPropertyRequest
        .validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    //capture the error log
    Assert.assertTrue(logs.getOutput().contains(
        "Setting bucket property failed for bucket"));

    Assert.assertFalse(omClientResponse.getOMResponse().getSuccess());
    Assert.assertEquals(omClientResponse.getOMResponse().getStatus(),
        OzoneManagerProtocolProtos.Status.QUOTA_EXCEEDED);
    Assert.assertTrue(omClientResponse.getOMResponse().getMessage().
        contains("Total buckets quota in this volume " +
            "should not be greater than volume quota"));
  }
}
