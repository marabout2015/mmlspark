// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark.IO.http

import com.microsoft.azure.credentials.AzureCliCredentials
import com.microsoft.azure.management.Azure
import com.microsoft.ml.spark.IO.binary.FileReaderUtils
import com.microsoft.ml.spark.core.test.base.TestBase

class LoadBalancerSuite extends TestBase with FileReaderUtils {

  test("Creation of a load balancer", TestBase.Extended) {
    val auth = Azure.authenticate(AzureCliCredentials.create)

    val setup = new AzureLoadBalancer(
      auth,
      "MAML Performance and Accuracy",
      "marhamil_rg")

    setup.createIfNotExists(
      "test_vn",
      "testEndpoint",
      8889,
      8889,
      "servingTest"
    )
  }

}