/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer.ByteBufOrStream;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageFramer;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.internal.grpc.HttpStreamReader;
import com.linecorp.armeria.internal.grpc.StatusListener;
import com.linecorp.armeria.internal.grpc.StatusMessageEscaper;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Codec;
import io.grpc.Codec.Identity;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;

/**
 * Encapsulates the state of a single server call, reading messages from the client, passing to business logic
 * via {@link ServerCall.Listener}, and writing messages passed back to the response.
 */
class ArmeriaServerCall<I, O> extends ServerCall<I, O>
        implements ArmeriaMessageDeframer.Listener, StatusListener {

    // TODO(anuraag): Support json too.
    private static final String GRPC_PROTO_CONTENT_TYPE = "application/grpc+proto";

    private static final Metadata EMPTY_METADATA = new Metadata();

    private static final Splitter ACCEPT_ENCODING_SPLITTER = Splitter.on(',').trimResults();

    private final MethodDescriptor<I, O> method;

    private final HttpStreamReader messageReader;
    private final ArmeriaMessageFramer messageFramer;

    private final HttpResponseWriter res;
    private final CompressorRegistry compressorRegistry;
    private final DecompressorRegistry decompressorRegistry;
    private final ServiceRequestContext ctx;
    private final GrpcMessageMarshaller<I, O> marshaller;

    // Only set once.
    private ServerCall.Listener<I> listener;

    @Nullable private final String clientAcceptEncoding;

    private Compressor compressor;
    private boolean messageCompression;
    private boolean messageReceived;

    // state
    private volatile boolean cancelled;
    private volatile boolean clientStreamClosed;
    private boolean sendHeadersCalled;
    private boolean closeCalled;

    ArmeriaServerCall(HttpHeaders clientHeaders,
                      MethodDescriptor<I, O> method,
                      CompressorRegistry compressorRegistry,
                      DecompressorRegistry decompressorRegistry,
                      HttpResponseWriter res,
                      int maxInboundMessageSizeBytes,
                      int maxOutboundMessageSizeBytes,
                      ServiceRequestContext ctx) {
        requireNonNull(clientHeaders, "clientHeaders");
        this.method = requireNonNull(method, "method");
        this.ctx = requireNonNull(ctx, "ctx");
        this.messageReader = new HttpStreamReader(
                requireNonNull(decompressorRegistry, "decompressorRegistry"),
                new ArmeriaMessageDeframer(
                        this,
                        maxInboundMessageSizeBytes,
                        ctx.alloc())
                        .decompressor(clientDecompressor(clientHeaders, decompressorRegistry)),
                this);
        this.messageFramer = new ArmeriaMessageFramer(ctx.alloc(), maxOutboundMessageSizeBytes);
        this.res = requireNonNull(res, "res");
        this.compressorRegistry = requireNonNull(compressorRegistry, "compressorRegistry");
        this.clientAcceptEncoding =
                Strings.emptyToNull(clientHeaders.get(GrpcHeaderNames.GRPC_ACCEPT_ENCODING));
        this.decompressorRegistry = requireNonNull(decompressorRegistry, "decompressorRegistry");
        marshaller = new GrpcMessageMarshaller<>(ctx.alloc(), GrpcSerializationFormats.PROTO, method);
    }

    @Override
    public void request(int numMessages) {
        if (ctx.eventLoop().inEventLoop()) {
            messageReader.request(numMessages);
        } else {
            ctx.eventLoop().submit(() -> messageReader.request(numMessages));
        }
    }

    @Override
    public void sendHeaders(Metadata unusedGrpcMetadata) {
        checkState(!sendHeadersCalled, "sendHeaders already called");
        checkState(!closeCalled, "call is closed");

        HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);

        headers.set(HttpHeaderNames.CONTENT_TYPE, GRPC_PROTO_CONTENT_TYPE);

        if (compressor == null || !messageCompression || clientAcceptEncoding == null) {
            compressor = Codec.Identity.NONE;
        } else {
            List<String> acceptedEncodingsList =
                    ACCEPT_ENCODING_SPLITTER.splitToList(clientAcceptEncoding);
            if (!acceptedEncodingsList.contains(compressor.getMessageEncoding())) {
                // resort to using no compression.
                compressor = Codec.Identity.NONE;
            }
        }
        messageFramer.setCompressor(compressor);

        // Always put compressor, even if it's identity.
        headers.add(GrpcHeaderNames.GRPC_ENCODING, compressor.getMessageEncoding());

        String advertisedEncodings = String.join(",", decompressorRegistry.getAdvertisedMessageEncodings());
        if (!advertisedEncodings.isEmpty()) {
            headers.add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, advertisedEncodings);
        }

        sendHeadersCalled = true;
        res.write(headers);
    }

    @Override
    public void sendMessage(O message) {
        checkState(sendHeadersCalled, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        try {
            res.write(messageFramer.writePayload(marshaller.serializeResponse(message)));
        } catch (RuntimeException e) {
            close(Status.fromThrowable(e));
            throw e;
        } catch (Throwable t) {
            close(Status.fromThrowable(t));
            throw new RuntimeException(t);
        }

        // We don't have a good way of listening to actual stream writes, and this functionality
        // probably isn't used in most applications anyways, so just say we're always ready for more messages.
        // In cases where flow control is activated, this may result in excessive internal buffering.
        final boolean notifyReady = isReady();
        if (notifyReady) {
            listener.onReady();
        }
    }

    @Override
    public boolean isReady() {
        return !closeCalled;
    }

    void close(Status status) {
        try {
            close(status, EMPTY_METADATA);
        } finally {
            if (status.isOk()) {
                listener.onComplete();
            } else {
                cancelled = true;
                listener.onCancel();
            }
        }
    }

    @Override
    public void close(Status status, Metadata unusedGrpcMetadata) {
        checkState(!closeCalled, "call already closed");

        messageFramer.close();
        if (!clientStreamClosed) {
            messageReader.cancel();
        }

        res.write(statusToTrailers(status, sendHeadersCalled));
        res.close();

        closeCalled = true;

        if (status.isOk()) {
            // The response is streamed so we don't have anything to set as the RpcResponse, so we set it
            // arbitrarily so it can at least be counted.
            ctx.logBuilder().responseContent(RpcResponse.of("success"), null);
        } else {
            ctx.logBuilder().responseContent(RpcResponse.ofFailure(status.asException()), null);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setMessageCompression(boolean messageCompression) {
        messageFramer.setMessageCompression(messageCompression);
        this.messageCompression = messageCompression;
    }

    @Override
    public void setCompression(String compressorName) {
        checkState(!sendHeadersCalled, "sendHeaders has been called");
        compressor =  compressorRegistry.lookupCompressor(compressorName);
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
        messageFramer.setCompressor(compressor);
    }

    @Override
    public MethodDescriptor<I, O> getMethodDescriptor() {
        return method;
    }

    @Override
    public void messageRead(ByteBufOrStream message) {
        // Special case for unary calls.
        if (messageReceived && method.getType() == MethodType.UNARY) {
            close(Status.INTERNAL.withDescription(
                    "More than one request messages for unary call or server streaming call"));
            return;
        }
        messageReceived = true;

        if (isCancelled()) {
            return;
        }

        try {
            listener.onMessage(marshaller.deserializeRequest(message));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void endOfStream() {
        clientStreamClosed = true;
        if (!closeCalled) {
            listener.onHalfClose();
        }
    }

    @Override
    public void onError(Status status) {
        close(status);
    }

    static HttpHeaders statusToTrailers(Status status, boolean headersSent) {
        final HttpHeaders trailers;
        if (headersSent) {
            // Normal trailers.
            trailers = new DefaultHttpHeaders();
        } else {
            // Trailers only response
            trailers = new DefaultHttpHeaders(true, 3, true)
                    .status(HttpStatus.OK)
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto");
        }
        trailers.add(GrpcHeaderNames.GRPC_STATUS, Integer.toString(status.getCode().value()));
        if (status.getDescription() != null) {
            trailers.add(GrpcHeaderNames.GRPC_MESSAGE,
                         StatusMessageEscaper.escape(status.getDescription()));
        }
        return trailers;
    }

    HttpStreamReader messageReader() {
        return messageReader;
    }

    void setListener(Listener<I> listener) {
        checkState(this.listener == null, "listener already set");
        this.listener = requireNonNull(listener, "listener");
    }

    private static Decompressor clientDecompressor(HttpHeaders headers, DecompressorRegistry registry) {
        String encoding = headers.get(GrpcHeaderNames.GRPC_ENCODING);
        if (encoding == null) {
            return Identity.NONE;
        }
        Decompressor decompressor = registry.lookupDecompressor(encoding);
        return firstNonNull(decompressor, Identity.NONE);
    }
}
