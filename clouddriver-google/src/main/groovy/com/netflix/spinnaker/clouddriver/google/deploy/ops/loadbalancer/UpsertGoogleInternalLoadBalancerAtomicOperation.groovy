/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.google.deploy.ops.loadbalancer

import com.google.api.services.compute.model.BackendService
import com.google.api.services.compute.model.ForwardingRule
import com.google.api.services.compute.model.HealthCheck
import com.google.api.services.compute.model.Operation
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.google.deploy.GCEUtil
import com.netflix.spinnaker.clouddriver.google.deploy.GoogleOperationPoller
import com.netflix.spinnaker.clouddriver.google.deploy.SafeRetry
import com.netflix.spinnaker.clouddriver.google.deploy.description.UpsertGoogleLoadBalancerDescription
import com.netflix.spinnaker.clouddriver.google.deploy.exception.GoogleOperationException
import com.netflix.spinnaker.clouddriver.google.model.GoogleHealthCheck
import com.netflix.spinnaker.clouddriver.google.model.callbacks.Utils
import com.netflix.spinnaker.clouddriver.google.model.loadbalancing.GoogleSessionAffinity
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import org.springframework.beans.factory.annotation.Autowired

class UpsertGoogleInternalLoadBalancerAtomicOperation implements AtomicOperation<Map> {
  private static final String BASE_PHASE = "UPSERT_INTERNAL_LOAD_BALANCER"

  @Autowired
  SafeRetry safeRetry

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  @Autowired
  private GoogleOperationPoller googleOperationPoller

  private final UpsertGoogleLoadBalancerDescription description

  UpsertGoogleInternalLoadBalancerAtomicOperation() {}

  UpsertGoogleInternalLoadBalancerAtomicOperation(UpsertGoogleLoadBalancerDescription description) {
    this.description = description
  }

  /**
   * curl -X POST -H "Content-Type: application/json" -d '[ { "upsertLoadBalancer": { "region": "us-central1", "ports": ["80"], "ipProtocol": "TCP", "credentials" : "my-account-name", "loadBalancerName" : "testlb", "backendService": {"name": "default-backend-service", "backends": [], "healthCheck": {"name": "basic-check", "port": 80, "checkIntervalSec": 1, "timeoutSec": 1, "healthyThreshold": 1, "unhealthyThreshold": 1, "healthCheckType": "TCP"}}, "network": "default", "subnet": "default-4464150e11ecaace", "loadBalancerType": "INTERNAL"}} ]' localhost:7002/gce/ops
   *
   * @param priorOutputs
   * @return
   */
  @Override
  Map operate(List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing upsert of load balancer $description.loadBalancerName " +
      "in $description.region..."

    if (!description.credentials) {
      throw new IllegalArgumentException("Unable to resolve credentials for Google account '${description.accountName}'.")
    }

    def compute = description.credentials.compute
    def project = description.credentials.project
    def region = description.region
    GoogleHealthCheck descriptionHealthCheck = description.backendService.healthCheck
    String backendServiceName = description.backendService.name
    String healthCheckName = descriptionHealthCheck.name
    GoogleHealthCheck.HealthCheckType healthCheckType = descriptionHealthCheck.healthCheckType

    // Set some default values that will be useful when doing comparisons.
    description.ipProtocol = description.ipProtocol ?: Constants.DEFAULT_IP_PROTOCOL

    ForwardingRule existingForwardingRule
    BackendService existingBackendService
    HealthCheck existingHealthCheck // Could be any one of Http, Https, Ssl, or Tcp.

    // We first devise a plan by setting all of these flags.
    boolean needToUpdateBackendService = false
    boolean needToUpdateHealthCheck = false

    // Check if there already exists a forwarding rule with the requested name.
    existingForwardingRule = GCEUtil.queryRegionalForwardingRule(project, description.loadBalancerName, compute, task, BASE_PHASE)

    if (existingForwardingRule && (description.region != GCEUtil.getLocalName(existingForwardingRule.region))) {
      throw new GoogleOperationException("There is already a load balancer named " +
        "$description.loadBalancerName (in region ${GCEUtil.getLocalName(existingForwardingRule.region)}). " +
        "Please specify a different name.")
    }

    existingBackendService = safeRetry.doRetry(
      { compute.regionBackendServices().get(project, region, backendServiceName).execute() },
      'Get',
      "Region backend service $backendServiceName",
      task,
      BASE_PHASE,
      [400, 403, 412],
      [404]
    ) as BackendService
    if (existingBackendService) {
      Boolean differentHealthChecks = existingBackendService.getHealthChecks().collect { GCEUtil.getLocalName(it) } != [healthCheckName]
      Boolean differentSessionAffinity = GoogleSessionAffinity.valueOf(existingBackendService.getSessionAffinity()) != description.backendService.sessionAffinity
      if (differentHealthChecks || differentSessionAffinity || existingBackendService.protocol != description.ipProtocol) {
        needToUpdateBackendService = true
      }
    }

    // Note: ILBs only use HealthCheck objects, _not_ Http(s)HealthChecks. The actual check (i.e. Ssl, Tcp, Http(s))
    // is nested in a field inside the HealthCheck object. This is different from all previous ways we used health checks,
    // and uses a separate endpoint from Http(s)HealthChecks.
    existingHealthCheck = safeRetry.doRetry(
      { compute.healthChecks().get(project, healthCheckName).execute() },
      'Get',
      "Health check $healthCheckName",
      task,
      BASE_PHASE,
      [400, 403, 412],
      [404]
    ) as HealthCheck

    needToUpdateHealthCheck = existingHealthCheck && GCEUtil.healthCheckShouldBeUpdated(existingHealthCheck, descriptionHealthCheck)

    // Now we start phase 2 of our plan -- upsert all the components.
    def healthCheckOp = null
    if (!existingHealthCheck) {
      task.updateStatus BASE_PHASE, "Creating health check $healthCheckName..."
      def newHealthCheck = GCEUtil.createNewHealthCheck(descriptionHealthCheck)
      healthCheckOp = safeRetry.doRetry(
        { compute.healthChecks().insert(project, newHealthCheck as HealthCheck).execute() },
        'Insert',
        "Health check $healthCheckName",
        task,
        BASE_PHASE,
        [400, 403, 412],
        []
      )
    } else if (existingHealthCheck && needToUpdateHealthCheck) {
      task.updateStatus BASE_PHASE, "Updating health check $healthCheckName..."
      GCEUtil.updateExistingHealthCheck(existingHealthCheck, descriptionHealthCheck)
      healthCheckOp = safeRetry.doRetry(
        { compute.healthChecks().update(project, healthCheckName, existingHealthCheck as HealthCheck).execute() },
        'Update',
        "Health check $healthCheckName",
        task,
        BASE_PHASE,
        [400, 403, 412],
        []
      )
    }
    if (healthCheckOp) {
      googleOperationPoller.waitForGlobalOperation(compute, project, healthCheckOp.getName(),
        null, task, "health check " + healthCheckName, BASE_PHASE)
    }

    def backendServiceOp = null
    if (!existingBackendService) {
      task.updateStatus BASE_PHASE, "Creating backend service ${description.backendService.name}..."
      BackendService bs = new BackendService(
        name: backendServiceName,
        healthChecks: [GCEUtil.buildHealthCheckUrl(project, healthCheckName)],
        sessionAffinity: description.backendService.sessionAffinity ?: 'NONE',
        loadBalancingScheme: 'INTERNAL',
        protocol: description.ipProtocol
      )
      backendServiceOp = safeRetry.doRetry(
        { compute.regionBackendServices().insert(project, region, bs).execute() },
        'Insert',
        "Backend service $description.backendService.name",
        task,
        BASE_PHASE,
        [400, 403, 412],
        []
      )
    } else if (existingBackendService && needToUpdateBackendService) {
      task.updateStatus BASE_PHASE, "Upating backend service ${description.backendService.name}..."
      existingBackendService.healthChecks = [GCEUtil.buildHealthCheckUrl(project, healthCheckName)]
      existingBackendService.sessionAffinity = description.backendService.sessionAffinity ?: 'NONE'
      existingBackendService.loadBalancingScheme = 'INTERNAL'
      existingBackendService.protocol = description.ipProtocol
      backendServiceOp = safeRetry.doRetry(
        { compute.regionBackendServices().update(project, region, existingBackendService.getName(), existingBackendService).execute() },
        'Update',
        "Backend service $description.backendService.name",
        task,
        BASE_PHASE,
        [400, 403, 412],
        []
      )
    }
    if (backendServiceOp) {
      googleOperationPoller.waitForRegionalOperation(compute, project, region, backendServiceOp.getName(),
        null, task, "backend service " + healthCheckName, BASE_PHASE)
    }

    if (!existingForwardingRule) {
      task.updateStatus BASE_PHASE, "Creating forwarding rule $description.loadBalancerName..."
      def forwardingRule = new ForwardingRule(
        name: description.loadBalancerName,
        loadBalancingScheme: 'INTERNAL',
        backendService: GCEUtil.buildRegionBackendServiceUrl(project, region, description.backendService.name),
        IPProtocol: description.ipProtocol,
        IPAddress: description.ipAddress,
        network: GCEUtil.buildNetworkUrl(project, description.network),
        subnetwork: GCEUtil.buildSubnetworkUrl(project, region, description.subnet),
        ports: description.ports
      )
      safeRetry.doRetry(
        { compute.forwardingRules().insert(project, region, forwardingRule).execute() },
        'Insert',
        "Regional forwarding rule ${description.loadBalancerName}",
        task,
        BASE_PHASE,
        [400, 403, 412],
        []
      )
    }

    task.updateStatus BASE_PHASE, "Done upserting load balancer $description.loadBalancerName in $region."
    [loadBalancers: [(region): [name: description.loadBalancerName]]]
  }
}
