/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.openai.api;

import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk.ChunkChoice;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Delays a tool-call aggregate until its response has completed, for models with internal tool execution enabled.
 *
 * <p>The minimal WebClient/SSE pipeline is adapted from Spring AI 1.1.7
 * {@code OpenAiApi.chatCompletionStream}, modified to establish the response boundary before window/merge.
 *
 * @author Christian Tzolov
 * @author Michael Lavelle
 * @author Mariusz Bernacki
 * @author Thomas Vitale
 * @author David Frizelle
 * @author Alexandros Pappas
 * @author Filip Hrisafov
 */
public final class ResponseBoundOpenAiApi extends OpenAiApi {

    private final WebClient streamingWebClient;

    public ResponseBoundOpenAiApi(String baseUrl, ApiKey apiKey, MultiValueMap<String, String> headers,
                                  String completionsPath, String embeddingsPath, RestClient.Builder restClientBuilder,
                                  WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler) {
        super(baseUrl, apiKey, headers, completionsPath, embeddingsPath, restClientBuilder, webClientBuilder,
                responseErrorHandler);
        Consumer<HttpHeaders> finalHeaders = h -> {
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set(HTTP_USER_AGENT_HEADER, SPRING_AI_USER_AGENT);
            h.addAll(headers);
        };
        this.streamingWebClient = webClientBuilder.clone().baseUrl(baseUrl).defaultHeaders(finalHeaders).build();
    }

    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest,
                                                          MultiValueMap<String, String> additionalHttpHeaders) {
        Assert.notNull(chatRequest, "The request body can not be null.");
        Assert.isTrue(chatRequest.stream(), "Request must set the stream property to true.");
        Flux<ChatCompletionChunk> rawChunks = this.streamingWebClient.post()
                .uri(getCompletionsPath())
                .headers(headers -> {
                    headers.addAll(additionalHttpHeaders);
                    addDefaultHeadersIfMissing(headers);
                })
                .bodyValue(chatRequest)
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil("[DONE]"::equals)
                .filter(content -> !"[DONE]".equals(content))
                .map(content -> ModelOptionsUtils.jsonToObject(content, ChatCompletionChunk.class));
        return deferToolCalls(rawChunks);
    }

    private void addDefaultHeadersIfMissing(HttpHeaders headers) {
        if (!headers.containsKey(HttpHeaders.AUTHORIZATION) && !(getApiKey() instanceof NoopApiKey)) {
            headers.setBearerAuth(getApiKey().getValue());
        }
    }

    Flux<ChatCompletionChunk> deferToolCalls(Flux<ChatCompletionChunk> upstream) {
        return Flux.defer(() -> {
            ResponseState state = new ResponseState();
            return upstream.concatMap(state::emitLive)
                    .concatWith(Flux.defer(state::finish))
                    .doFinally(signal -> state.clear());
        });
    }

    private static ChatCompletionChunk copyChunk(ChatCompletionChunk source, List<ChunkChoice> choices, Usage usage) {
        return new ChatCompletionChunk(source.id(), choices, source.created(), source.model(), source.serviceTier(),
                source.systemFingerprint(), source.object(), usage);
    }

    private static ChunkChoice copyChoice(ChunkChoice source, ChatCompletionMessage delta) {
        return new ChunkChoice(source.finishReason(), source.index(), delta, source.logprobs());
    }

    private static ChatCompletionChunk toolOnlyChunk(ChatCompletionChunk source, ChunkChoice choice,
                                                     List<ToolCall> toolCalls) {
        List<ChunkChoice> choices = choice == null ? List.of()
                : List.of(copyChoice(choice, toolOnlyDelta(choice.delta(), toolCalls)));
        return copyChunk(source, choices, source.usage());
    }

    private static ChatCompletionMessage toolOnlyDelta(ChatCompletionMessage source, List<ToolCall> toolCalls) {
        if (source == null) {
            return new ChatCompletionMessage(null, null, null, null, null, null, null, null, null);
        }
        return new ChatCompletionMessage(null, source.role(), source.name(), source.toolCallId(), toolCalls,
                source.refusal(), source.audioOutput(), source.annotations(), null);
    }

    private static ChatCompletionChunk withoutToolCalls(ChatCompletionChunk source) {
        List<ChunkChoice> choices = source.choices() == null ? null
                : source.choices().stream().map(ResponseBoundOpenAiApi::withoutToolCalls).toList();
        return copyChunk(source, choices, source.usage());
    }

    private static ChunkChoice withoutToolCalls(ChunkChoice source) {
        if (source == null || source.delta() == null) {
            return source;
        }
        ChatCompletionMessage delta = source.delta();
        return copyChoice(source, new ChatCompletionMessage(delta.rawContent(), delta.role(), delta.name(),
                delta.toolCallId(), null, delta.refusal(), delta.audioOutput(), delta.annotations(),
                delta.reasoningContent()));
    }

    private static ChatCompletionMessage withToolCalls(ChatCompletionMessage source, List<ToolCall> toolCalls) {
        return new ChatCompletionMessage(source.rawContent(), source.role(), source.name(), source.toolCallId(),
                toolCalls, source.refusal(), source.audioOutput(), source.annotations(), source.reasoningContent());
    }

    private static ChatCompletionMessage withResponseText(ChatCompletionMessage source, ChoiceText text) {
        return new ChatCompletionMessage(text.contentText(), source.role(), source.name(), source.toolCallId(),
                source.toolCalls(), source.refusal(), source.audioOutput(), source.annotations(), text.reasoningText());
    }

    private record ChunkScan(ChunkChoice choice, ChunkChoice toolChoice, int validChoices) {

        private boolean hasToolCalls() {
            return this.toolChoice != null;
        }

    }

    private static final class ResponseState {

        private final Map<Integer, ChoiceText> choiceTexts = new HashMap<>();

        private final Set<Integer> seenChoiceIndexes = new HashSet<>();

        private final OpenAiStreamFunctionCallingHelper helper = new OpenAiStreamFunctionCallingHelper();

        private final List<ToolCallAggregate> toolCallAggregates = new ArrayList<>();

        private final Map<Integer, ToolCallAggregate> currentToolCallsByIndex = new HashMap<>();

        private final Map<String, ToolCallAggregate> currentToolCallsById = new HashMap<>();

        private ChatCompletionChunk toolAggregate;

        private ToolCallAggregate lastToolCall;

        private Integer toolChoiceIndex;

        private Usage latestUsage;

        private boolean seenMultipleChoices;

        private boolean toolAggregateFinished;

        private Flux<ChatCompletionChunk> emitLive(ChatCompletionChunk chunk) {
            if (chunk.usage() != null) {
                this.latestUsage = chunk.usage();
            }
            ChunkScan scan = scan(chunk);
            if (this.toolAggregate == null && !scan.hasToolCalls()) {
                return Flux.just(chunk);
            }
            validateToolBoundary(scan);
            ChunkChoice projectionChoice = scan.validChoices() == 1 ? scan.choice() : null;
            this.toolAggregate = this.helper.merge(this.toolAggregate,
                    toolOnlyChunk(chunk, projectionChoice, List.of()));
            mergeToolCalls(chunk, scan);
            if (scan.validChoices() == 1
                    && scan.choice().finishReason() == OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS) {
                this.toolAggregateFinished = true;
            }
            return Flux.just(scan.hasToolCalls() ? withoutToolCalls(chunk) : chunk);
        }

        private ChunkScan scan(ChatCompletionChunk chunk) {
            int validChoices = 0;
            ChunkChoice singleChoice = null;
            ChunkChoice toolChoice = null;
            if (chunk.choices() != null) {
                for (ChunkChoice choice : chunk.choices()) {
                    if (choice == null) {
                        continue;
                    }
                    validChoices++;
                    singleChoice = choice;
                    this.seenChoiceIndexes.add(choice.index());
                    toolChoice = scanChoice(choice, toolChoice);
                }
            }
            this.seenMultipleChoices |= validChoices > 1;
            return new ChunkScan(singleChoice, toolChoice, validChoices);
        }

        private ChunkChoice scanChoice(ChunkChoice choice, ChunkChoice toolChoice) {
            if (choice.delta() == null) {
                return toolChoice;
            }
            this.choiceTexts.computeIfAbsent(choice.index(), ignored -> new ChoiceText()).append(choice.delta());
            return CollectionUtils.isEmpty(choice.delta().toolCalls()) ? toolChoice : choice;
        }

        private void validateToolBoundary(ChunkScan scan) {
            if (this.toolAggregate == null) {
                if (this.seenMultipleChoices || scan.validChoices() != 1 || this.seenChoiceIndexes.size() != 1) {
                    throw new IllegalStateException("Tool calls require one choice and one choice index");
                }
                this.toolChoiceIndex = scan.toolChoice().index();
                return;
            }
            if (scan.validChoices() > 1) {
                throw new IllegalStateException("Multiple choices after a tool call are not supported");
            }
            if (scan.validChoices() == 1 && !Objects.equals(this.toolChoiceIndex, scan.choice().index())) {
                throw new IllegalStateException("A different choice after a tool call is not supported");
            }
        }

        private void mergeToolCalls(ChatCompletionChunk chunk, ChunkScan scan) {
            if (!scan.hasToolCalls()) {
                return;
            }
            if (this.toolAggregateFinished) {
                startIndependentToolCalls();
            }
            for (ToolCall call : scan.toolChoice().delta().toolCalls()) {
                if (call == null) {
                    throw new IllegalStateException("Tool call aggregate contains a null call");
                }
                ToolCallAggregate aggregate = resolveToolCall(call);
                aggregate.merge(this.helper, chunk, scan.toolChoice(), call);
                registerToolCall(aggregate, call.id());
                this.lastToolCall = aggregate;
            }
        }

        private void startIndependentToolCalls() {
            this.currentToolCallsByIndex.clear();
            this.currentToolCallsById.clear();
            this.lastToolCall = null;
            this.toolAggregateFinished = false;
        }

        private ToolCallAggregate resolveToolCall(ToolCall call) {
            ToolCallAggregate byId = StringUtils.hasText(call.id()) ? this.currentToolCallsById.get(call.id()) : null;
            if (byId != null) {
                byId.validateIndex(call.index());
                return byId;
            }
            ToolCallAggregate byIndex = call.index() == null ? null : this.currentToolCallsByIndex.get(call.index());
            if (byIndex != null) {
                byIndex.validateId(call.id());
                return byIndex;
            }
            if (!StringUtils.hasText(call.id()) && this.lastToolCall != null
                    && (call.index() == null || this.lastToolCall.index == null)) {
                return this.lastToolCall;
            }
            if (!StringUtils.hasText(call.id()) && call.index() == null) {
                throw new IllegalStateException("Tool call continuation has no id or index");
            }
            ToolCallAggregate created = new ToolCallAggregate(call.index());
            this.toolCallAggregates.add(created);
            return created;
        }

        private void registerToolCall(ToolCallAggregate aggregate, String id) {
            if (aggregate.index != null) {
                ToolCallAggregate existing = this.currentToolCallsByIndex.get(aggregate.index);
                if (existing != null && existing != aggregate) {
                    throw new IllegalStateException("Conflicting tool call indexes for one response");
                }
                this.currentToolCallsByIndex.put(aggregate.index, aggregate);
            }
            if (StringUtils.hasText(id)) {
                this.currentToolCallsById.put(id, aggregate);
            }
        }

        private Flux<ChatCompletionChunk> finish() {
            if (this.toolAggregate == null) {
                return Flux.empty();
            }
            if (this.toolAggregate.choices() == null || this.toolAggregate.choices().size() != 1) {
                return Flux.error(new IllegalStateException("Invalid tool aggregate choice boundary"));
            }
            ChunkChoice toolChoice = this.toolAggregate.choices().getFirst();
            ChoiceText text = this.choiceTexts.get(this.toolChoiceIndex);
            if (toolChoice == null || toolChoice.delta() == null || text == null || this.toolCallAggregates.isEmpty()) {
                return Flux.error(new IllegalStateException("Tool aggregate state is incomplete"));
            }
            ChatCompletionMessage delta = withToolCalls(toolChoice.delta(), completedToolCalls());
            ChunkChoice deferredChoice = copyChoice(toolChoice, withResponseText(delta, text));
            ChatCompletionChunk deferred = copyChunk(this.toolAggregate, List.of(deferredChoice), this.latestUsage);
            this.toolAggregate = null;
            return Flux.just(deferred);
        }

        private List<ToolCall> completedToolCalls() {
            return this.toolCallAggregates.stream().map(ToolCallAggregate::toolCall).toList();
        }

        private void clear() {
            this.toolAggregate = null;
            this.lastToolCall = null;
            this.toolChoiceIndex = null;
            this.latestUsage = null;
            this.choiceTexts.clear();
            this.seenChoiceIndexes.clear();
            this.toolCallAggregates.clear();
            this.currentToolCallsByIndex.clear();
            this.currentToolCallsById.clear();
            this.seenMultipleChoices = false;
            this.toolAggregateFinished = false;
        }

    }

    private static final class ToolCallAggregate {

        private Integer index;

        private String id;

        private ChatCompletionChunk chunk;

        private ToolCallAggregate(Integer index) {
            this.index = index;
        }

        private void merge(OpenAiStreamFunctionCallingHelper helper, ChatCompletionChunk source, ChunkChoice choice,
                           ToolCall call) {
            validateId(call.id());
            validateIndex(call.index());
            if (this.chunk != null && !StringUtils.hasText(this.id) && StringUtils.hasText(call.id())) {
                introduceId(helper, call.id());
            }
            String mergedId = StringUtils.hasText(this.id) ? null : call.id();
            ToolCall current = new ToolCall(call.index(), mergedId, call.type(), call.function());
            this.chunk = helper.merge(this.chunk, toolOnlyChunk(source, choice, List.of(current)));
            if (StringUtils.hasText(call.id())) {
                this.id = call.id();
            }
            if (this.index == null) {
                this.index = call.index();
            }
        }

        private void introduceId(OpenAiStreamFunctionCallingHelper helper, String id) {
            ToolCall previous = currentToolCall();
            ToolCall identified = new ToolCall(this.index, id, previous.type(), previous.function());
            ChunkChoice aggregateChoice = this.chunk.choices().getFirst();
            this.chunk = helper.merge(null, toolOnlyChunk(this.chunk, aggregateChoice, List.of(identified)));
            this.id = id;
        }

        private ToolCall toolCall() {
            ToolCall merged = currentToolCall();
            validateCompletion(merged);
            return new ToolCall(this.index, merged.id(), merged.type(), merged.function());
        }

        private ToolCall currentToolCall() {
            if (this.chunk == null || this.chunk.choices() == null || this.chunk.choices().size() != 1) {
                throw new IllegalStateException("Tool call aggregate choice is incomplete");
            }
            ChatCompletionMessage delta = this.chunk.choices().getFirst().delta();
            if (delta == null || delta.toolCalls() == null || delta.toolCalls().size() != 1) {
                throw new IllegalStateException("Tool call aggregate delta is incomplete");
            }
            return delta.toolCalls().getFirst();
        }

        private void validateCompletion(ToolCall merged) {
            if (!StringUtils.hasText(merged.id())) {
                throw new IllegalStateException("Tool call aggregate id is incomplete");
            }
            if (!StringUtils.hasText(merged.type())) {
                throw new IllegalStateException("Tool call aggregate type is incomplete");
            }
            if (merged.function() == null || !StringUtils.hasText(merged.function().name())
                    || merged.function().arguments() == null) {
                throw new IllegalStateException("Tool call aggregate function is incomplete");
            }
        }

        private void validateId(String candidate) {
            if (StringUtils.hasText(this.id) && StringUtils.hasText(candidate) && !this.id.equals(candidate)) {
                throw new IllegalStateException("Conflicting tool call ids for one tool call index");
            }
        }

        private void validateIndex(Integer candidate) {
            if (this.index != null && candidate != null && !this.index.equals(candidate)) {
                throw new IllegalStateException("Conflicting tool call indexes for one tool call id");
            }
        }

    }

    private static final class ChoiceText {

        private final StringBuilder content = new StringBuilder();

        private final StringBuilder reasoning = new StringBuilder();

        private boolean contentPresent;

        private boolean reasoningPresent;

        private void append(ChatCompletionMessage delta) {
            String contentDelta = delta.content();
            if (contentDelta != null) {
                this.contentPresent = true;
                this.content.append(contentDelta);
            }
            String reasoningDelta = delta.reasoningContent();
            if (reasoningDelta != null) {
                this.reasoningPresent = true;
                this.reasoning.append(reasoningDelta);
            }
        }

        private String contentText() {
            return this.contentPresent ? this.content.toString() : null;
        }

        private String reasoningText() {
            return this.reasoningPresent ? this.reasoning.toString() : null;
        }

    }

}
