package io.vertx.ext.eventbus.client.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.vertx.ext.eventbus.client.options.EventBusClientOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class TcpTransportChannel extends TransportChannel {

  private ChannelHandlerContext handlerCtx;
  private boolean handshakeComplete = false;
  private boolean reading;
  private boolean flush;

  TcpTransportChannel(Transport transport, EventBusClientOptions options) {
    super(transport, options);
  }

  @Override
  protected void initChannel(Channel channel) throws Exception {
    super.initChannel(channel);

    ChannelPipeline pipeline = channel.pipeline();

    if (this.options.getTcpTransportOptions().getIdleTimeout() > 0) {
      pipeline.addLast("idleStateHandler", new IdleStateHandler(0, 0, this.options.getTcpTransportOptions().getIdleTimeout(), TimeUnit.MILLISECONDS));
      pipeline.addLast("idleEventHandler", new ChannelDuplexHandler() {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
          if (evt instanceof IdleStateEvent) {
            ctx.close();
          }
        }
      });
    }

    pipeline.addLast(new ByteToMessageDecoder() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        reading = true;
        super.channelRead(ctx, msg);
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
      public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        handlerCtx = ctx;
        if(!TcpTransportChannel.this.options.isSsl()) {
          handshakeComplete = true;
          transport.connectedHandler.handle(null);
        }
      }
      @Override
      protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        while (true) {
          if (in.readableBytes() < 4) {
            break;
          }
          int readerIdx = in.readerIndex();
          int len = in.getInt(readerIdx);
          if (in.readableBytes() < 4 + len) {
            return;
          }
          String json = in.toString(readerIdx + 4, len, StandardCharsets.UTF_8);
          in.readerIndex(readerIdx + 4 + len);
          transport.messageHandler.handle(json);
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

  @Override
  void sslConnectedHandler(Channel channel) {
    handshakeComplete = true;
    transport.connectedHandler.handle(null);
  }

  void send(final String message) {
    if (handlerCtx.executor().inEventLoop()) {
      ByteBuf buff = handlerCtx.alloc().buffer();
      buff.writeInt(0);
      buff.writeCharSequence(message, StandardCharsets.UTF_8);
      buff.setInt(0, buff.readableBytes() - 4);
      if (reading) {
        flush = true;
        handleFutureSendError(handlerCtx, message, handlerCtx.write(buff));
      } else {
        handleFutureSendError(handlerCtx, message, handlerCtx.writeAndFlush(buff));
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
