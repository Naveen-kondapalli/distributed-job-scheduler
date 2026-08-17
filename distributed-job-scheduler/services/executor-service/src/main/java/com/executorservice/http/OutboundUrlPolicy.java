package com.executorservice.http;

import com.executorservice.config.ExecutorProperties;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboundUrlPolicy {

    private final ExecutorProperties properties;

    public void validate(URI uri) {
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("HTTP job URL must include a host");
        }

        Set<String> allowedHosts = properties.getHttp().getAllowedHosts().stream()
                .map(hostname -> hostname.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(normalizedHost)) {
            throw new IllegalArgumentException("HTTP target host is not allowlisted");
        }

        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(address)) {
                    throw new IllegalArgumentException("HTTP target resolves to a blocked address");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to resolve HTTP target host");
        }
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCloudMetadataAddress(address);
    }

    private boolean isCloudMetadataAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && Byte.toUnsignedInt(bytes[0]) == 169
                && Byte.toUnsignedInt(bytes[1]) == 254
                && Byte.toUnsignedInt(bytes[2]) == 169
                && Byte.toUnsignedInt(bytes[3]) == 254;
    }
}
