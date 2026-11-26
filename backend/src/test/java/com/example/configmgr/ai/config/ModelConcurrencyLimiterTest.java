package com.example.configmgr.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.ReasoningAwareOpenAiChatModel;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelConcurrencyLimiterTest {

    @Test
    void allModelCallsShareTenPermitsAndBoundedQueue() throws Exception {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel delegate = prompt -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            calls.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release timed out");
                }
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test call interrupted", ex);
            }
            finally {
                active.decrementAndGet();
            }
            return new ChatResponse(List.of());
        };
        ChatModel model = new ConcurrencyLimitedChatModel(delegate, limiter);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 12; index++) {
                futures.add(executor.submit(() -> model.call((Prompt) null)));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(10);
            assertThat(maximum).hasValue(10);
            release.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(calls).hasValue(12);
        assertThat(maximum).hasValue(10);
    }

    @Test
    void modelFailureReleasesPermitForTheNextCall() {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = prompt -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("upstream failed");
            }
            return new ChatResponse(List.of());
        };
        ChatModel model = new ConcurrencyLimitedChatModel(delegate, limiter);

        assertThatThrownBy(() -> model.call((Prompt) null)).isInstanceOf(IllegalStateException.class);
        assertThat(model.call((Prompt) null)).isNotNull();
        assertThat(calls).hasValue(2);
    }

    @Test
    void interruptedPermitWaitRestoresInterruptWithoutCallingTheModel() {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter();
        AtomicInteger calls = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet();
                return new ChatResponse(List.of());
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.never();
            }
        };
        ChatModel model = new ConcurrencyLimitedChatModel(delegate, limiter);
        List<Disposable> subscriptions = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                subscriptions.add(model.stream((Prompt) null).subscribe());
            }
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> model.call((Prompt) null)).isInstanceOf(IllegalStateException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        }
        finally {
            Thread.interrupted();
            subscriptions.forEach(Disposable::dispose);
        }
    }

    @Test
    void reasoningReplayCapabilitySurvivesDecoration() {
        ReasoningAwareOpenAiChatModel delegate = org.mockito.Mockito.mock(ReasoningAwareOpenAiChatModel.class);
        org.mockito.Mockito.when(delegate.isReasoningReplayEnabled()).thenReturn(true);
        ChatModel model = new ConcurrencyLimitedChatModel(delegate, new ModelConcurrencyLimiter());

        assertThat(ConcurrencyLimitedChatModel.getReasoningReplayEnabled(model)).isTrue();
    }

    @Test
    void streamCancellationReleasesPermitForAnotherModelPath() {
        ModelConcurrencyLimiter limiter = new ModelConcurrencyLimiter();
        AtomicInteger streams = new AtomicInteger();
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of());
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                streams.incrementAndGet();
                return Flux.never();
            }
        };
        ChatModel model = new ConcurrencyLimitedChatModel(delegate, limiter);
        List<Disposable> subscriptions = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                subscriptions.add(model.stream((Prompt) null).subscribe());
            }
            assertThat(streams).hasValue(10);
            subscriptions.getFirst().dispose();

            assertThat(model.call((Prompt) null)).isNotNull();
        }
        finally {
            subscriptions.forEach(Disposable::dispose);
        }
    }
}
