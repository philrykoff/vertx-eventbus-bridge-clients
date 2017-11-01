package io.vertx.ext.eventbus.client;

import io.vertx.ext.eventbus.client.json.JacksonCodec;
import io.vertx.ext.eventbus.client.options.WebSocketTransportOptions;
import io.vertx.ext.unit.TestContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.net.UnknownHostException;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class WebSocketJacksonBusTest extends WebSocketBusTest {

  @BeforeClass
  public static void beforeClass() throws UnknownHostException {
    WebSocketBusTest.beforeClass();
  }

  @AfterClass
  public static void afterClass() {
    WebSocketBusTest.afterClass();
  }

  @Override
  protected EventBusClient client(TestContext ctx) {
    EventBusClientOptions options = new EventBusClientOptions().setPort(7000)
                                                               .setTransportOptions(new WebSocketTransportOptions().setPath("/eventbus-test")
                                                                                                                   .setMaxWebsocketFrameSize(MAX_WEBSOCKET_FRAME_SIZE));
    ctx.put("clientOptions", options);
    ctx.put("codec", new JacksonCodec());
    return EventBusClient.websocket(options, new JacksonCodec());
  }
}
