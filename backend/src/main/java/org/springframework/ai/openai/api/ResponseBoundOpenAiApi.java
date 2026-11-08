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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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

    private static ChatCompletionChunk toolOnlyChunk(ChatCompletionChunk source, ChunkChoice choice) {
        List<ChunkChoice> choices = choice == null ? List.of()
                : List.of(copyChoice(choice, toolOnlyDelta(choice.delta())));
        return copyChunk(source, choices, source.usage());
    }

    private static ChatCompletionMessage toolOnlyDelta(ChatCompletionMessage source) {
        if (source == null) {
            return new ChatCompletionMessage(null, null, null, null, null, null, null, null, null);
        }
        return new ChatCompletionMessage(null, source.role(), source.name(), source.toolCallId(), source.toolCalls(),
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

        private ChatCompletionChunk toolAggregate;

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
            this.toolAggregate = this.helper.merge(this.toolAggregate, toolOnlyChunk(chunk, projectionChoice));
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
            if (choice.delta().toolCalls() != null && choice.delta().toolCalls().size() > 1) {
                throw new IllegalStateException("Multiple tool calls in one raw choice are not supported");
            }
            return CollectionUtils.isEmpty(choice.delta().toolCalls()) ? toolChoice : choice;
        }

        private void validateToolBoundary(ChunkScan scan) {
            if (this.toolAggregateFinished && scan.hasToolCalls()) {
                throw new IllegalStateException("Tool calls after the tool aggregate finish are not supported");
            }
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

        private Flux<ChatCompletionChunk> finish() {
            if (this.toolAggregate == null) {
                return Flux.empty();
            }
            if (this.toolAggregate.choices() == null || this.toolAggregate.choices().size() != 1) {
                return Flux.error(new IllegalStateException("Invalid tool aggregate choice boundary"));
            }
            ChunkChoice toolChoice = this.toolAggregate.choices().getFirst();
            ChoiceText text = this.choiceTexts.get(this.toolChoiceIndex);
            if (toolChoice == null || toolChoice.delta() == null || text == null) {
                return Flux.error(new IllegalStateException("Tool aggregate state is incomplete"));
            }
            ChunkChoice deferredChoice = copyChoice(toolChoice, withResponseText(toolChoice.delta(), text));
            ChatCompletionChunk deferred = copyChunk(this.toolAggregate, List.of(deferredChoice), this.latestUsage);
            this.toolAggregate = null;
            return Flux.just(deferred);
        }

        private void clear() {
            this.toolAggregate = null;
            this.toolChoiceIndex = null;
            this.latestUsage = null;
            this.choiceTexts.clear();
            this.seenChoiceIndexes.clear();
            this.seenMultipleChoices = false;
            this.toolAggregateFinished = false;
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
