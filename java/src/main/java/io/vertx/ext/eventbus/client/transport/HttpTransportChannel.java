package io.vertx.ext.eventbus.client.transport;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 */
class HttpTransportChannel extends TransportChannel {

  private boolean aggregateChunks;
  private Handler<Channel> channelHandler;
  private Handler<Object> responseHandler;

  HttpTransportChannel(Transport transport, boolean aggregateChunks, Handler<Channel> channelHandler, Handler<Object> responseHandler) {
    super(transport);
    this.aggregateChunks = aggregateChunks;
    this.channelHandler = channelHandler;
    this.responseHandler = responseHandler;
  }

  @Override
  protected void initChannel(Channel channel) throws Exception {
    super.initChannel(channel);

    // TODO: things like max body size... configure it!

    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(new HttpClientCodec());
    //  pipeline.addLast(new HttpContentDecompressor());
    if(this.aggregateChunks) {
      pipeline.addLast(new HttpObjectAggregator(20 * 1024 * 1024));
    }
    pipeline.addLast(new SimpleChannelInboundHandler<HttpObject>() {

      @Override
      public void channelRead0 (ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof HttpContent) {
          HttpContent content = (HttpContent) msg;
          if (content instanceof LastHttpContent) {
            ctx.close();
          }
        }
        HttpTransportChannel.this.responseHandler.handle(msg);
      }

      @Override
      public void exceptionCaught (ChannelHandlerContext ctx, Throwable cause){
        transport.handleError("A connection exception occured.", cause);
        HttpTransportChannel.this.transport.closeHandler.handle(false);
      }
    });
  }

  @Override
  void handshakeCompleteHandler(Channel channel) {
    this.channelHandler.handle(channel);
  }
}
