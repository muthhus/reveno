/**
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.clustering.core.components;

import org.reveno.atp.clustering.api.Address;
import org.reveno.atp.clustering.api.Cluster;
import org.reveno.atp.clustering.api.ClusterView;
import org.reveno.atp.clustering.api.message.Message;
import org.reveno.atp.clustering.core.RevenoClusterConfiguration;
import org.reveno.atp.clustering.core.api.ClusterExecutor;
import org.reveno.atp.clustering.core.api.ClusterState;
import org.reveno.atp.clustering.core.api.MessagesReceiver;
import org.reveno.atp.clustering.core.messages.NodeState;
import org.reveno.atp.clustering.util.Utils;
import org.reveno.atp.utils.RevenoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class MessagingClusterStateCollector implements ClusterExecutor<ClusterState, Void>, MessagesReceiver {

    @Override
    public ClusterState execute(ClusterView view, Void context) {
        LOG.info("Cluster state collection [view: {}, nodes: {}]", view.viewId(), view.members());
        long currentTransactionId = transactionId.get();

        if (allStatesReceived(view, currentTransactionId)) {
            Optional<NodeState> latestTransactionId = nodesStates.values().stream()
                    .filter(m -> view.members().contains(m.address()))
                    .filter(m -> m.viewId == view.viewId())
                    .max(STATE_MESSAGE_COMPARATOR);
            if (latestTransactionId.isPresent()) {
                LOG.info("Cluster state collection.");

                NodeState stateMessage = latestTransactionId.get();
                if (stateMessage.transactionId > currentTransactionId) {
                    LOG.trace("Need to sync - my txId: {}, latest: {}", currentTransactionId, stateMessage.transactionId);
                    return new ClusterState(false, currentTransactionId, Optional.of(stateMessage));
                } else {
                    return new ClusterState(false, currentTransactionId, Optional.empty());
                }
            } else {
                LOG.trace("Sync node not found [view: {}; txId: {}; states: {}]", view.viewId(),
                        currentTransactionId, nodesStates);
                return new ClusterState(true, currentTransactionId, Optional.empty());
            }
        } else {
            LOG.trace("Not all states received [view: {}; states: {}]", view.viewId(), nodesStates);
            if (view.viewId() == cluster.view().viewId()) {
                return execute(view);
            } else {
                return new ClusterState(true, currentTransactionId, Optional.empty());
            }
        }
    }

    @Override
    public void onMessage(Message message) {
        nodesStates.put(message.address(), (NodeState) message);
    }

    @Override
    public Set<Integer> interestedTypes() {
        return SUBSCRIPTION;
    }

    protected boolean allStatesReceived(ClusterView view, long currentTransactionId) {
        NodeState message = new NodeState(view.viewId(), currentTransactionId, config.revenoDataSync().mode().getType(),
                config.revenoDataSync().port());
        cluster.gateway().send(view.members(), message, cluster.gateway().oob());
        return RevenoUtils.waitFor(() -> nodesStates.keySet().containsAll(view.members()) && nodesStates.entrySet()
                        .stream()
                        .filter(kv -> view.members().contains(kv.getKey()))
                        .filter(kv -> kv.getValue().viewId == view.viewId()).count() == view.members().size(),
                config.revenoElectionTimeouts().ackTimeoutNanos());
    }

    public MessagingClusterStateCollector(Cluster cluster, Supplier<Long> transactionId, RevenoClusterConfiguration config) {
        this.cluster = cluster;
        this.transactionId = transactionId;
        this.config = config;
    }

    protected Cluster cluster;
    protected Supplier<Long> transactionId;
    protected RevenoClusterConfiguration config;

    protected Map<Address, NodeState> nodesStates = new ConcurrentHashMap<>();

    protected static final Logger LOG = LoggerFactory.getLogger(MessagingClusterStateCollector.class);
    protected static final Set<Integer> SUBSCRIPTION = new HashSet<Integer>() {{
        add(NodeState.TYPE);
    }};
    protected static final Comparator<NodeState> STATE_MESSAGE_COMPARATOR = (a, b) -> {
        if (a.transactionId > b.transactionId)
            return 1;
        else if (a.transactionId < b.transactionId)
            return -1;
        else
            return 0;
    };
}
