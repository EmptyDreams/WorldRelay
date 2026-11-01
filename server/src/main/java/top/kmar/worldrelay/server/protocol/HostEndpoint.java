package top.kmar.worldrelay.server.protocol;

import top.kmar.worldrelay.server.json.JsonWriter;

import java.net.URI;
import java.net.URISyntaxException;

/** 只验证地址语法，不解析 DNS、不探测连通性。IPv6 地址不带方括号。 */
public record HostEndpoint(String address, int port) {

    public HostEndpoint {
        if (address == null || address.isBlank() || address.length() > 253
            || address.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
            || address.contains("[") || address.contains("]") || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid host endpoint");
        }
        try {
            String host = address.contains(":") ? "[" + address + "]" : address;
            URI uri = new URI("relay://" + host + ":" + port);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPort() != port) {
                throw new IllegalArgumentException("Invalid host endpoint");
            }
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid host endpoint");
        }
    }

    public void writeJson(JsonWriter writer) {
        writer.beginObject().name("address").value(address).name("port").value(port).endObject();
    }

}