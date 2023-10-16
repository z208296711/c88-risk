package com.c88.risk.config;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static com.c88.common.core.constant.TopicConstants.*;
import static com.c88.risk.contant.StoreConstants.*;


@Configuration
public class KafkaConfig {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    private Admin admin;

    private static final int defaultPartitions = 3;
    Map<String, Integer> topicPartitions = Map.of(
            BET_RECORD, defaultPartitions,
            WITHDRAW, defaultPartitions,
            WITHDRAW_APPLY, defaultPartitions,
            RISK_CONFIG, defaultPartitions,
            ASSOCIATION_CONFIG, defaultPartitions);// topic 初始化 partitions

    List<Map<String, String>> topicStores = List.of(Map.of(BET_RECORD, AGG_BET_RECORD),
            Map.of(BET_RECORD, MONTHLY_AGG_BET_RECORD),
            Map.of(WITHDRAW_APPLY, AGG_BET_RECORD_FROM_LAST_WITHDRAW),
            Map.of(BET_RECORD, DAILY_AGG_BET_RECORD));// topic 對應的 state store

    /**
     * 初始化topic partitions，並同步對應的 state store partitions
     */
    @PostConstruct
    public void init() {
        Properties properties = new Properties();
        properties.put(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress
        );
        admin = Admin.create(properties);
        try {
            ListTopicsResult topics = admin.listTopics();
            Set<String> topicNames = topics.names().get();

            short replicationFactor = 1;
            for (String topicName : topicPartitions.keySet()) {
                int partitions = topicPartitions.get(topicName);
                if (topicNames.contains(topicName)) {
                    setTopicPartitions(topicName, partitions);
                } else {
                    createTopic(topicName, partitions, replicationFactor);
                }

                String storeName = topicStores.stream()
                        .filter(x -> x.containsKey(topicName))
                        .map(x -> x.get(topicName))
                        .findFirst().orElse(null);

//                String storeName = topicStores.get(topicName);
                if (storeName != null) {
                    topicNames
                            .parallelStream()
                            .filter(name -> name.contains('-' + storeName + '-'))
                            .forEach(name -> {
                                try {
                                    setTopicPartitions(name, partitions);
                                } catch (ExecutionException | InterruptedException e) {
                                    e.printStackTrace();
                                }
                            });
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setTopicPartitions(String topicName, int partitions) throws ExecutionException, InterruptedException {
        TopicDescription topicDescription = admin.describeTopics(Collections.singleton(topicName)).values().get(topicName).get();
        if (topicDescription.partitions().size() < partitions) {
            Map<String, NewPartitions> newPartitions = Map.of(topicName, NewPartitions.increaseTo(partitions));
            CreatePartitionsResult result = admin.createPartitions(newPartitions);
            KafkaFuture<Void> future = result.values().get(topicName);
            future.get();
        }
    }

    private void createTopic(String topicName, int partitions, short replicationFactor) throws ExecutionException, InterruptedException {
        NewTopic newTopic = new NewTopic(topicName, partitions, replicationFactor);
        CreateTopicsResult result = admin.createTopics(
                Collections.singleton(newTopic)
        );
        KafkaFuture<Void> future = result.values().get(topicName);
        future.get();
    }

}
