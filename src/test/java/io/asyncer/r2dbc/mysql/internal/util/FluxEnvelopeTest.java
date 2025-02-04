/*
 * Copyright 2023 asyncer.io projects
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.asyncer.r2dbc.mysql.internal.util;

import io.asyncer.r2dbc.mysql.constant.Envelopes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FluxEnvelope}.
 */
class FluxEnvelopeTest {

    private static final byte[] RD_PATTERN = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz"
        .getBytes(StandardCharsets.US_ASCII);

    private static final Consumer<ByteBuf> EMPTY_HEADER = buf -> {
        try {
            assertThat(ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes(), false))
                .hasSize(4)
                .containsOnly(0);
        } finally {
            buf.release();
        }
    };

    private final ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

    @Test
    void empty() {
        envelopes(Flux.empty(), randomEnvelopeSize())
            .as(StepVerifier::create)
            .assertNext(EMPTY_HEADER)
            .verifyComplete();

        envelopes(Flux.just(allocator.buffer(0, 0)), randomEnvelopeSize())
            .as(StepVerifier::create)
            .assertNext(EMPTY_HEADER)
            .verifyComplete();

        envelopes(Flux.empty(), Integer.MAX_VALUE)
            .as(StepVerifier::create)
            .assertNext(EMPTY_HEADER)
            .verifyComplete();
    }

    @Test
    void splitIntegralMultiple() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf allocated = mockBuf(envelopeSize * 10);
        String origin = allocated.toString(StandardCharsets.US_ASCII);

        envelopes(Flux.just(allocated), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(origin, envelopeSize, 0, 11))
            .verifyComplete();
        assertThat(allocated.refCnt()).isEqualTo(0);
    }

    @Test
    void splitMoreOneThanIntegral() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf allocated = mockBuf(envelopeSize * 7 + 1);
        String origin = allocated.toString(StandardCharsets.US_ASCII);

        envelopes(Flux.just(allocated), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(origin, envelopeSize, 1, 8))
            .verifyComplete();
        assertThat(allocated.refCnt()).isEqualTo(0);
    }

    @Test
    void splitLessOneThanIntegral() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf allocated = mockBuf(envelopeSize * 16 - 1);
        String origin = allocated.toString(StandardCharsets.US_ASCII);

        envelopes(Flux.just(allocated), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(origin, envelopeSize, envelopeSize - 1, 16))
            .verifyComplete();
        assertThat(allocated.refCnt()).isEqualTo(0);
    }

    @Test
    void merge() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(1),
            mockBuf(3),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 6, 1))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeFull() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(1),
            mockBuf(3),
            mockBuf(1),
            mockBuf(envelopeSize - 7)
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 0, 2))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeFullWithMore() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(1),
            mockBuf(3),
            mockBuf(envelopeSize - 6),
            mockBuf(5),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 5, 2))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeFullOutOfEnvelope() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(1),
            mockBuf(3),
            mockBuf(envelopeSize),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 6, 2))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeWithLargeCross() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(envelopeSize - 6),
            mockBuf(3),
            mockBuf(envelopeSize * 10 + 2),
            mockBuf(3)
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 4, 12))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeWithLargeCrossIntegral() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(envelopeSize - 6),
            mockBuf(3),
            mockBuf(envelopeSize * 10 + 1),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 0, 12))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeIntegralWithLargeCross() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(envelopeSize - 5),
            mockBuf(3),
            mockBuf(envelopeSize * 10 + 1),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 1, 12))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    @Test
    void mergeIntegralWithLargeCrossIntegral() {
        int envelopeSize = randomEnvelopeSize();
        ByteBuf[] buffers = {
            mockBuf(2),
            mockBuf(envelopeSize - 5),
            mockBuf(3),
            mockBuf(envelopeSize * 10),
        };

        envelopes(Flux.fromArray(buffers), envelopeSize)
            .collectList()
            .as(StepVerifier::create)
            .assertNext(assertBuffers(join(buffers), envelopeSize, 0, 12))
            .verifyComplete();
        assertThat(Arrays.stream(buffers).map(ByteBuf::refCnt).collect(Collectors.toList())).containsOnly(0);
    }

    private Flux<ByteBuf> envelopes(Flux<ByteBuf> source, int envelopeSize) {
        return new FluxEnvelope(source, allocator, envelopeSize, 0, true);
    }

    private Consumer<List<ByteBuf>> assertBuffers(String origin, int envelopeSize, int lastSize,
        int totalSize) {
        return originBuffers -> {
            try {
                List<ByteBuf> buffers = new ArrayList<>((originBuffers.size() + 1) >>> 1);

                for (int i = 0, n = originBuffers.size(); i < n; i += 2) {
                    ByteBuf header = originBuffers.get(i);
                    assertThat(header.readableBytes()).isEqualTo(Envelopes.PART_HEADER_SIZE);

                    int size = header.readMediumLE();
                    if (size > 0) {
                        ByteBuf envelope = originBuffers.get(i + 1);
                        assertThat(envelope.readableBytes()).isEqualTo(size);
                        buffers.add(envelope);
                    } else {
                        assertThat(n - 1).isEqualTo(i);
                        buffers.add(header.alloc().buffer(0, 0));
                    }
                }

                assertThat(buffers).hasSize(totalSize);

                for (int i = 0, n = buffers.size() - 1; i < n; ++i) {
                    assertThat(buffers.get(i).readableBytes()).isEqualTo(envelopeSize);
                }

                assertThat(buffers.get(buffers.size() - 1).readableBytes()).isEqualTo(lastSize);
                assertThat(origin).isEqualTo(buffers.stream()
                    .map(buf -> buf.toString(StandardCharsets.US_ASCII))
                    .collect(Collectors.joining()));
            } finally {
                for (ByteBuf buf : originBuffers) {
                    buf.release();
                }
            }
        };
    }

    private ByteBuf mockBuf(int size) {
        byte[] bytes = new byte[size];

        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] = randomChar();
        }

        ByteBuf buf = allocator.buffer();

        try {
            return buf.writeBytes(bytes);
        } catch (Throwable e) {
            buf.release();
            throw e;
        }
    }

    private static String join(ByteBuf[] buffers) {
        StringBuilder builder = new StringBuilder();

        for (ByteBuf buf : buffers) {
            builder.append(buf.toString(StandardCharsets.US_ASCII));
        }

        return builder.toString();
    }

    private static int randomEnvelopeSize() {
        return ThreadLocalRandom.current().nextInt(9, 24);
    }

    private static byte randomChar() {
        return RD_PATTERN[ThreadLocalRandom.current().nextInt(0, RD_PATTERN.length)];
    }
}
