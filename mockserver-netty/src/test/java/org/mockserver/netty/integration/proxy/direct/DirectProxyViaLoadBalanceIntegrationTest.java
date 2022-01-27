package org.mockserver.netty.integration.proxy.direct;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * @author jamesdbloom
 */
public class DirectProxyViaLoadBalanceIntegrationTest {

    private static ClientAndServer targetClientAndServer;
    private static ClientAndServer loadBalancerClientAndServer;

    private static final EventLoopGroup clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(DirectProxyViaLoadBalanceIntegrationTest.class.getSimpleName() + "-eventLoop"));

    private static final NettyHttpClient httpClient = new NettyHttpClient(new MockServerLogger(), clientEventLoopGroup, null, false);

    @BeforeClass
    public static void startServer() {
        targetClientAndServer = startClientAndServer();
        loadBalancerClientAndServer = startClientAndServer("127.0.0.1", targetClientAndServer.getPort());
    }

    @AfterClass
    public static void stopEventLoopGroup() {
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(targetClientAndServer);
        stopQuietly(loadBalancerClientAndServer);
    }

    @Before
    public void reset() {
        targetClientAndServer.reset();
        loadBalancerClientAndServer.reset();
    }

    @Test
    public void shouldNotForwardInLoopIndefinitely() throws Exception {
        Level originalLevel = ConfigurationProperties.logLevel();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            targetClientAndServer.reset();
            loadBalancerClientAndServer.reset();

            // when
            Future<HttpResponse> responseFuture =
                httpClient.sendRequest(
                    request()
                        .withPath("/some_path")
                        .withHeader(HOST.toString(), "localhost:" + loadBalancerClientAndServer.getPort()),
                    new InetSocketAddress(loadBalancerClientAndServer.getPort())
                );

            // then - returns 404
            assertThat(responseFuture.get(10, TimeUnit.MINUTES).getStatusCode(), is(404));

            // and - both proxy and target verify request received
            loadBalancerClientAndServer.verify(request().withPath("/some_path"));
            targetClientAndServer.verify(request().withPath("/some_path"));

            // and - logs hide proxied request
            String[] loadBalancerLogMessages = loadBalancerClientAndServer.retrieveLogMessagesArray(null);
            String[] targetLogMessages = targetClientAndServer.retrieveLogMessagesArray(null);
            assertThat(loadBalancerLogMessages[2], containsString("no expectation for:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"method\" : \"GET\"," + NEW_LINE +
                "    \"path\" : \"/some_path\"")
            );
            assertThat(loadBalancerLogMessages[2], containsString(" returning response:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"statusCode\" : 404," + NEW_LINE +
                "    \"reasonPhrase\" : \"Not Found\"")
            );
            // target server forward request to load balancer as Host header matches load balancer's Host
            assertThat(targetLogMessages[2], containsString("returning response:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"statusCode\" : 404," + NEW_LINE +
                "    \"reasonPhrase\" : \"Not Found\"")
            );
            assertThat(targetLogMessages[2], containsString("for forwarded request" + NEW_LINE +
                NEW_LINE +
                " in json:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"method\" : \"GET\"," + NEW_LINE +
                "    \"path\" : \"/some_path\"")
            );
        } finally {
            ConfigurationProperties.logLevel(originalLevel.name());
        }
    }

    @Test
    public void shouldReturnExpectationForTargetMockServer() throws Exception {
        Level originalLevel = ConfigurationProperties.logLevel();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            targetClientAndServer.reset();
            loadBalancerClientAndServer.reset();
            targetClientAndServer
                .when(
                    request()
                        .withPath("/target")
                )
                .respond(
                    response()
                        .withBody("target_response")
                );

            // when
            Future<HttpResponse> responseFuture =
                httpClient.sendRequest(
                    request()
                        .withPath("/target")
                        .withHeader(HOST.toString(), "localhost:" + loadBalancerClientAndServer.getPort()),
                    new InetSocketAddress(loadBalancerClientAndServer.getPort())
                );

            // then - returns 404
            HttpResponse httpResponse = responseFuture.get(10, TimeUnit.MINUTES);
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is("target_response"));

            // and - both proxy and target verify request received
            loadBalancerClientAndServer.verify(request().withPath("/target"));
            targetClientAndServer.verify(request().withPath("/target"));

            // and - logs hide proxied request
            String[] logMessages = loadBalancerClientAndServer.retrieveLogMessagesArray(null);
            assertThat(logMessages[2], containsString("returning response:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"statusCode\" : 200," + NEW_LINE +
                "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                "    \"headers\" : {" + NEW_LINE +
                "      \"content-length\" : [ \"15\" ]," + NEW_LINE +
                "      \"connection\" : [ \"keep-alive\" ]" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"body\" : \"target_response\"" + NEW_LINE +
                "  }" + NEW_LINE +
                NEW_LINE +
                " for forwarded request" + NEW_LINE +
                NEW_LINE +
                " in json:" + NEW_LINE +
                NEW_LINE +
                "  {" + NEW_LINE +
                "    \"method\" : \"GET\"," + NEW_LINE +
                "    \"path\" : \"/target\"," + NEW_LINE));
        } finally {
            ConfigurationProperties.logLevel(originalLevel.name());
        }
    }

    @Test
    public void shouldVerifyReceivedRequests() throws Exception {
        // given
        Future<HttpResponse> responseFuture =
            httpClient.sendRequest(
                request()
                    .withPath("/some_path")
                    .withHeader(HOST.toString(), "localhost:" + loadBalancerClientAndServer.getPort()),
                new InetSocketAddress(loadBalancerClientAndServer.getPort())
            );

        // then
        assertThat(responseFuture.get(10, TimeUnit.SECONDS).getStatusCode(), is(404));

        // then
        targetClientAndServer.verify(request()
            .withPath("/some_path"));
        targetClientAndServer.verify(request()
            .withPath("/some_path"), VerificationTimes.exactly(1));
    }

}
