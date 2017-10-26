package io.vertx.ext.eventbus.client;

import io.vertx.ext.eventbus.client.json.GsonCodec;
import io.vertx.ext.eventbus.client.options.EventBusClientOptions;
import io.vertx.ext.eventbus.client.options.HttpTransportOptions;
import io.vertx.ext.unit.TestContext;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class HttpStreamingBusTest extends HttpLongPollingBusTest {

  @Override
  protected EventBusClient client(TestContext ctx) {
    EventBusClientOptions options = new EventBusClientOptions().setPort(7000)
                                                               .setHttpTransportOptions(new HttpTransportOptions().setPath("/eventbus-test").setStreaming(true));
    ctx.put("clientOptions", options);
    ctx.put("codec", new GsonCodec());
    return EventBusClient.http(options, new GsonCodec());
  }
}
