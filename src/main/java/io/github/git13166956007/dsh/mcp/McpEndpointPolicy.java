package io.github.git13166956007.dsh.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/** Validates remote MCP endpoints before the HTTP client is allowed to connect. */
public final class McpEndpointPolicy {
    private final boolean allowPrivateNetworks;

    public McpEndpointPolicy(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public void validateSyntax(String endpoint) {
        URI uri = parse(endpoint);
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("MCP HTTP endpoint must use http or https");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("MCP endpoint user info is not allowed");
        if (uri.getFragment() != null) throw new IllegalArgumentException("MCP endpoint fragments are not allowed");
        if (uri.getPort() < -1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("MCP endpoint port is invalid");
        }
    }

    public void validateForConnection(String endpoint) {
        validateSyntax(endpoint);
        if (allowPrivateNetworks) return;
        URI uri = parse(endpoint);
        String host = uri.getHost();
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isPrivateOrLocal(address)) {
                    throw new IllegalArgumentException("MCP endpoint resolves to a private or local address: " + host);
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("MCP endpoint host cannot be resolved: " + host, exception);
        }
    }

    private static URI parse(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("MCP endpoint is required");
        try {
            URI uri = URI.create(endpoint.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("MCP endpoint must be an absolute URL with a host");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid MCP endpoint: " + endpoint, exception);
        }
    }

    private static boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0 || first == 10 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19));
        }
        return (bytes[0] & 0xfe) == 0xfc;
    }
}
