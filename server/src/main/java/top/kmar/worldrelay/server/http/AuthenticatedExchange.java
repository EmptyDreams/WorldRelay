package top.kmar.worldrelay.server.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/** JDK exchange 的属性可能共享于整个 context，认证身份必须保存在请求独立的包装器中。 */
final class AuthenticatedExchange extends HttpExchange {

    private final HttpExchange delegate;
    private final HttpPrincipal principal;

    AuthenticatedExchange(HttpExchange delegate, String username) {
        this.delegate = delegate;
        this.principal = new HttpPrincipal(username, "WorldRelay");
    }

    @Override
    public Object getAttribute(String name) {
        return BasicAuthenticationHandler.USERNAME_ATTRIBUTE.equals(name) ? principal.getUsername() : delegate.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (BasicAuthenticationHandler.USERNAME_ATTRIBUTE.equals(name)) {
            throw new IllegalArgumentException("Authenticated username is read-only");
        }
        delegate.setAttribute(name, value);
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Headers getRequestHeaders() {
        return delegate.getRequestHeaders();
    }

    @Override
    public Headers getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public URI getRequestURI() {
        return delegate.getRequestURI();
    }

    @Override
    public String getRequestMethod() {
        return delegate.getRequestMethod();
    }

    @Override
    public HttpContext getHttpContext() {
        return delegate.getHttpContext();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public InputStream getRequestBody() {
        return delegate.getRequestBody();
    }

    @Override
    public OutputStream getResponseBody() {
        return delegate.getResponseBody();
    }

    @Override
    public void sendResponseHeaders(int status, long length) throws IOException {
        delegate.sendResponseHeaders(status, length);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return delegate.getRemoteAddress();
    }

    @Override
    public int getResponseCode() {
        return delegate.getResponseCode();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public String getProtocol() {
        return delegate.getProtocol();
    }

    @Override
    public void setStreams(InputStream input, OutputStream output) {
        delegate.setStreams(input, output);
    }

}