package com.amazonaws.ivs.net;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphed.extension.twitch.patches.StreamAdsPatch;

/**
 * Wraps the IVS player's HTTP client and lets {@link StreamAdsPatch} rewrite live playlists.
 * Lives in the IVS package because {@link ReadCallback} and {@link StreamConsumer} are package-private.
 */
@SuppressWarnings("unused")
public final class MorphedHttpClient implements HttpClient {
    // Backup searches make their own network calls, so keep them off the player's network threads.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "TwitchMorphed-playlist");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpClient delegate;

    private MorphedHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    /** Injected at the end of {@code HttpClientFactory.create()}. */
    public static HttpClient wrap(HttpClient client) {
        if (client == null || client instanceof MorphedHttpClient) return client;
        return new MorphedHttpClient(client);
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public void execute(Request request, ResponseCallback callback) {
        String url = request.getUrl();
        if (!StreamAdsPatch.shouldInspect(url)) {
            delegate.execute(request, callback);
            return;
        }
        delegate.execute(request, new ResponseCallback() {
            @Override
            public void onError(Exception exception) {
                callback.onError(exception);
            }

            @Override
            public void onResponse(Response response) {
                if (response.getStatus() != 200) {
                    callback.onResponse(response);
                    return;
                }
                response.readContent(new BodyCollector(request) {
                    @Override
                    void onBody(byte[] body) {
                        EXECUTOR.execute(() -> deliver(request, response, callback, url, body));
                    }

                    @Override
                    public void onError(Exception exception) {
                        callback.onError(exception);
                    }
                });
            }
        });
    }

    private static void deliver(Request request, Response response, ResponseCallback callback, String url, byte[] body) {
        byte[] output = body;
        try {
            String original = new String(body, StandardCharsets.UTF_8);
            String rewritten = StreamAdsPatch.process(url, original);
            if (rewritten != null && !rewritten.equals(original)) {
                output = rewritten.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
            // Fall back to the original body; playback must never break because of the ad blocker.
        }
        if (response.getHeader("Content-Length") != null) {
            response.setHeader("Content-Length", String.valueOf(output.length));
        }
        response.setConsumer(null, new BytesConsumer(request, output));
        callback.onResponse(response);
    }

    /** Collects a response body that IVS hands out in chunks. */
    private abstract static class BodyCollector implements ReadCallback {
        private final Request request;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        BodyCollector(Request request) {
            this.request = request;
        }

        abstract void onBody(byte[] body);

        @Override
        public int getTimeout() {
            return request.getTimeout();
        }

        @Override
        public void onData(ByteBuffer buffer, boolean isLast) {
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            body.write(chunk, 0, chunk.length);
            if (isLast) onBody(body.toByteArray());
        }

        @Override
        public void onData(byte[] bytes, int length, boolean isLast) {
            body.write(bytes, 0, length);
            if (isLast) onBody(body.toByteArray());
        }
    }

    /** Hands a finished body to the player in one direct buffer, like IVS's own consumer. */
    private static final class BytesConsumer implements StreamConsumer {
        private final Request request;
        private final byte[] body;

        BytesConsumer(Request request, byte[] body) {
            this.request = request;
            this.body = body;
        }

        @Override
        public void consume(ReadCallback readCallback) {
            synchronized (request.lock()) {
                if (request.isCancelled()) return;
                ByteBuffer buffer = ByteBuffer.allocateDirect(body.length);
                buffer.put(body);
                buffer.flip();
                readCallback.onData(buffer, true);
            }
        }
    }
}
