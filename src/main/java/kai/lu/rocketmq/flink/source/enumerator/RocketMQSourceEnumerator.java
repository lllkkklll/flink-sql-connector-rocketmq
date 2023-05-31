package kai.lu.rocketmq.flink.source.enumerator;

import kai.lu.rocketmq.flink.source.split.RocketMQPartitionSplit;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.connector.source.SplitsAssignment;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.StringUtils;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultLitePullConsumer;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.impl.MQClientManager;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.common.message.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.util.*;

import static kai.lu.rocketmq.flink.legacy.RocketMQConfig.*;

/**
 * The enumerator class for RocketMQ source.
 */
@Internal
public class RocketMQSourceEnumerator implements SplitEnumerator<RocketMQPartitionSplit, RocketMQSourceEnumState> {

    private static final Logger LOG = LoggerFactory.getLogger(RocketMQSourceEnumerator.class);

    private final Map<MessageQueue, Long> offsetTable = new HashMap<>();
    private final String consumerOffsetMode;
    private final long consumerOffsetTimestamp;
    /**
     * The topic used for this RocketMQSource.
     */
    private final String topic;
    /**
     * The consumer group used for this RocketMQSource.
     */
    private final String consumerGroup;
    /**
     * The name server address used for this RocketMQSource.
     */
    private final String nameServerAddress;
    /**
     * The stop timestamp for this RocketMQSource.
     */
    private final long stopInMs;
    /**
     * The start offset for this RocketMQSource.
     */
    private final long startOffset;
    /**
     * The partition discovery interval for this RocketMQSource.
     */
    private final long partitionDiscoveryIntervalMs;
    /**
     * The boundedness of this RocketMQSource.
     */
    private final Boundedness boundedness;

    /**
     * The accessKey used for this RocketMQSource.
     */
    private final String accessKey;
    /**
     * The secretKey used for this RocketMQSource.
     */
    private final String secretKey;

    private final SplitEnumeratorContext<RocketMQPartitionSplit> context;

    // The internal states of the enumerator.
    /**
     * This set is only accessed by the partition discovery callable in the callAsync() method, i.e
     * worker thread.
     */
    private final Set<Tuple3<String, String, Integer>> discoveredPartitions;
    /**
     * The current assignment by reader id. Only accessed by the coordinator thread.
     */
    private final Map<Integer, List<RocketMQPartitionSplit>> readerIdToSplitAssignments;
    /**
     * The discovered and initialized partition splits that are waiting for owner reader to be
     * ready.
     */
    private final Map<Integer, Set<RocketMQPartitionSplit>> pendingPartitionSplitAssignment;

    // Lazily instantiated or mutable fields.

    private DefaultLitePullConsumer consumer;

    protected MQClientInstance mQClientFactory;

    private boolean noMoreNewPartitionSplits = false;

    public RocketMQSourceEnumerator(
            String topic,
            String consumerGroup,
            String nameServerAddress,
            String accessKey,
            String secretKey,
            long stopInMs,
            long startOffset,
            long partitionDiscoveryIntervalMs,
            Boundedness boundedness,
            SplitEnumeratorContext<RocketMQPartitionSplit> context,
            String consumerOffsetMode,
            long consumerOffsetTimestamp) {
        this(
                topic,
                consumerGroup,
                nameServerAddress,
                accessKey,
                secretKey,
                stopInMs,
                startOffset,
                partitionDiscoveryIntervalMs,
                boundedness,
                context,
                new HashMap<>(),
                consumerOffsetMode,
                consumerOffsetTimestamp
        );
    }

    public RocketMQSourceEnumerator(
            String topic,
            String consumerGroup,
            String nameServerAddress,
            String accessKey,
            String secretKey,
            long stopInMs,
            long startOffset,
            long partitionDiscoveryIntervalMs,
            Boundedness boundedness,
            SplitEnumeratorContext<RocketMQPartitionSplit> context,
            Map<Integer, List<RocketMQPartitionSplit>> currentSplitsAssignments,
            String consumerOffsetMode,
            long consumerOffsetTimestamp) {
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.nameServerAddress = nameServerAddress;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.stopInMs = stopInMs;
        this.startOffset = startOffset;
        this.partitionDiscoveryIntervalMs = partitionDiscoveryIntervalMs;
        this.boundedness = boundedness;
        this.context = context;

        this.discoveredPartitions = new HashSet<>();
        this.readerIdToSplitAssignments = new HashMap<>(currentSplitsAssignments);
        this.readerIdToSplitAssignments.forEach(
                (reader, splits) -> splits.forEach(
                        s -> discoveredPartitions.add(
                                new Tuple3<>(s.getTopic(), s.getBroker(), s.getPartition())
                        )
                )
        );
        this.pendingPartitionSplitAssignment = new HashMap<>();
        this.consumerOffsetMode = consumerOffsetMode;
        this.consumerOffsetTimestamp = consumerOffsetTimestamp;
    }

    /**
     * Returns the index of the target subtask that a specific RocketMQ partition should be assigned
     * to.
     *
     * <p>The resulting distribution of partitions of a single topic has the following contract:
     *
     * <ul>
     *   <li>1. Uniformly distributed across subtasks
     *   <li>2. Partitions are round-robin distributed (strictly clockwise w.r.t. ascending subtask
     *       indices) by using the partition id as the offset from a starting index (i.e., the index
     *       of the subtask which partition 0 of the topic will be assigned to, determined using the
     *       topic name).
     * </ul>
     *
     * @param topic      the RocketMQ topic assigned.
     * @param broker     the RocketMQ broker assigned.
     * @param partition  the RocketMQ partition to assign.
     * @param numReaders the total number of readers.
     * @return the id of the subtask that owns the split.
     */
    @VisibleForTesting
    static int getSplitOwner(String topic, String broker, int partition, int numReaders) {
        int startIndex = (((topic + "-" + broker).hashCode() * 31) & 0x7FFFFFFF) % numReaders;

        // here, the assumption is that the id of RocketMQ partitions are always ascending
        // starting from 0, and therefore can be used directly as the offset clockwise from the
        // start index
        return (startIndex + partition) % numReaders;
    }

    @Override
    public void start() {
        initialRocketMQConsumer();
        LOG.info(
                "Starting the RocketMQSourceEnumerator for consumer group {} "
                        + "with partition discovery interval of {} ms.",
                consumerGroup,
                partitionDiscoveryIntervalMs
        );
        context.callAsync(
                this::discoverAndInitializePartitionSplit,
                this::handlePartitionSplitChanges,
                0,
                partitionDiscoveryIntervalMs
        );
    }

    @Override
    public void handleSplitRequest(int subtaskId, @Nullable String requesterHostname) {
        // the RocketMQ source pushes splits eagerly, rather than act upon split requests
    }

    @Override
    public void addSplitsBack(List<RocketMQPartitionSplit> splits, int subtaskId) {
        addPartitionSplitChangeToPendingAssignments(splits);
        assignPendingPartitionSplits();
    }

    @Override
    public void addReader(int subtaskId) {
        LOG.debug(
                "Adding reader {} to RocketMQSourceEnumerator for consumer group {}.",
                subtaskId,
                consumerGroup
        );
        assignPendingPartitionSplits();
        if (boundedness == Boundedness.BOUNDED) {
            // for RocketMQ bounded source, send this signal to ensure the task can end after all
            // the
            // splits assigned are completed.
            context.signalNoMoreSplits(subtaskId);
        }
    }

    @Override
    public RocketMQSourceEnumState snapshotState(long checkpointId) {
        return new RocketMQSourceEnumState(readerIdToSplitAssignments);
    }

    // ----------------- private methods -------------------

    @Override
    public void close() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    private Set<RocketMQPartitionSplit> discoverAndInitializePartitionSplit() throws MQClientException {
        Set<Tuple3<String, String, Integer>> newPartitions = new HashSet<>();
        Set<Tuple3<String, String, Integer>> removedPartitions = new HashSet<>(
                Collections.unmodifiableSet(discoveredPartitions));

        Collection<MessageQueue> messageQueues = consumer.fetchMessageQueues(topic);
        Set<RocketMQPartitionSplit> result = new HashSet<>();
        consumer.assign(messageQueues);

        for (MessageQueue messageQueue : messageQueues) {
            Tuple3<String, String, Integer> topicPartition = new Tuple3<>(
                    messageQueue.getTopic(),
                    messageQueue.getBrokerName(),
                    messageQueue.getQueueId()
            );
            if (!removedPartitions.remove(topicPartition)) {
                newPartitions.add(topicPartition);
                result.add(new RocketMQPartitionSplit(
                        topicPartition.f0,
                        topicPartition.f1,
                        topicPartition.f2,
                        getOffsetByMessageQueue(messageQueue),
                        stopInMs
                ));
            }
        }
        discoveredPartitions.addAll(Collections.unmodifiableSet(newPartitions));
        return result;
    }

    // This method should only be invoked in the coordinator executor thread.
    private void handlePartitionSplitChanges(Set<RocketMQPartitionSplit> partitionSplits, Throwable t) {
        if (t != null) {
            throw new FlinkRuntimeException("Failed to handle partition splits change due to ", t);
        }
        if (partitionDiscoveryIntervalMs < 0) {
            noMoreNewPartitionSplits = true;
        }
        addPartitionSplitChangeToPendingAssignments(partitionSplits);
        assignPendingPartitionSplits();
    }

    // This method should only be invoked in the coordinator executor thread.
    private void addPartitionSplitChangeToPendingAssignments(Collection<RocketMQPartitionSplit> newPartitionSplits) {
        int numReaders = context.currentParallelism();
        for (RocketMQPartitionSplit split : newPartitionSplits) {
            int ownerReader = getSplitOwner(
                    split.getTopic(),
                    split.getBroker(),
                    split.getPartition(),
                    numReaders
            );
            pendingPartitionSplitAssignment.computeIfAbsent(ownerReader, r -> new HashSet<>()).add(split);
        }
        LOG.debug(
                "Assigned {} to {} readers of consumer group {}.",
                newPartitionSplits,
                numReaders,
                consumerGroup
        );
    }

    // This method should only be invoked in the coordinator executor thread.
    private void assignPendingPartitionSplits() {
        Map<Integer, List<RocketMQPartitionSplit>> incrementalAssignment = new HashMap<>();
        pendingPartitionSplitAssignment.forEach(
                (ownerReader, pendingSplits) -> {
                    if (!pendingSplits.isEmpty() && context.registeredReaders().containsKey(ownerReader)) {
                        // The owner reader is ready, assign the split to the owner reader.
                        incrementalAssignment
                                .computeIfAbsent(ownerReader, r -> new ArrayList<>())
                                .addAll(pendingSplits);
                    }
                });
        if (incrementalAssignment.isEmpty()) {
            // No assignment is made.
            return;
        }

        LOG.info("Assigning splits to readers {}", incrementalAssignment);
        context.assignSplits(new SplitsAssignment<>(incrementalAssignment));
        incrementalAssignment.forEach(
                (readerOwner, newPartitionSplits) -> {
                    // Update the split assignment.
                    readerIdToSplitAssignments
                            .computeIfAbsent(readerOwner, r -> new ArrayList<>())
                            .addAll(newPartitionSplits);
                    // Clear the pending splits for the reader owner.
                    pendingPartitionSplitAssignment.remove(readerOwner);
                    // Sends NoMoreSplitsEvent to the readers if there is no more partition splits
                    // to be assigned.
                    if (noMoreNewPartitionSplits) {
                        LOG.debug(
                                "No more RocketMQPartitionSplits to assign. Sending NoMoreSplitsEvent to the readers "
                                        + "in consumer group {}.",
                                consumerGroup);
                        context.signalNoMoreSplits(readerOwner);
                    }
                });
    }

    private long getOffsetByMessageQueue(MessageQueue mq) throws MQClientException {
        Long offset = offsetTable.get(mq);

        if (offset == null) {
            if (startOffset > 0) {
                offset = startOffset;
            } else {
                switch (consumerOffsetMode) {
                    case CONSUMER_OFFSET_EARLIEST:
                        offset = mQClientFactory.getMQAdminImpl().minOffset(mq);
                        break;
                    case CONSUMER_OFFSET_LATEST:
                        offset = mQClientFactory.getMQAdminImpl().maxOffset(mq);
                        break;
                    case CONSUMER_OFFSET_TIMESTAMP:
                        offset = consumer.offsetForTimestamp(mq, consumerOffsetTimestamp);

                        if (offset < 0) {
                            throw new IllegalArgumentException("Unknown value for CONSUMER_TIMESTAMP: " + consumerOffsetTimestamp + ".");
                        }
                        break;
                    default:
                        offset = consumer.committed(mq);

                        if (offset < 0) {
                            throw new IllegalArgumentException("Unknown value for CONSUMER_OFFSET_RESET_TO.");
                        }
                }
            }
        }
        offsetTable.put(mq, offset);

        return offsetTable.get(mq);
    }

    private void initialRocketMQConsumer() {
        try {
            AclClientRPCHook aclClientRPCHook = null;

            if (!StringUtils.isNullOrWhitespaceOnly(accessKey) && !StringUtils.isNullOrWhitespaceOnly(secretKey)) {
                aclClientRPCHook = new AclClientRPCHook(new SessionCredentials(accessKey, secretKey));
            }
            consumer = new DefaultLitePullConsumer(consumerGroup, aclClientRPCHook);
            consumer.setNamesrvAddr(nameServerAddress);
            consumer.setInstanceName(
                    String.join(
                            "||",
                            ManagementFactory.getRuntimeMXBean().getName(),
                            topic,
                            consumerGroup,
                            "" + System.nanoTime()));
            consumer.start();

            mQClientFactory = MQClientManager.getInstance().getOrCreateMQClientInstance(consumer, aclClientRPCHook);
        } catch (MQClientException e) {
            LOG.error("Failed to initial RocketMQ consumer.", e);
            consumer.shutdown();
        }
    }
}