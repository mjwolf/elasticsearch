/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.TransportVersions;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.Diff;
import org.elasticsearch.cluster.node.DiscoveryNodeUtils;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ChunkedToXContentDiffableSerializationTestCase;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.SingleNodeShutdownMetadata.Type.SIGTERM;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class NodesShutdownMetadataTests extends ChunkedToXContentDiffableSerializationTestCase<Metadata.ClusterCustom> {

    public void testInsertNewNodeShutdownMetadata() {
        NodesShutdownMetadata nodesShutdownMetadata = new NodesShutdownMetadata(new HashMap<>());
        SingleNodeShutdownMetadata newNodeMetadata = randomNodeShutdownInfo();

        nodesShutdownMetadata = nodesShutdownMetadata.putSingleNodeMetadata(newNodeMetadata);

        assertThat(nodesShutdownMetadata.get(newNodeMetadata.getNodeId()), equalTo(newNodeMetadata));
        assertThat(nodesShutdownMetadata.getAll().values(), contains(newNodeMetadata));
    }

    public void testRemoveShutdownMetadata() {
        NodesShutdownMetadata nodesShutdownMetadata = new NodesShutdownMetadata(new HashMap<>());
        List<SingleNodeShutdownMetadata> nodes = randomList(1, 20, this::randomNodeShutdownInfo);

        for (SingleNodeShutdownMetadata node : nodes) {
            nodesShutdownMetadata = nodesShutdownMetadata.putSingleNodeMetadata(node);
        }

        SingleNodeShutdownMetadata nodeToRemove = randomFrom(nodes);
        nodesShutdownMetadata = nodesShutdownMetadata.removeSingleNodeMetadata(nodeToRemove.getNodeId());

        assertThat(nodesShutdownMetadata.get(nodeToRemove.getNodeId()), nullValue());
        assertThat(nodesShutdownMetadata.getAll().values(), hasSize(nodes.size() - 1));
        assertThat(nodesShutdownMetadata.getAll().values(), not(hasItem(nodeToRemove)));
    }

    public void testIsNodeShuttingDown() {
        for (SingleNodeShutdownMetadata.Type type : List.of(
            SingleNodeShutdownMetadata.Type.RESTART,
            SingleNodeShutdownMetadata.Type.REMOVE,
            SingleNodeShutdownMetadata.Type.SIGTERM
        )) {
            NodesShutdownMetadata nodesShutdownMetadata = new NodesShutdownMetadata(
                Collections.singletonMap(
                    "this_node",
                    SingleNodeShutdownMetadata.builder()
                        .setNodeId("this_node")
                        .setNodeEphemeralId("this_node")
                        .setReason("shutdown for a unit test")
                        .setType(type)
                        .setStartedAtMillis(randomNonNegativeLong())
                        .setGracePeriod(type == SIGTERM ? randomTimeValue() : null)
                        .build()
                )
            );

            DiscoveryNodes.Builder nodes = DiscoveryNodes.builder();
            nodes.add(DiscoveryNodeUtils.create("this_node"));
            nodes.localNodeId("this_node");
            nodes.masterNodeId("this_node");

            ClusterState state = ClusterState.builder(ClusterName.DEFAULT).nodes(nodes).build();

            state = ClusterState.builder(state)
                .metadata(Metadata.builder(state.metadata()).putCustom(NodesShutdownMetadata.TYPE, nodesShutdownMetadata).build())
                .nodes(DiscoveryNodes.builder(state.nodes()).add(DiscoveryNodeUtils.create("_node_1")).build())
                .build();

            assertThat(state.metadata().nodeShutdowns().contains("this_node"), equalTo(true));
            assertThat(state.metadata().nodeShutdowns().contains("_node_1"), equalTo(false));
        }
    }

    public void testSigtermIsRemoveInOlderVersions() throws IOException {
        SingleNodeShutdownMetadata metadata = SingleNodeShutdownMetadata.builder()
            .setNodeId("myid")
            .setNodeEphemeralId("myid")
            .setType(SingleNodeShutdownMetadata.Type.SIGTERM)
            .setReason("myReason")
            .setStartedAtMillis(0L)
            .setGracePeriod(new TimeValue(1_000))
            .build();
        BytesStreamOutput out = new BytesStreamOutput();
        out.setTransportVersion(TransportVersions.V_8_7_1);
        metadata.writeTo(out);
        StreamInput in = out.bytes().streamInput();
        in.setTransportVersion(TransportVersions.V_8_7_1);
        assertThat(new SingleNodeShutdownMetadata(in).getType(), equalTo(SingleNodeShutdownMetadata.Type.REMOVE));

        out = new BytesStreamOutput();
        metadata.writeTo(out);
        assertThat(new SingleNodeShutdownMetadata(out.bytes().streamInput()).getType(), equalTo(SingleNodeShutdownMetadata.Type.SIGTERM));
    }

    public void testIsNodeMarkedForRemoval() {
        SingleNodeShutdownMetadata.Type type;
        SingleNodeShutdownMetadata.Builder builder = SingleNodeShutdownMetadata.builder()
            .setNodeId("thenode")
            .setNodeEphemeralId("thenode")
            .setReason("myReason")
            .setStartedAtMillis(0L);
        switch (type = randomFrom(SingleNodeShutdownMetadata.Type.values())) {
            case SIGTERM -> {
                var metadata = new NodesShutdownMetadata(
                    Map.of("thenode", builder.setType(type).setGracePeriod(TimeValue.ONE_MINUTE).build())
                );
                assertThat(metadata.isNodeMarkedForRemoval("thenode"), is(true));
                assertThat(metadata.isNodeMarkedForRemoval("anotherNode"), is(false));
            }
            case REMOVE -> {
                var metadata = new NodesShutdownMetadata(Map.of("thenode", builder.setType(type).build()));
                assertThat(metadata.isNodeMarkedForRemoval("thenode"), is(true));
                assertThat(metadata.isNodeMarkedForRemoval("anotherNode"), is(false));
            }
            case REPLACE -> {
                var metadata = new NodesShutdownMetadata(
                    Map.of("thenode", builder.setType(type).setTargetNodeName("newnodecoming").build())
                );
                assertThat(metadata.isNodeMarkedForRemoval("thenode"), is(true));
                assertThat(metadata.isNodeMarkedForRemoval("anotherNode"), is(false));
            }
            case RESTART -> {
                var metadata = new NodesShutdownMetadata(Map.of("thenode", builder.setType(type).build()));
                assertThat(metadata.isNodeMarkedForRemoval("thenode"), is(false));
            }
        }
    }

    @Override
    protected Writeable.Reader<Diff<Metadata.ClusterCustom>> diffReader() {
        return NodesShutdownMetadata.NodeShutdownMetadataDiff::new;
    }

    @Override
    protected NodesShutdownMetadata doParseInstance(XContentParser parser) throws IOException {
        return NodesShutdownMetadata.fromXContent(parser);
    }

    @Override
    protected Writeable.Reader<Metadata.ClusterCustom> instanceReader() {
        return NodesShutdownMetadata::new;
    }

    @Override
    protected NodesShutdownMetadata createTestInstance() {
        Map<String, SingleNodeShutdownMetadata> nodes = randomList(0, 10, this::randomNodeShutdownInfo).stream()
            .collect(Collectors.toMap(SingleNodeShutdownMetadata::getNodeId, Function.identity()));
        return new NodesShutdownMetadata(nodes);
    }

    private SingleNodeShutdownMetadata randomNodeShutdownInfo() {
        final SingleNodeShutdownMetadata.Type type = randomFrom(SingleNodeShutdownMetadata.Type.values());
        final SingleNodeShutdownMetadata.Builder builder = SingleNodeShutdownMetadata.builder()
            .setNodeId(randomAlphaOfLength(5))
            .setNodeEphemeralId(randomAlphaOfLength(5))
            .setType(type)
            .setReason(randomAlphaOfLength(5))
            .setStartedAtMillis(randomNonNegativeLong());
        if (type.equals(SingleNodeShutdownMetadata.Type.RESTART) && randomBoolean()) {
            builder.setAllocationDelay(randomTimeValue());
        } else if (type.equals(SingleNodeShutdownMetadata.Type.REPLACE)) {
            builder.setTargetNodeName(randomAlphaOfLengthBetween(5, 10));
        } else if (type.equals(SingleNodeShutdownMetadata.Type.SIGTERM)) {
            builder.setGracePeriod(randomTimeValue());
        }
        return builder.setNodeSeen(randomBoolean()).build();
    }

    @Override
    protected Metadata.ClusterCustom makeTestChanges(Metadata.ClusterCustom testInstance) {
        return randomValueOtherThan(testInstance, this::createTestInstance);
    }

    @Override
    protected Metadata.ClusterCustom mutateInstance(Metadata.ClusterCustom instance) {
        return makeTestChanges(instance);
    }
}
