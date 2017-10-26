package io.vertx.ext.eventbus.client.transport;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.EventBusClientOptions;

public class WebSocketTransport extends Transport {

  private WebSocketTransportChannel channel;
  private Logger logger;

  public WebSocketTransport(NioEventLoopGroup group, EventBusClientOptions options) {
    super(group, options);
    this.bootstrap.channel(NioSocketChannel.class);
    this.logger = LoggerFactory.getLogger(WebSocketTransport.class);
  }

  @Override
  public Future<Void> connect() {
    this.channel = new WebSocketTransportChannel(this, options);
    this.bootstrap.handler(this.channel);
    return this.bootstrap.connect(this.options.getHost(), this.options.getPort());
  }

  public void send(String message) {
    if(this.channel == null) {
      this.logger.error("Could not send message on unconnected transport: " + message);
      return;
    }
    this.channel.send(message);
  }

  public Future<Void> close() {
    if(this.channel == null) {
      this.logger.error("Could not close unconnected transport.");
      return group.next().<Void>newFailedFuture(new Throwable("Could not close unconnected transport."));
    }
    return this.channel.close();
  }
}
