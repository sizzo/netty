/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerContext;
import io.netty.channel.ChannelOutboundHandlerContext;
import io.netty.channel.CombinedChannelHandler;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.util.internal.QueueFactory;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A combination of {@link HttpRequestEncoder} and {@link HttpResponseDecoder}
 * which enables easier client side HTTP implementation. {@link HttpClientCodec}
 * provides additional state management for <tt>HEAD</tt> and <tt>CONNECT</tt>
 * requests, which {@link HttpResponseDecoder} lacks.  Please refer to
 * {@link HttpResponseDecoder} to learn what additional state management needs
 * to be done for <tt>HEAD</tt> and <tt>CONNECT</tt> and why
 * {@link HttpResponseDecoder} can not handle it by itself.
 *
 * If the {@link Channel} is closed and there are missing responses,
 * a {@link PrematureChannelClosureException} is thrown.
 *
 * @see HttpServerCodec
 *
 * @apiviz.has io.netty.handler.codec.http.HttpResponseDecoder
 * @apiviz.has io.netty.handler.codec.http.HttpRequestEncoder
 */
public class HttpClientCodec extends CombinedChannelHandler {

    /** A queue that is used for correlating a request and a response. */
    final Queue<HttpMethod> queue = QueueFactory.createQueue();

    /** If true, decoding stops (i.e. pass-through) */
    volatile boolean done;

    private final AtomicLong requestResponseCounter = new AtomicLong();
    private final boolean failOnMissingResponse;

    /**
     * Creates a new instance with the default decoder options
     * ({@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}).
     */
    public HttpClientCodec() {
        this(4096, 8192, 8192, false);
    }

    /**
     * Creates a new instance with the specified decoder options.
     */
    public HttpClientCodec(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        this(maxInitialLineLength, maxHeaderSize, maxChunkSize, false);
    }

    public HttpClientCodec(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
            boolean failOnMissingResponse) {

        init(
                new Decoder(maxInitialLineLength, maxHeaderSize, maxChunkSize),
                new Encoder());
        this.failOnMissingResponse = failOnMissingResponse;
    }

    private final class Encoder extends HttpRequestEncoder {
        @Override
        public void encode(
                ChannelOutboundHandlerContext<Object> ctx, Object msg, ChannelBuffer out) throws Exception {
            if (msg instanceof HttpRequest && !done) {
                queue.offer(((HttpRequest) msg).getMethod());
            }

            super.encode(ctx, msg, out);

            if (failOnMissingResponse) {
                // check if the request is chunked if so do not increment
                if (msg instanceof HttpRequest && !((HttpRequest) msg).isChunked()) {
                    requestResponseCounter.incrementAndGet();
                } else if (msg instanceof HttpChunk && ((HttpChunk) msg).isLast()) {
                    // increment as its the last chunk
                    requestResponseCounter.incrementAndGet();
                }
            }
        }
    }

    private final class Decoder extends HttpResponseDecoder {

        Decoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        public Object decode(
                ChannelInboundHandlerContext<Byte> ctx, ChannelBuffer buffer) throws Exception {
            if (done) {
                return buffer.readBytes(actualReadableBytes());
            } else {
                Object msg = super.decode(ctx, buffer);
                if (failOnMissingResponse) {
                    decrement(msg);
                }
                return msg;
            }
        }

        private void decrement(Object msg) {
            if (msg == null) {
                return;
            }

            // check if its a HttpMessage and its not chunked
            if (msg instanceof HttpMessage && !((HttpMessage) msg).isChunked()) {
                requestResponseCounter.decrementAndGet();
            } else if (msg instanceof HttpChunk && ((HttpChunk) msg).isLast()) {
                requestResponseCounter.decrementAndGet();
            } else if (msg instanceof Object[]) {
                // we just decrement it here as we only use this if the end of the chunk is reached
                // It would be more safe to check all the objects in the array but would also be slower
                requestResponseCounter.decrementAndGet();
            }
        }

        @Override
        protected boolean isContentAlwaysEmpty(HttpMessage msg) {
            final int statusCode = ((HttpResponse) msg).getStatus().getCode();
            if (statusCode == 100) {
                // 100-continue response should be excluded from paired comparison.
                return true;
            }

            // Get the method of the HTTP request that corresponds to the
            // current response.
            HttpMethod method = queue.poll();

            char firstChar = method.getName().charAt(0);
            switch (firstChar) {
            case 'H':
                // According to 4.3, RFC2616:
                // All responses to the HEAD request method MUST NOT include a
                // message-body, even though the presence of entity-header fields
                // might lead one to believe they do.
                if (HttpMethod.HEAD.equals(method)) {
                    return true;

                    // The following code was inserted to work around the servers
                    // that behave incorrectly.  It has been commented out
                    // because it does not work with well behaving servers.
                    // Please note, even if the 'Transfer-Encoding: chunked'
                    // header exists in the HEAD response, the response should
                    // have absolutely no content.
                    //
                    //// Interesting edge case:
                    //// Some poorly implemented servers will send a zero-byte
                    //// chunk if Transfer-Encoding of the response is 'chunked'.
                    ////
                    //// return !msg.isChunked();
                }
                break;
            case 'C':
                // Successful CONNECT request results in a response with empty body.
                if (statusCode == 200) {
                    if (HttpMethod.CONNECT.equals(method)) {
                        // Proxy connection established - Not HTTP anymore.
                        done = true;
                        queue.clear();
                        return true;
                    }
                }
                break;
            }

            return super.isContentAlwaysEmpty(msg);
        }

        @Override
        public void channelInactive(ChannelInboundHandlerContext<Byte> ctx)
                throws Exception {
            super.channelInactive(ctx);

            if (failOnMissingResponse) {
                long missingResponses = requestResponseCounter.get();
                if (missingResponses > 0) {
                    ctx.fireExceptionCaught(new PrematureChannelClosureException(
                            "channel gone inactive with " + missingResponses +
                            " missing response(s)"));
                }
            }
        }
    }
}
