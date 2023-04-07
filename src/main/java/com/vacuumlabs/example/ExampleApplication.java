package com.vacuumlabs.example;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;

@SpringBootApplication
public class ExampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}

@RestController
class ExampleController {

    @Autowired
    private StreamBridge streamBridge;
    @Autowired
    private MessageRepository messageRepository;


    @PostMapping({"/transactions"})
    public void transactionSender(@Valid @RequestBody @NotNull TransactionDto transactionDto) {
        streamBridge.send("transaction-sender-out-0", transactionDto);
    }

    @GetMapping("/messages")
    public Iterable<MessageEntity> getMessages() {
        return messageRepository.findAll();
    }
}

@Configuration
class KafkaConfiguration {
    @Bean
    public Consumer<TransactionDto> messageSaver(MessageRepository messageRepository) {
        return message -> {
            if (!message.accountNumber().equals("ACC-123456")) {
                throw new IllegalArgumentException("Account doesn't exist: " + message.accountNumber());
            }
            messageRepository.save(new MessageEntity(null, message.description()));
        };
    }
}

record TransactionDto(
    @Min(0)
    @Max(10)
    Integer priority,

    @Pattern(regexp = "ACC-[0-9]{6}")
    String accountNumber,

    @NotNull
    BigDecimal amount,

    @NotBlank(message = "Message is mandatory")
    String description
) {
}

@Repository
interface MessageRepository extends CrudRepository<MessageEntity, Long> {
}

@Entity
class MessageEntity {
    @Id
    @GeneratedValue
    private Long id;
    private String message;

    public MessageEntity() {
    }

    public MessageEntity(Long id, String message) {
        this.id = id;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageEntity that = (MessageEntity) o;
        return Objects.equals(id, that.id) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, message);
    }
}
