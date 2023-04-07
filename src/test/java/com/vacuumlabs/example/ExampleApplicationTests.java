package com.vacuumlabs.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.api.Assertions;
import org.assertj.core.util.IterableUtil;
import org.awaitility.Awaitility;
import org.awaitility.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.math.BigDecimal;
import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"management.metrics.export.prometheus.enabled=true"})
@AutoConfigureMockMvc(print = MockMvcPrint.DEFAULT, printOnlyOnFailure = false)
@Testcontainers
class ExampleApplicationTests {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MessageRepository messageRepository;

    @Container
    private static DockerComposeContainer dcc = new DockerComposeContainer(new File("docker-compose.yaml"))
        .withLocalCompose(true)
        .withOptions("--compatibility")
        .withExposedService("kafka", 9092, Wait.forListeningPort())
        .withExposedService("postgres", 5432, Wait.forListeningPort());


    @Test
    public void contextLoads() {
    }

    @Test
    public void getMessages() throws Exception {
        mockMvc.perform(get("/messages"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }

    @Test
    public void healthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    @Test
    public void metricsEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk());
    }

    @Test
    public void newTransaction_invalid() throws Exception {
        postNewTransaction(
            new TransactionDto(11, null, null, null)
        ).andExpect(status().isBadRequest());
    }

    @Test
    @DirtiesContext
    public void newTransaction_valid() throws Exception {
        postNewTransaction(
            new TransactionDto(1, "ACC-123456", new BigDecimal(1000), "Test transaction")
        ).andExpect(status().isOk());

        Awaitility.await().pollDelay(Duration.ONE_HUNDRED_MILLISECONDS).atMost(Duration.FIVE_SECONDS).until(() -> IterableUtil.sizeOf(messageRepository.findAll()) > 0);

        Assertions.assertThat(messageRepository.findAll()).isEqualTo(IterableUtil.iterable(new MessageEntity(1L, "Test transaction")));
    }

    @Test
    @DirtiesContext
    public void newTransaction_valid_nonexistentAccountNumber() throws Exception {
        postNewTransaction(
            new TransactionDto(1, "ACC-654321", new BigDecimal(1000), "Test transaction")
        ).andExpect(status().isOk());

        var record = awaitRecord("error.test-topic.message-saver");
        var exceptionMessage = new String(record.headers().lastHeader("x-exception-message").value());
        Assertions.assertThat(exceptionMessage).endsWith("Account doesn't exist: ACC-654321");
        Assertions.assertThat(messageRepository.findAll()).isEmpty();
    }

    private ResultActions postNewTransaction(TransactionDto transactionDto) throws Exception {
        return mockMvc.perform(post("/transactions")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(transactionDto)));
    }

    private ConsumerRecord<String, String> awaitRecord(String topic) {
        var props = KafkaTestUtils.consumerProps(
            "localhost:9092",
            this.getClass().getName(),
            "false"
        );
        try (var consumer = new KafkaConsumer<String,String>(props)) {
            consumer.assign(Collections.singleton(new TopicPartition(topic, 0)));
            return KafkaTestUtils.getSingleRecord(consumer, topic, java.time.Duration.ofSeconds(10).toMillis());
        }
    }
}
