package top.kmar.worldrelay.tunnel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** JDK WebSocket/TLS 与虚拟线程实现；不替换 Minecraft 的 Netty，也不执行外部程序。 */
public final class TunnelConnector implements AutoCloseable {

    private final ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("worldrelay-tunnel-", 0).factory());
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "worldrelay-tunnel-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<ServerSocket> listeners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile HttpClient http;

    public TunnelConnector() {
        timers.scheduleWithFixedDelay(() -> sessions.forEach(session -> {
            long now = System.nanoTime();
            if (now - session.lastRead > TimeUnit.SECONDS.toNanos(45)) session.close();
            else if (now - session.lastWrite > TimeUnit.SECONDS.toNanos(15) && session.pingQueued.compareAndSet(false, true))
                session.task(() -> session.send(TunnelWire.PING, new byte[0]));
        }), 1, 1, TimeUnit.SECONDS);
    }

    private synchronized HttpClient http() {
        if (closed.get()) throw new IllegalStateException("Tunnel connector is closed");
        if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return http;
    }

    private final class LocalSocket extends Socket {

        @Override
        public synchronized void close() throws IOException {
            try {
                super.close();
            } finally {
                sockets.remove(this);
            }
        }

    }

    public final class Session implements AutoCloseable {

        private final CompletableFuture<Session> established = new CompletableFuture<>();
        private final CompletableFuture<Socket> rawReady = new CompletableFuture<>();
        private final BlockingQueue<byte[]> writes = new ArrayBlockingQueue<>(16);
        private final Semaphore capacity = new Semaphore(TunnelWire.MAX_PENDING);
        private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean finishing = new AtomicBoolean();
        private final AtomicBoolean pingQueued = new AtomicBoolean();
        private final Consumer<String> open;
        private volatile WebSocket webSocket;
        private volatile Socket transport;
        private volatile SSLSocket tls;
        private volatile Socket raw;
        private volatile ServerSocket listener;
        private volatile Runnable rawClosed = () -> {};
        private volatile String registrationId;
        private volatile long lastRead = System.nanoTime(), lastWrite = System.nanoTime();

        private Session(Consumer<String> open) {
            this.open = open;
        }

        public String registrationId() {
            return registrationId;
        }

        public boolean isOpen() {
            return !ended.get();
        }

        private void task(Runnable action) {
            FutureTask<Void> future = new FutureTask<>(action, null) {
                @Override
                protected void done() {
                    tasks.remove(this);
                }
            };
            tasks.add(future);
            try {
                io.execute(future);
                if (ended.get()) future.cancel(true);
            } catch (RejectedExecutionException failure) {
                future.cancel(true);
                close();
            }
        }

        private void startWriter() {
            task(() -> {
                try {
                    while (isOpen()) {
                        byte[] frame = writes.take();
                        try {
                            if (webSocket != null) webSocket.sendBinary(ByteBuffer.wrap(frame), true).get(45, TimeUnit.SECONDS);
                            else {
                                DataOutputStream output = new DataOutputStream(tls.getOutputStream());
                                output.writeInt(frame.length);
                                output.write(frame);
                                output.flush();
                            }
                            lastWrite = System.nanoTime();
                            if (frame[0] == TunnelWire.PING) pingQueued.set(false);
                            if (frame[0] == TunnelWire.CLOSE) {
                                if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2, TimeUnit.SECONDS);
                                else tls.close();
                                close();
                                return;
                            }
                        } finally {
                            capacity.release(frame.length);
                        }
                    }
                } catch (Exception failure) {
                    close();
                }
            });
        }

        private void send(byte type, byte[] payload) {
            if (payload.length > TunnelWire.CHUNK || !isOpen()) return;
            byte[] frame = new byte[payload.length + 1];
            frame[0] = type;
            System.arraycopy(payload, 0, frame, 1, payload.length);
            boolean acquired = false, queued = false;
            try {
                while (isOpen() && !acquired) {
                    acquired = capacity.tryAcquire(frame.length, 1, TimeUnit.SECONDS);
                }
                if (!acquired || !isOpen()) return;
                while (isOpen() && !queued) {
                    queued = writes.offer(frame, 1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                close();
            } finally {
                if (acquired && !queued) capacity.release(frame.length);
            }
        }

        private void receive(byte[] frame) throws Exception {
            if (frame.length < 1 || frame.length > TunnelWire.CHUNK + 1) throw new IOException("Invalid tunnel frame");
            lastRead = System.nanoTime();
            byte type = frame[0];
            if (type == TunnelWire.PONG) return; // 读取已重置失活计时。
            if (type == TunnelWire.DATA) {
                if (!established.isDone() || established.isCompletedExceptionally()) throw new IOException("Tunnel is not ready");
                Socket destination = rawReady.get(10, TimeUnit.SECONDS);
                destination.getOutputStream().write(frame, 1, frame.length - 1);
            } else if (type == TunnelWire.PING) send(TunnelWire.PONG, new byte[0]);
            else if (type == TunnelWire.CLOSE) close();
            else {
                if (frame.length > 4097) throw new IOException("Control message too large");
                String text = new String(frame, 1, frame.length - 1, StandardCharsets.UTF_8);
                if (type == TunnelWire.READY || type == TunnelWire.REGISTERED) {
                    registrationId = text;
                    if (!established.complete(this)) throw new IOException("Repeated tunnel readiness");
                } else if (type == TunnelWire.OPEN && established.isDone() && open != null) open.accept(text);
                else throw new IOException("Tunnel rejected or protocol mismatch");
            }
        }

        public void bindRaw(Socket socket) {
            bindRaw(socket, () -> {});
        }

        public void bindRaw(Socket socket, Runnable onClosed) {
            synchronized (this) {
                if (!isOpen() || raw != null) {
                    quietClose(socket);
                    onClosed.run();
                    return;
                }
                raw = socket;
                rawClosed = onClosed;
                sockets.add(socket);
                rawReady.complete(socket);
            }
            task(() -> {
                try {
                    byte[] buffer = new byte[TunnelWire.CHUNK];
                    int count;
                    while (isOpen() && (count = socket.getInputStream().read(buffer)) >= 0) {
                        send(TunnelWire.DATA, java.util.Arrays.copyOf(buffer, count));
                    }
                    if (isOpen() && finishing.compareAndSet(false, true)) send(TunnelWire.CLOSE, new byte[0]);
                } catch (IOException failure) {
                    close();
                }
            });
        }

        @Override
        public void close() {
            if (!ended.compareAndSet(false, true)) return;
            sessions.remove(this);
            established.completeExceptionally(new IOException("Tunnel disconnected"));
            rawReady.completeExceptionally(new IOException("Tunnel disconnected"));
            tasks.forEach(task -> task.cancel(true));
            if (webSocket != null) webSocket.abort();
            if (transport != null) quietClose(transport);
            synchronized (this) {
                if (raw != null) {
                    quietClose(raw);
                    sockets.remove(raw);
                }
                rawClosed.run();
                if (listener != null) {
                    quietClose(listener);
                    listeners.remove(listener);
                }
            }
        }

    }

    @SuppressWarnings("resource") // HttpClient 由整个 Connector 持有，在 close 中关闭。
    public CompletableFuture<Session> connect(TunnelEndpoint endpoint, String token, Consumer<String> open) {
        if (closed.get()) return CompletableFuture.failedFuture(new IOException("Tunnel connector is closed"));
        Session session = new Session(open);
        sessions.add(session);
        try {
            timers.schedule(() -> {if (!session.established.isDone()) session.close();}, 10, TimeUnit.SECONDS);
            session.established.whenComplete((result, failure) -> {if (failure != null) session.close();});
            if (endpoint.webSocket()) {
                http().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(endpoint.uri(), new WebSocket.Listener() {
                    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

                    @Override
                    public void onOpen(WebSocket socket) {
                        session.webSocket = socket;
                        if (!session.isOpen()) {
                            socket.abort();
                            return;
                        }
                        session.startWriter();
                        session.send(TunnelWire.HELLO, ("1\n" + token).getBytes(StandardCharsets.UTF_8));
                        socket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
                        if (partial.size() + data.remaining() > TunnelWire.CHUNK + 1) {
                            session.close();
                            return CompletableFuture.completedFuture(null);
                        }
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        partial.writeBytes(bytes);
                        if (!last) {
                            socket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }
                        byte[] frame = partial.toByteArray();
                        partial.reset();
                        return CompletableFuture.runAsync(() -> {
                            try {
                                session.receive(frame);
                            } catch (Exception failure) {
                                session.close();
                            }
                        }, io).whenComplete((ignored, error) -> {if (session.isOpen()) socket.request(1);});
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
                        session.close();
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                        session.close();
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket socket, Throwable error) {
                        session.close();
                    }
                }).whenComplete((socket, error) -> {if (error != null) session.close();});
            } else session.task(() -> {
                try {
                    LocalSocket base = new LocalSocket();
                    sockets.add(base);
                    session.transport = base;
                    base.connect(new InetSocketAddress(endpoint.uri().getHost(), endpoint.port()), 10_000);
                    base.setTcpNoDelay(true);
                    base.setSoTimeout(10_000);
                    SSLSocket tls = (SSLSocket) ssl(endpoint.fingerprint()).getSocketFactory().createSocket(base, endpoint.uri().getHost(), endpoint.port(), true);
                    session.tls = tls;
                    tls.startHandshake();
                    tls.setSoTimeout(45_000);
                    session.startWriter();
                    session.send(TunnelWire.HELLO, ("1\n" + token).getBytes(StandardCharsets.UTF_8));
                    DataInputStream input = new DataInputStream(tls.getInputStream());
                    while (session.isOpen()) {
                        int size = input.readInt();
                        if (size < 1 || size > TunnelWire.CHUNK + 1) throw new IOException("Invalid tunnel frame size");
                        byte[] frame = input.readNBytes(size);
                        if (frame.length != size) throw new EOFException();
                        session.receive(frame);
                    }
                } catch (Exception failure) {
                    session.close();
                }
            });
        } catch (RuntimeException failure) {
            session.close();
        }
        return session.established;
    }

    public CompletableFuture<Socket> local(int port) {
        CompletableFuture<Socket> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                LocalSocket socket = new LocalSocket();
                sockets.add(socket);
                try {
                    if (closed.get()) throw new IOException("Connector closed");
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
                    socket.setTcpNoDelay(true);
                    if (closed.get() || !result.complete(socket)) quietClose(socket);
                } catch (Exception failure) {
                    quietClose(socket);
                    result.completeExceptionally(new IOException("Local world is unavailable"));
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new IOException("Connector closed"));
        }
        return result;
    }

    public CompletableFuture<InetSocketAddress> listen(Session session) {
        CompletableFuture<InetSocketAddress> result = new CompletableFuture<>();
        try {
            io.execute(() -> {
                try (ServerSocket listener = new ServerSocket()) {
                    listeners.add(listener);
                    session.listener = listener;
                    listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
                    listener.setSoTimeout(30_000);
                    if (closed.get() || !session.isOpen() || !result.complete((InetSocketAddress) listener.getLocalSocketAddress())) return;
                    session.bindRaw(listener.accept());
                } catch (Exception failure) {
                    result.completeExceptionally(new IOException("Local tunnel bind failed"));
                    session.close();
                } finally {
                    if (session.listener != null) listeners.remove(session.listener);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(new IOException("Connector closed"));
        }
        return result;
    }

    private static SSLContext ssl(String fingerprint) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                throw new CertificateException("Client trust is not supported");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    if (chain.length == 0) throw new CertificateException("Missing certificate");
                    chain[0].checkValidity();
                    if (!MessageDigest.isEqual(HexFormat.of().parseHex(fingerprint), MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))
                        throw new CertificateException("Tunnel certificate does not match API fingerprint");
                } catch (java.security.GeneralSecurityException failure) {
                    throw new CertificateException("Tunnel certificate verification failed", failure);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return context;
    }

    private static void quietClose(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) { /* 关闭不影响存档所有权。 */ }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        timers.shutdownNow();
        sessions.forEach(Session::close);
        sockets.forEach(TunnelConnector::quietClose);
        listeners.forEach(TunnelConnector::quietClose);
        io.shutdownNow();
        synchronized (this) {
            if (http != null) http.shutdownNow();
        }
    }

}