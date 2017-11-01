package io.vertx.ext.eventbus.client.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.ext.eventbus.client.EventBusClientOptions;
import io.vertx.ext.eventbus.client.options.WebSocketTransportOptions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class WebSocketTransportChannel extends TransportChannel {

  private ChannelHandlerContext handlerCtx;
  private boolean handshakeComplete = false;
  private boolean reading;
  private boolean flush;

  WebSocketTransportChannel(Transport transport, EventBusClientOptions options) {
    super(transport, options);
  }

  @Override
  protected void initChannel(Channel channel) throws Exception {
    super.initChannel(channel);

    final WebSocketTransportOptions options = this.options.<WebSocketTransportOptions>getTransportOptions();

    StringBuilder url = new StringBuilder();
    url.append("ws");
    if(this.options.isSsl()) {
      url.append("s");
    }
    url.append("://").append(this.options.getHost()).append(options.getPath()).append("/websocket");

    WebSocketClientHandshaker handshaker =
      WebSocketClientHandshakerFactory.newHandshaker(new URI(url.toString()),
                                                     WebSocketVersion.V13,
                                                     null,
                                                     false,
                                                     new DefaultHttpHeaders(),
        options.getMaxWebsocketFrameSize());
    WebSocketClientProtocolHandler handler = new WebSocketClientProtocolHandler(handshaker);

    ChannelPipeline pipeline = channel.pipeline();

    if (options.getIdleTimeout() > 0) {
      pipeline.addLast("idleStateHandler", new IdleStateHandler(0, 0, options.getIdleTimeout(), TimeUnit.MILLISECONDS));
      pipeline.addLast("idleEventHandler", new ChannelDuplexHandler() {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
          if (evt instanceof IdleStateEvent) {
            handshaker.close(channel, new CloseWebSocketFrame());
          }
        }
      });
    }

    pipeline.addLast(new HttpClientCodec());
    pipeline.addLast(new HttpObjectAggregator(8192));
    pipeline.addLast(handler);
    pipeline.addLast(new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
          handlerCtx = ctx;
          handshakeComplete = true;
          transport.connectedHandler.handle(null);
        }
      }
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        reading = true;
        if (msg instanceof BinaryWebSocketFrame) {
          BinaryWebSocketFrame frame = (BinaryWebSocketFrame) msg;
          String json = frame.content().toString(StandardCharsets.UTF_8);
          transport.messageHandler.handle(json);
        } else {
          System.out.println("Unhandled " + msg);
        }
      }
      @Override
      public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        super.channelReadComplete(ctx);
        reading = false;
        if (flush) {
          flush = false;
          ctx.flush();
        }
      }
      @Override
      public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        handlerCtx = null;
        if(handshakeComplete) {
          transport.closeHandler.handle(null);
        }
      }
    });
  }

  void send(final String message) {
    if (handlerCtx.executor().inEventLoop()) {
      ByteBuf buff = handlerCtx.alloc().buffer();
      buff.writeCharSequence(message, StandardCharsets.UTF_8);
      BinaryWebSocketFrame frame = new BinaryWebSocketFrame(buff);
      if (reading) {
        flush = true;
        handleFutureSendError(handlerCtx, message, handlerCtx.write(frame));
      } else {
        handleFutureSendError(handlerCtx, message, handlerCtx.writeAndFlush(frame));
      }
    } else {
      handlerCtx.executor().execute(new Runnable() {
        @Override
        public void run() {
          send(message);
        }
      });
    }
  }

  ChannelFuture close() {
    if(this.handlerCtx != null) {
      return this.handlerCtx.close();
    }
    return null;
  }
}
