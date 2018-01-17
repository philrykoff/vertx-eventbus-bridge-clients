package io.vertx.ext.eventbus.client;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.client.json.GsonCodec;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.*;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.littleshoot.proxy.*;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Queue;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class HttpLongPollingBusTest extends TcpBusTest {

  static int MAX_WEBSOCKET_FRAME_SIZE = 1024 * 1024;

  private static HttpProxyServer httpProxy;
  private static int httpProxyPort = 8000;

  @BeforeClass
  public static void beforeClass() throws UnknownHostException {
    TcpBusTest.beforeClass();
    httpProxy = DefaultHttpProxyServer.bootstrap().withPort(httpProxyPort).withTransparent(true).start();
  }

  @AfterClass
  public static void afterClass() {
    TcpBusTest.afterClass();
    httpProxy.stop();
  }

  @Override
  protected EventBusClient client(TestContext ctx) {
    EventBusClientOptions options = new EventBusClientOptions().setPort(7000)
                                                               .setTransportOptions(new HttpTransportOptions().setPath("/eventbus-test").setStreaming(false));
    ctx.put("clientOptions", options);
    ctx.put("codec", new GsonCodec());
    return EventBusClient.http(options, new GsonCodec());
  }

  @Override
  protected void setUpBridges(TestContext ctx) {
    Router router = Router.router(vertx);
    BridgeOptions opts = new BridgeOptions()
      .setPingTimeout(15000)
      .addInboundPermitted(new PermittedOptions().setAddressRegex(".*"))
      .addOutboundPermitted(new PermittedOptions().setAddressRegex(".*"));
    SockJSHandler ebHandler = SockJSHandler.create(vertx).bridge(opts);
    router.route("/eventbus-test/*").handler(ebHandler);
    HttpServer server = vertx.createHttpServer(new HttpServerOptions().setMaxWebsocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE).setMaxWebsocketMessageSize(MAX_WEBSOCKET_FRAME_SIZE))
      .requestHandler(router::accept)
      .listen(7000, ctx.asyncAssertSuccess());

    vertx.createHttpServer(new HttpServerOptions().setMaxWebsocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE).setMaxWebsocketMessageSize(MAX_WEBSOCKET_FRAME_SIZE).setSsl(true).setKeyStoreOptions(
      new JksOptions().setPath("server-keystore.jks").setPassword("wibble")
    ))
      .requestHandler(router::accept)
      .listen(7001, ctx.asyncAssertSuccess());

    ctx.put("bridge", server);
  }

  @Override
  protected void startBridge(TestContext ctx, Handler<Void> handler) {

    ctx.<HttpServer>get("bridge").listen(7000, v -> {
      ctx.assertTrue(v.succeeded());
      handler.handle(null);
    });
  }

  @Override
  protected void stopBridge(TestContext ctx, Handler<Void> handler) {

    ctx.<HttpServer>get("bridge").close(v -> {
      ctx.assertTrue(v.succeeded());
      handler.handle(null);
    });
  }

  @Override
  public void testIdleTimeout(final TestContext ctx) throws Exception {
    LoggerFactory.getLogger(HttpLongPollingBusTest.class).info("HTTP long polling transport does not support idle timeout.");
  }

  @Test
  public void testProxyHttpConnect(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
                               .setProxyOptions(new ProxyOptions(ProxyType.HTTP_CONNECT, "localhost", httpProxyPort));

    performHelloWorld(ctx, async, client);
  }

  @Test
  public void testProxyHttpTransparent(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
                               .setProxyOptions(new ProxyOptions(ProxyType.HTTP_DIRECT, "localhost", httpProxyPort));

    performHelloWorld(ctx, async, client);
  }

  @Test
  public void testProxyHttpConnectSsl(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7001).setSsl(true).setTrustAll(true).setVerifyHost(false).setAutoReconnect(false)
      .setProxyOptions(new ProxyOptions(ProxyType.HTTP_CONNECT, "localhost", httpProxyPort).setUsername("foo").setPassword("bar"));

    performHelloWorld(ctx, async, client);

    async.awaitSuccess(3000);
  }

  @Test
  public void testProxyHttpConnectFailure(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
                               .setProxyOptions(new ProxyOptions(ProxyType.HTTP_CONNECT, "localhost", httpProxyPort + 1));

    client.connectedHandler(event -> {
      client.close();
      ctx.fail("Should not connect.");
    });

    client.exceptionHandler(event -> async.complete());

    client.connect();
  }

  @Test
  public void testProxyHttpTransparentFailure(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
                               .setProxyOptions(new ProxyOptions(ProxyType.HTTP_DIRECT, "localhost", httpProxyPort + 1));

    client.connectedHandler(event -> {
      client.close();
      ctx.fail("Should not connect.");
    });

    client.exceptionHandler(event -> async.complete());

    client.connect();
  }
}
