package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class AuthenticationHandlersTest {

    /** 验证注册和创建世界均只允许本地地址与真实对端同为 127.0.0.1，伪造转发头不能绕过限制。 */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/register", "/api/v1/worlds"})
    void registrationRequiresExactSocketAddressesAndIgnoresForwardingHeaders(String path) throws Exception {
        LocalRegistrationHandler handler = new LocalRegistrationHandler(exchange -> HttpResponses.noContent(exchange, 204));
        AddressExchange local = new AddressExchange("127.0.0.1", "127.0.0.1");
        local.path = path;
        // 转发头既不能授予权限，也不能改变真实本地连接的身份。
        local.getRequestHeaders().set("X-Forwarded-For", "192.0.2.1");
        handler.handle(local);
        assertEquals(204, local.getResponseCode());
        for (String[] addresses : new String[][]{{"127.0.0.1", "192.0.2.1"}, {"127.0.0.1", "127.0.0.2"},
            {"127.0.0.1", "::1"}, {"0.0.0.0", "127.0.0.1"}}) {
            AddressExchange rejected = new AddressExchange(addresses[0], addresses[1]);
            rejected.path = path;
            rejected.getRequestHeaders().set("X-Forwarded-For", "127.0.0.1");
            rejected.getRequestHeaders().set("Forwarded", "for=127.0.0.1");
            handler.handle(rejected);
            assertEquals(403, rejected.getResponseCode());
            assertTrue(rejected.body.toString(StandardCharsets.UTF_8).contains("LOCAL_ACCESS_REQUIRED"));
            assertEquals("no-store", rejected.getResponseHeaders().getFirst("Cache-Control"));
        }
    }

    /** 真实网络不能伪造对端地址，这里仅替代 socket 元数据来验证边界。 */
    private static final class AddressExchange extends HttpExchange {

        private final InetSocketAddress local;
        private final InetSocketAddress remote;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status = -1;
        private String path;

        AddressExchange(String local, String remote) {
            this.local = new InetSocketAddress(local, 8081);
            this.remote = new InetSocketAddress(remote, 12345);
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create(path);
        }

        @Override
        public String getRequestMethod() {
            return "POST";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return body;
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            this.status = status;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return remote;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return local;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }

    }

}
