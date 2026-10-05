package com.dameokja.backend.push.infrastructure;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDnsResolverTest {
    private static final String HOST = "fcm.googleapis.com";
    private final DnsResolver delegate = mock(DnsResolver.class);
    private final PushDnsResolver resolver = new PushDnsResolver(delegate);

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "2001:4860::8888", "2606:4700::1111"})
    void keepsPublicAddresses(String literal) throws Exception {
        InetAddress address = InetAddress.ofLiteral(literal);
        when(delegate.resolve(HOST)).thenReturn(new InetAddress[]{address});
        assertThat(resolver.resolve(HOST, 443).getFirst().getAddress()).isSameAs(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.255", "127.0.0.1", "169.254.169.254",
            "172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.2.1", "192.88.99.1", "192.168.0.1", "198.18.0.1",
            "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::a00:1",
            "2001::1", "2001:db8::1", "2002:7f00:1::", "3ffe::1", "3fff::1"})
    void rejectsAnyNonPublicAnswerIncludingMixedResults(String literal) throws Exception {
        when(delegate.resolve(HOST)).thenReturn(new InetAddress[]{InetAddress.ofLiteral("8.8.8.8"), InetAddress.ofLiteral(literal)});
        assertThatThrownBy(() -> resolver.resolve(HOST, 443)).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsMissingAnswers() throws Exception {
        when(delegate.resolve(HOST)).thenReturn(new InetAddress[0], (InetAddress[]) null, new InetAddress[]{null});
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void connectsToCheckedAddressWithoutResolvingAgainAndBlocksRebinding() throws Exception {
        InetAddress publicAddress = InetAddress.ofLiteral("8.8.8.8");
        when(delegate.resolve(HOST)).thenReturn(new InetAddress[]{publicAddress}, new InetAddress[]{InetAddress.ofLiteral("127.0.0.1")});
        Socket socket = mock(Socket.class);
        DefaultHttpClientConnectionOperator operator = new DefaultHttpClientConnectionOperator(proxy -> socket, null, resolver, scheme -> null);
        ManagedHttpClientConnection connection = mock(ManagedHttpClientConnection.class);
        HttpHost host = new HttpHost("https", HOST, 443);
        operator.connect(connection, host, null, Timeout.ofSeconds(1), SocketConfig.DEFAULT, HttpClientContext.create());
        verify(socket).connect(new InetSocketAddress(publicAddress, 443), 1000);
        assertThatThrownBy(() -> operator.connect(connection, host, null, Timeout.ofSeconds(1), SocketConfig.DEFAULT,
                HttpClientContext.create())).isInstanceOf(UnknownHostException.class);
        verify(delegate, times(2)).resolve(HOST);
        verify(socket, times(1)).connect(any(), anyInt());
    }
}
