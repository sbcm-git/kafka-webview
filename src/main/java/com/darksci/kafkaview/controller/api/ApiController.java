package com.darksci.kafkaview.controller.api;

import com.darksci.kafkaview.controller.BaseController;
import com.darksci.kafkaview.manager.kafka.KafkaAdminFactory;
import com.darksci.kafkaview.manager.kafka.KafkaOperations;
import com.darksci.kafkaview.manager.kafka.config.ClientConfig;
import com.darksci.kafkaview.manager.kafka.config.ClusterConfig;
import com.darksci.kafkaview.manager.kafka.config.DeserializerConfig;
import com.darksci.kafkaview.manager.kafka.config.TopicConfig;
import com.darksci.kafkaview.manager.kafka.dto.ConsumerState;
import com.darksci.kafkaview.manager.kafka.dto.KafkaResults;
import com.darksci.kafkaview.manager.kafka.dto.NodeDetails;
import com.darksci.kafkaview.manager.kafka.dto.NodeList;
import com.darksci.kafkaview.manager.kafka.dto.TopicDetails;
import com.darksci.kafkaview.manager.kafka.dto.TopicList;
import com.darksci.kafkaview.manager.kafka.KafkaConsumerFactory;
import com.darksci.kafkaview.manager.kafka.TransactionalKafkaClient;
import com.darksci.kafkaview.manager.kafka.config.FilterConfig;
import com.darksci.kafkaview.manager.kafka.dto.TopicListing;
import com.darksci.kafkaview.manager.kafka.filter.AFilter;
import com.darksci.kafkaview.manager.plugin.DeserializerLoader;
import com.darksci.kafkaview.manager.plugin.exception.LoaderException;
import com.darksci.kafkaview.manager.ui.FlashMessage;
import com.darksci.kafkaview.model.Cluster;
import com.darksci.kafkaview.model.MessageFormat;
import com.darksci.kafkaview.model.View;
import com.darksci.kafkaview.repository.ClusterRepository;
import com.darksci.kafkaview.repository.ViewRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles API requests.
 */
@Controller
@RequestMapping("/api")
public class ApiController {
    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);

    @Autowired
    private ViewRepository viewRepository;

    @Autowired
    private DeserializerLoader deserializerLoader;

    @Autowired
    private ClusterRepository clusterRepository;

    /**
     * GET kafka results
     */
    @RequestMapping(path = "/consumer/view/{id}", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public KafkaResults consume(
        final @PathVariable Long id,
        final @RequestParam(name = "action", required = false) String action,
        final @RequestParam(name = "partitions", required = false) String partitions) {

        // Retrieve the view definition
        final View view = viewRepository.findOne(id);
        if (view == null) {
            // TODO Return some kind of error.
        }

        // Create consumer
        final KafkaResults results;
        try (final TransactionalKafkaClient transactionalKafkaClient = setup(view)) {
            // move directions if needed
            if ("next".equals(action)) {
                // Do nothing!
                //transactionalKafkaClient.next();
            } else if ("previous".equals(action)) {
                transactionalKafkaClient.previous();
            } else if ("head".equals(action)) {
                transactionalKafkaClient.toHead();
            } else if ("tail".equals(action)) {
                transactionalKafkaClient.toTail();
            }

            // Poll
            results = transactionalKafkaClient.consumePerPartition();
        }
        return results;
    }

    /**
     * POST manually set a consumer's offsets.
     */
    @RequestMapping(path = "/consumer/view/{id}/offsets", method = RequestMethod.POST, produces = "application/json")
    @ResponseBody
    public ConsumerState setConsumerOffsets(final @PathVariable Long id, final @RequestBody Map<Integer, Long> partitionOffsetMap) {
        // Retrieve the view definition
        final View view = viewRepository.findOne(id);
        if (view == null) {
            // TODO Return some kind of error.
        }

        // Create consumer
        try (final TransactionalKafkaClient transactionalKafkaClient = setup(view)) {
            return transactionalKafkaClient.seek(partitionOffsetMap);
        }
    }

    /**
     * GET listing of all available kafka topics for a requested cluster.
     */
    @RequestMapping(path = "/cluster/{id}/topics/list", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public List<TopicListing> getTopics(final @PathVariable Long id) {
        // Retrieve cluster
        final Cluster cluster = clusterRepository.findOne(id);
        if (cluster == null) {
            // Handle error by returning empty list?
            new ArrayList<>();
        }

        // Create new Operational Client
        try (final KafkaOperations operations = createOperationsClient(cluster)) {
            final TopicList topics = operations.getAvailableTopics();
            return topics.getTopics();
        }
    }

    /**
     * GET Topic Details
     */
    @RequestMapping(path = "/cluster/{id}/topic/{topic}/details", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public TopicDetails getTopicDetails(final @PathVariable Long id, final @PathVariable String topic) {
        // Retrieve cluster
        final Cluster cluster = clusterRepository.findOne(id);
        if (cluster == null) {
            // Handle error by returning empty list?
            new ArrayList<>();
        }

        // Create new Operational Client
        try (final KafkaOperations operations = createOperationsClient(cluster)) {
            final TopicDetails topicDetails = operations.getTopicDetails(topic);
            return topicDetails;
        }
    }

    /**
     * GET Nodes within a cluster.
     */
    @RequestMapping(path = "/cluster/{id}/nodes", method = RequestMethod.GET, produces = "application/json")
    @ResponseBody
    public List<NodeDetails> getClusterNodes(final @PathVariable Long id) {
        // Retrieve cluster
        final Cluster cluster = clusterRepository.findOne(id);
        if (cluster == null) {
            // Handle error by returning empty list?
            new ArrayList<>();
        }

        try (final KafkaOperations operations = createOperationsClient(cluster)) {
            final NodeList nodes = operations.getClusterNodes();
            return nodes.getNodes();
        }
    }

    private KafkaOperations createOperationsClient(final Cluster cluster) {
        // Create new Operational Client
        final ClusterConfig clusterConfig = new ClusterConfig(cluster.getBrokerHosts());
        final AdminClient adminClient = new KafkaAdminFactory(clusterConfig, "BobsYerAunty").create();

        return new KafkaOperations(adminClient);
    }

    private TransactionalKafkaClient setup(final View view) {
        // Construct a consumerId based on user
        final String consumerId = "MyUserId1";

        // Grab our relevant bits
        final Cluster cluster = view.getCluster();
        final MessageFormat keyMessageFormat = view.getKeyMessageFormat();
        final MessageFormat valueMessageFormat = view.getValueMessageFormat();

        final Class keyDeserializerClass;
        try {
            if (keyMessageFormat.isDefaultFormat()) {
                keyDeserializerClass = deserializerLoader.getDeserializerClass(keyMessageFormat.getClasspath());
            } else {
                keyDeserializerClass = deserializerLoader.getDeserializerClass(keyMessageFormat.getJar(), keyMessageFormat.getClasspath());
            }
        } catch (final LoaderException exception) {
            throw new RuntimeException(exception.getMessage(), exception);
        }

        final Class valueDeserializerClass;
        try {
            if (valueMessageFormat.isDefaultFormat()) {
                valueDeserializerClass = deserializerLoader.getDeserializerClass(valueMessageFormat.getClasspath());
            } else {
                valueDeserializerClass = deserializerLoader.getDeserializerClass(valueMessageFormat.getJar(), valueMessageFormat.getClasspath());
            }
        } catch (final LoaderException exception) {
            throw new RuntimeException(exception.getMessage(), exception);
        }

        final ClusterConfig clusterConfig = new ClusterConfig(cluster.getBrokerHosts());
        final DeserializerConfig deserializerConfig = new DeserializerConfig(keyDeserializerClass, valueDeserializerClass);
        final TopicConfig topicConfig = new TopicConfig(clusterConfig, deserializerConfig, view.getTopic());

        final ClientConfig clientConfig = ClientConfig.newBuilder()
            .withTopicConfig(topicConfig)
            .withNoFilters()
            .withConsumerId(consumerId)
            .withPartitions(view.getPartitionsAsSet())
            .build();

        // Create the damn consumer
        final KafkaConsumerFactory kafkaConsumerFactory = new KafkaConsumerFactory(clientConfig);
        final KafkaConsumer kafkaConsumer = kafkaConsumerFactory.createAndSubscribe();

        // Create consumer
        final KafkaResults results;
        return new TransactionalKafkaClient(kafkaConsumer, clientConfig);
    }

    private ClusterConfig getClusterConfig() {
        return new ClusterConfig("localhost:9092");
    }
}