package io.vertx.ext.eventbus.client;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.eventbus.client.json.GsonCodec;
import io.vertx.ext.eventbus.client.json.JsonCodec;
import io.vertx.ext.eventbus.client.options.WebSocketTransportOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import org.junit.*;

import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class WebSocketBusTest extends HttpStreamingBusTest {

  @BeforeClass
  public static void beforeClass() throws UnknownHostException {
    HttpLongPollingBusTest.beforeClass();
  }

  @AfterClass
  public static void afterClass() {
    HttpLongPollingBusTest.afterClass();
  }

  @Override
  protected EventBusClient client(TestContext ctx) {
    EventBusClientOptions options = new EventBusClientOptions().setPort(7000)
                                                               .setTransportOptions(new WebSocketTransportOptions().setPath("/eventbus-test")
                                                                                                                            .setMaxWebsocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE));
    ctx.put("clientOptions", options);
    ctx.put("codec", new GsonCodec());
    return EventBusClient.websocket(options);
  }

  @Override
  @Test
  public void testIdleTimeout(final TestContext ctx) throws Exception {
    final Async async = ctx.async(5);
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setAutoReconnectInterval(0).<WebSocketTransportOptions>getTransportOptions().setIdleTimeout(100);

    client.connectedHandler(event -> {
      countDownAndCloseClient(async, client);
    });

    client.connect();

    async.awaitSuccess(3000);
  }

  @Test
  public void testMaxWebSocketFrameSizeSend(final TestContext ctx) throws Exception {

    final Async async = ctx.async(2);
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false);

    vertx.eventBus().consumer("server_addr", msg -> {
      msg.reply(new JsonObject());
    });

    client.send("server_addr", getStringForJsonObjectTargetByteSize(ctx, "server_addr", 128), response -> {
      ctx.assertTrue(response.succeeded(), "Message within MaxWebSocketFrameSize limit should succeed.");
      countDownAndCloseClient(async, client);
    });
    client.send("server_addr", getStringForJsonObjectTargetByteSize(ctx, "server_addr", MAX_WEBSOCKET_FRAME_SIZE - 8), response -> {
      ctx.assertTrue(response.succeeded(), "Message within MaxWebSocketFrameSize limit should succeed.");
      countDownAndCloseClient(async, client);
    });
  }

  @Test
  public void testMaxWebSocketFrameSizeSendFail(final TestContext ctx) throws Exception {

    final Async async = ctx.async();
    EventBusClient client = client(ctx);

    ctx.<EventBusClientOptions>get("clientOptions").setPort(7000).setAutoReconnect(false);

    vertx.eventBus().consumer("server_addr", msg -> {
      msg.reply(new JsonObject());
    });

    client.exceptionHandler(event -> {
      // Is not being fired, as we don't have any indication of an error from the server
    });

    client.closeHandler(event -> {
      async.complete();
    });

    client.connectedHandler(event -> {

      client.send("server_addr", getStringForJsonObjectTargetByteSize(ctx, "server_addr", MAX_WEBSOCKET_FRAME_SIZE  *10), response -> {
        // This code will be reached when the request times out - or never, as the SockJS server just drops the connection instead of sending a proper error response
        ctx.assertFalse(response.succeeded(), "Should not be able to send more than MAX_WEBSOCKET_FRAME_SIZE");
      });
    });

    client.connect();
  }

  private String getStringForJsonObjectTargetByteSize(TestContext ctx, String address, int numberOfBytes) {

    String replyAddress = UUID.randomUUID().toString();
    int envelopeLength = ctx.<JsonCodec>get("codec").encode(this.getMessageEnvelope(address, replyAddress, "")).getBytes(StandardCharsets.UTF_8).length;

    String body = getStringWithSize(numberOfBytes - envelopeLength);
    Map<String, Object> currentCandidate = this.getMessageEnvelope("server_addr", replyAddress, body);
    int currentCandidateLength = ctx.<JsonCodec>get("codec").encode(currentCandidate).getBytes(StandardCharsets.UTF_8).length;

    ctx.assertEquals(numberOfBytes, currentCandidateLength, "Could not create string with target byte size.");

    return body;
  }

  private Map<String, Object> getMessageEnvelope(String address, String replyAddress, Object body) {

    Map<String, Object> obj = new HashMap<>();
    obj.put("type", "send");
    obj.put("address", address);
    obj.put("replyAddress", replyAddress);
    obj.put("body", body);
    return obj;
  }

  private String getStringWithSize(int numberOfBytes) {
    StringBuilder builder = new StringBuilder();
    for(int i = 0; i < numberOfBytes; ++i) {
      builder.append("x");
    }
    return builder.toString();
  }
}
