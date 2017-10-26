package io.vertx.ext.eventbus.client;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.eventbus.client.json.GsonCodec;
import io.vertx.ext.eventbus.client.options.*;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class HttpLongPollingBusTest extends TcpBusTest {

  static int MAX_WEBSOCKET_FRAME_SIZE = 1024 * 1024;
  private static HttpProxyServer proxy;

  @BeforeClass
  public static void beforeClass() {
    proxy = DefaultHttpProxyServer.bootstrap().withPort(8000).withAllowLocalOnly(true).start();
  }

  @AfterClass
  public static void afterClass() {
    proxy.stop();
  }

  @Override
  protected EventBusClient client(TestContext ctx) {
    EventBusClientOptions options = new EventBusClientOptions().setPort(7000)
                                                               .setHttpTransportOptions(new HttpTransportOptions().setPath("/eventbus-test").setStreaming(false));
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
    HttpServer server = vertx.createHttpServer(new HttpServerOptions().setMaxWebsocketFrameSize(Integer.MAX_VALUE))
      .requestHandler(router::accept)
      .listen(7000, ctx.asyncAssertSuccess());

    vertx.createHttpServer(new HttpServerOptions().setMaxWebsocketFrameSize(Integer.MAX_VALUE).setSsl(true).setKeyStoreOptions(
      new JksOptions().setPath("server-keystore.jks").setPassword("wibble")
    ))
      .requestHandler(router::accept)
      .listen(7001, ctx.asyncAssertSuccess());

    ctx.put("bridge", server);
  }

  @Override
  protected void stopBridge(TestContext ctx, Handler<Void> handler) {

    ctx.<HttpServer>get("bridge").close(v -> {
      ctx.assertTrue(v.succeeded());
      handler.handle(null);
    });
  }

  @Override
  protected void startBridge(TestContext ctx, Handler<Void> handler) {

    ctx.<HttpServer>get("bridge").listen(7000, v -> {
      ctx.assertTrue(v.succeeded());
      handler.handle(null);
    });
  }

  @Override
  public void testIdleTimeout(final TestContext ctx) throws Exception
  {
    // Is not applicable to HttpTransport
  }

  @Test
  public void testProxyHttpSsl(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7001).setSsl(true).setTrustAll(true).setVerifyHost(false).setAutoReconnect(false)
                                                   .setProxyOptions(new ProxyOptions(ProxyType.HTTP, "localhost", 8000));

    performHelloWorld(ctx, async, client);

    async.awaitSuccess(3000);
  }

  @Test
  public void testProxyHttp(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
      .setProxyOptions(new ProxyOptions(ProxyType.HTTP, "localhost", 8000));

    performHelloWorld(ctx, async, client);
  }

  @Test
  public void testProxyHttpFailure(final TestContext ctx) {
    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false)
      .setProxyOptions(new ProxyOptions(ProxyType.HTTP, "localhost", 8001));

    client.connectedHandler(event -> {
      client.close();
      ctx.fail("Should not connect.");
    });

    client.exceptionHandler(event -> async.complete());

    client.connect();
  }
}
