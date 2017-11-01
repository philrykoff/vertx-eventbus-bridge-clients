package io.vertx.ext.eventbus.client.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.vertx.ext.eventbus.client.EventBusClientOptions;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;

public abstract class Transport {

  NioEventLoopGroup group;
  EventBusClientOptions options;
  Bootstrap bootstrap;
  private Logger logger;

  Handler<Void> connectedHandler;
  Handler<String> messageHandler;
  Handler<Void> closeHandler;
  private Handler<Throwable> exceptionHandler;

  Transport(NioEventLoopGroup group, EventBusClientOptions options) {
    this.group = group;
    this.bootstrap = new Bootstrap().group(group);
    this.options = options;
    this.logger = LoggerFactory.getLogger(Transport.class);
  }

  public abstract Future<Void> connect();

  public abstract void send(String message);

  public abstract Future<Void> close();

  public void connectedHandler(Handler<Void> handler) {
    this.connectedHandler = handler;
  }

  public void messageHandler(Handler<String> handler) {
    this.messageHandler = handler;
  }

  public void closeHandler(Handler<Void> handler) {
    this.closeHandler = handler;
  }

  public void exceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
  }

  void handleError(String message, Throwable t) {
    this.logger.error(message, t);
    Handler<Throwable> handler = this.exceptionHandler;
    if (handler != null) {
      try {
        handler.handle(t);
      } catch (Exception e) {
        // Exception handler should not throw
        e.printStackTrace();
      }
    }
  }
}
