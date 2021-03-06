/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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


package com.netflix.spinnaker.orca.clouddriver.pipeline.providers.aws

import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.strategies.DeployStagePreProcessor
import com.netflix.spinnaker.orca.kato.pipeline.strategy.Strategy
import com.netflix.spinnaker.orca.kato.pipeline.support.StageData
import com.netflix.spinnaker.orca.pipeline.CheckPreconditionsStage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AwsDeployStagePreProcessor implements DeployStagePreProcessor {
  private static final List<Strategy> rollingStrategies = [Strategy.ROLLING_RED_BLACK, Strategy.MONITORED]

  @Autowired
  ApplySourceServerGroupCapacityStage applySourceServerGroupSnapshotStage

  @Autowired
  CheckPreconditionsStage checkPreconditionsStage

  @Override
  List<StepDefinition> additionalSteps(StageExecution stage) {
    def stageData = stage.mapTo(StageData)
    Strategy strategy = Strategy.fromStrategyKey(stageData.strategy)

    if (rollingStrategies.contains(strategy)) {
      // rolling red/black has no need to snapshot capacities
      return []
    }

    return [
      new StepDefinition(
        name: "snapshotSourceServerGroup",
        taskClass: CaptureSourceServerGroupCapacityTask
      )
    ]
  }

  @Override
  List<StageDefinition> beforeStageDefinitions(StageExecution stage) {
    def stageData = stage.mapTo(StageData)
    def stageDefinitions = []

    if (shouldCheckServerGroupsPreconditions(stageData)) {
      stageDefinitions << new StageDefinition(
        name: "Check Deploy Preconditions",
        stageDefinitionBuilder: checkPreconditionsStage,
        context: [
          preconditionType: "clusterSize",
          context: [
            onlyEnabledServerGroups: true,
            comparison: '<=',
            expected: stageData.maxInitialAsgs,
            regions: [ stageData.region ],
            cluster: stageData.cluster,
            application: stageData.application,
            credentials: stageData.getAccount(),
            moniker: stageData.moniker
          ]
        ]
      )
    }

    return stageDefinitions
  }

  @Override
  List<StageDefinition> afterStageDefinitions(StageExecution stage) {
    def stageData = stage.mapTo(StageData)
    def stageDefinitions = []
    Strategy strategy = Strategy.fromStrategyKey(stageData.strategy)

    if (!rollingStrategies.contains(strategy)) {
      // rolling strategies have no need to apply a snapshotted capacity (on the newly created server group)
      stageDefinitions << new StageDefinition(
        name: "restoreMinCapacityFromSnapshot",
        stageDefinitionBuilder: applySourceServerGroupSnapshotStage,
        context: [:]
      )
    }

    return stageDefinitions
  }

  @Override
  boolean supports(StageExecution stage) {
    def stageData = stage.mapTo(StageData)
    return stageData.cloudProvider == "aws" // && stageData.useSourceCapacity
  }

  private static boolean shouldCheckServerGroupsPreconditions(StageData stageData) {
    // TODO(dreynaud): enabling cautiously for RRB/MD only for testing, but we would ideally roll this out to other strategies
    Strategy strategy = Strategy.fromStrategyKey(stageData.strategy)

    return (rollingStrategies.contains(strategy) && (stageData.maxInitialAsgs != -1))
  }
}
