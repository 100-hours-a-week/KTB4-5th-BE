package com.dameokja.backend.push.infrastructure;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

final class PushDnsResolver implements DnsResolver {
    // IANA 특수 용도·전환 대역은 푸시 목적지로 쓰지 않는다. 확인: 2026-10-02
    // https://www.iana.org/assignments/iana-ipv4-special-registry/
    // https://www.iana.org/assignments/iana-ipv6-special-registry/
    private static final List<Network> BLOCKED = List.of("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
            "2001::/23", "2001:db8::/32", "2002::/16", "3ffe::/16", "3fff::/20").stream().map(Network::parse).toList();
    private static final Network IPV6_GLOBAL_UNICAST = Network.parse("2000::/3");
    private final DnsResolver delegate;

    PushDnsResolver(DnsResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("푸시 목적지의 IP 주소가 없습니다.");
        }
        for (InetAddress address : addresses) {
            if (address == null || (!(address instanceof Inet4Address) && !IPV6_GLOBAL_UNICAST.contains(address))
                    || BLOCKED.stream().anyMatch(network -> network.contains(address))) {
                throw new UnknownHostException("허용되지 않은 푸시 목적지 IP 주소입니다.");
            }
        }
        // 기본 resolve(host, port)는 이 IP 객체로 소켓 주소를 만들어 추가 DNS 조회를 하지 않는다.
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // VAPID 인증에는 정식 호스트명 변환이 필요 없으므로 별도 DNS 조회를 하지 않는다.
        return host;
    }

    private record Network(byte[] address, int prefixBits) {
        static Network parse(String cidr) {
            String[] parts = cidr.split("/");
            return new Network(InetAddress.ofLiteral(parts[0]).getAddress(), Integer.parseInt(parts[1]));
        }

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) {
                return false;
            }
            for (int bit = 0; bit < prefixBits; bit++) {
                int mask = 1 << (Byte.SIZE - 1 - bit % Byte.SIZE);
                if ((bytes[bit / Byte.SIZE] & mask) != (address[bit / Byte.SIZE] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
