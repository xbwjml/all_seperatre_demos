package com.example.demo.localMsgTable.payStock.scheduler;

import com.example.demo.localMsgTable.payStock.domain.LocalMessage;
import com.example.demo.localMsgTable.payStock.enums.MsgStatus;
import com.example.demo.localMsgTable.payStock.mq.StockMessageProducer;
import com.example.demo.localMsgTable.payStock.repository.LocalMessageRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class MsgCompensateScheduler {

    private static final int BATCH_SIZE = 50;
    private static final int BASE_DELAY_SECONDS = 10;
    private static final String LOCK_KEY = "pay-stock:compensate-lock";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final LocalMessageRepository msgRepo;
    private final StockMessageProducer producer;
    private final StringRedisTemplate redisTemplate;

    private final ExecutorService sendExecutor = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            Thread.ofVirtual().name("compensate-send-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    public MsgCompensateScheduler(LocalMessageRepository msgRepo,
                                  StockMessageProducer producer,
                                  StringRedisTemplate redisTemplate) {
        this.msgRepo = msgRepo;
        this.producer = producer;
        this.redisTemplate = redisTemplate;
    }

    @PreDestroy
    public void shutdown() {
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                sendExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sendExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(fixedDelay = 10_000)
    public void compensate() {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }

        try {
            doCompensate();
        } finally {
            redisTemplate.delete(LOCK_KEY);
        }
    }

    private void doCompensate() {
        List<LocalMessage> pendingMessages = msgRepo.findPendingMessages(LocalDateTime.now(), BATCH_SIZE);
        if (pendingMessages.isEmpty()) {
            return;
        }

        log.info("[PayStock] Compensating {} pending messages", pendingMessages.size());

        CompletableFuture<?>[] futures = pendingMessages.stream()
                .map(msg -> CompletableFuture.runAsync(() -> processSingleMessage(msg), sendExecutor))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).join();
    }

    private void processSingleMessage(LocalMessage msg) {
        try {
            if (msg.getRetryCount() >= msg.getMaxRetry()) {
                msgRepo.markDead(msg.getId());
                log.warn("[PayStock] Message marked DEAD after {} retries: id={}, orderId={}",
                        msg.getRetryCount(), msg.getId(), msg.getOrderId());
                return;
            }

            boolean sent = producer.send(msg);
            if (sent) {
                msgRepo.updateStatus(msg.getId(), MsgStatus.SENT);
                log.info("[PayStock] Compensate sent successfully: id={}, orderId={}", msg.getId(), msg.getOrderId());
            } else {
                long delaySeconds = (long) Math.pow(2, msg.getRetryCount()) * BASE_DELAY_SECONDS;
                LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(delaySeconds);
                msgRepo.incrementRetryAndSetNextTime(msg.getId(), nextRetry);
                log.warn("[PayStock] Compensate send failed, retry scheduled at {}: id={}, orderId={}",
                        nextRetry, msg.getId(), msg.getOrderId());
            }
        } catch (Exception e) {
            log.error("[PayStock] Unexpected error processing message: id={}, orderId={}",
                    msg.getId(), msg.getOrderId(), e);
        }
    }
}
