package org.apache.stratos.autoscaler.rule;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.Constants;
import org.apache.stratos.autoscaler.NetworkPartitionLbHolder;
import org.apache.stratos.autoscaler.PartitionContext;
import org.apache.stratos.autoscaler.algorithm.AutoscaleAlgorithm;
import org.apache.stratos.autoscaler.algorithm.OneAfterAnother;
import org.apache.stratos.autoscaler.algorithm.RoundRobin;
import org.apache.stratos.autoscaler.client.cloud.controller.CloudControllerClient;
import org.apache.stratos.autoscaler.client.cloud.controller.InstanceNotificationClient;
import org.apache.stratos.autoscaler.partition.PartitionManager;
import org.apache.stratos.cloud.controller.stub.pojo.MemberContext;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.domain.topology.MemberStatus;
import org.apache.stratos.messaging.domain.topology.Service;
import org.apache.stratos.messaging.message.receiver.topology.TopologyManager;

/**
 * This will have utility methods that need to be executed from rule file...
 */
public class RuleTasksDelegator {

    public static final double SCALE_UP_FACTOR = 0.8;   //get from config
    public static final double SCALE_DOWN_FACTOR = 0.2;
    private static boolean arspiIsSet = false;

    private static final Log log = LogFactory.getLog(RuleTasksDelegator.class);

    public double getPredictedValueForNextMinute(float average, float gradient, float secondDerivative, int timeInterval){
        double predictedValue;
//        s = u * t + 0.5 * a * t * t
        if(log.isDebugEnabled()){
            log.debug(String.format("Predicting the value, [average]: %s , [gradient]: %s , [second derivative]" +
                    ": %s , [time intervals]: %s ", average, gradient, secondDerivative, timeInterval));
        }
        predictedValue = average + gradient * timeInterval + 0.5 * secondDerivative * timeInterval * timeInterval;

        return predictedValue;
    }


    public int getNumberOfInstancesRequiredBasedOnRif(float rifPredictedValue , float requestsServedPerInstance , float averageRequestsServedPerInstance , boolean arspiReset){

        float requestsInstanceCanHandle = requestsServedPerInstance;

        if(arspiReset && averageRequestsServedPerInstance != 0){
            requestsInstanceCanHandle = averageRequestsServedPerInstance;
           
        }
        float numberOfInstances = 0;
        if(requestsInstanceCanHandle!=0) {
            numberOfInstances = rifPredictedValue / requestsInstanceCanHandle;
            arspiReset = true;

        }else{
            arspiReset = false;
        }
        return (int)Math.ceil(numberOfInstances);
    }

    public int getNumberOfInstancesRequiredBasedOnLoadAndMemoryConsumption(float upperLimit , float lowerLimit ,double predictedValue , int activeMemberCount ){

        double numberOfInstances = 0;
        if(predictedValue > upperLimit){
            numberOfInstances = (activeMemberCount*predictedValue)/upperLimit;
        }else if((upperLimit >= predictedValue) && (predictedValue >= lowerLimit)){
            numberOfInstances = activeMemberCount;
        }else{
            numberOfInstances = (activeMemberCount*predictedValue)/lowerLimit;
        }

        return (int)Math.ceil(numberOfInstances);
    }

    public int getMaxNumberOfInstancesRequired(int numberOfInstancesReuquiredBasedOnRif , int numberOfInstancesReuquiredBasedOnMemoryConsumption , boolean mcReset , int numberOfInstancesReuquiredBasedOnLoadAverage , boolean laReset){
        int  numberOfInstances = 0;

        int rifBasedRequiredInstances = 0;
        int mcBasedRequiredInstances  = 0;
        int laBasedRequiredInstances  = 0;
        if(arspiIsSet){
            rifBasedRequiredInstances = numberOfInstancesReuquiredBasedOnRif;
        }
        if(mcReset){
            rifBasedRequiredInstances = numberOfInstancesReuquiredBasedOnMemoryConsumption;
        }
        if(laReset){
            rifBasedRequiredInstances = numberOfInstancesReuquiredBasedOnLoadAverage;
        }
        numberOfInstances = Math.max(Math.max(numberOfInstancesReuquiredBasedOnMemoryConsumption,numberOfInstancesReuquiredBasedOnLoadAverage),numberOfInstancesReuquiredBasedOnRif);
        return  numberOfInstances;
    }

    public int getMemberCount(String clusterId , int scalingPara ){

        int activeMemberCount = 0;
        int memberCount = 0;
       for( Service service : TopologyManager.getTopology().getServices()) {
           if(service.clusterExists(clusterId)) {
               Cluster cluster = service.getCluster(clusterId);

               for (Member member : cluster.getMembers()) {
                   if (member.isActive() || member.getStatus() == MemberStatus.Created || member.getStatus() == MemberStatus.Starting  ) {
                       memberCount++;
                       if(member.isActive()) {
                           activeMemberCount++;
                       }
                   }
               }
           }
       }
        if(scalingPara == 1){
            return memberCount;
        }else{
            return activeMemberCount;
        }


    }

    public AutoscaleAlgorithm getAutoscaleAlgorithm(String partitionAlgorithm){
        AutoscaleAlgorithm autoscaleAlgorithm = null;
        if(log.isDebugEnabled()){
            log.debug(String.format("Partition algorithm is ", partitionAlgorithm));
        }
        if(Constants.ROUND_ROBIN_ALGORITHM_ID.equals(partitionAlgorithm)){

            autoscaleAlgorithm = new RoundRobin();
        } else if(Constants.ONE_AFTER_ANOTHER_ALGORITHM_ID.equals(partitionAlgorithm)){

            autoscaleAlgorithm = new OneAfterAnother();
        } else {
            if(log.isErrorEnabled()){
                log.error(String.format("Partition algorithm %s could not be identified !", partitionAlgorithm));
            }
        }
        return autoscaleAlgorithm;
    }

    public void delegateSpawn(PartitionContext partitionContext, String clusterId, String lbRefType) {
        try {

            String nwPartitionId = partitionContext.getNetworkPartitionId();
//            NetworkPartitionContext ctxt =
//                                          PartitionManager.getInstance()
//                                                          .getNetworkPartitionLbHolder(nwPartitionId);
            NetworkPartitionLbHolder lbHolder =
                                          PartitionManager.getInstance()
                                                          .getNetworkPartitionLbHolder(nwPartitionId);

            
            String lbClusterId = getLbClusterId(lbRefType, partitionContext, lbHolder);

            MemberContext memberContext =
                                         CloudControllerClient.getInstance()
                                                              .spawnAnInstance(partitionContext.getPartition(),
                                                                      clusterId,
                                                                      lbClusterId, partitionContext.getNetworkPartitionId());
            if (memberContext != null) {
                partitionContext.addPendingMember(memberContext);
                if(log.isDebugEnabled()){
                    log.debug(String.format("Pending member added, [member] %s [partition] %s", memberContext.getMemberId(),
                            memberContext.getPartition().getId()));
                }
            } else if(log.isDebugEnabled()){
                log.debug("Returned member context is null, did not add to pending members");
            }

        } catch (Throwable e) {
            String message = "Cannot spawn an instance";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
   	}



    public static String getLbClusterId(String lbRefType, PartitionContext partitionCtxt, 
        NetworkPartitionLbHolder networkPartitionLbHolder) {

       String lbClusterId = null;

        if (lbRefType != null) {
            if (lbRefType.equals(org.apache.stratos.messaging.util.Constants.DEFAULT_LOAD_BALANCER)) {
                lbClusterId = networkPartitionLbHolder.getDefaultLbClusterId();
//                lbClusterId = nwPartitionCtxt.getDefaultLbClusterId();
            } else if (lbRefType.equals(org.apache.stratos.messaging.util.Constants.SERVICE_AWARE_LOAD_BALANCER)) {
                String serviceName = partitionCtxt.getServiceName();
                lbClusterId = networkPartitionLbHolder.getLBClusterIdOfService(serviceName);
//                lbClusterId = nwPartitionCtxt.getLBClusterIdOfService(serviceName);
            } else {
                log.warn("Invalid LB reference type defined: [value] "+lbRefType);
            }
        }
        if (log.isDebugEnabled()){
            log.debug(String.format("Getting LB id for spawning instance [lb reference] %s ," +
                    " [partition] %s [network partition] %s [Lb id] %s ", lbRefType, partitionCtxt.getPartitionId(),
                    networkPartitionLbHolder.getNetworkPartitionId(), lbClusterId));
        }
       return lbClusterId;
    }

    public void delegateTerminate(PartitionContext partitionContext, String memberId) {
        try {
            //calling SM to send the instance notification event.
            InstanceNotificationClient.getInstance().sendMemberCleanupEvent(memberId);
            partitionContext.moveActiveMemberToTerminationPendingMembers(memberId);
            //CloudControllerClient.getInstance().terminate(memberId);
        } catch (Throwable e) {
            log.error("Cannot terminate instance", e);
        }
    }

    public void terminateObsoleteInstance(String memberId) {
        try {
            CloudControllerClient.getInstance().terminate(memberId);
        } catch (Throwable e) {
            log.error("Cannot terminate instance", e);
        }
    }

   	public void delegateTerminateAll(String clusterId) {
           try {

               CloudControllerClient.getInstance().terminateAllInstances(clusterId);
           } catch (Throwable e) {
               log.error("Cannot terminate instance", e);
           }
       }

}
