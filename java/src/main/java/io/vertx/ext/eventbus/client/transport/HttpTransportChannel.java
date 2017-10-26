package io.vertx.ext.eventbus.client.transport;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.options.EventBusClientOptions;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 */
class HttpTransportChannel extends TransportChannel {

  private boolean aggregateChunks;
  private boolean compressed;
  private Handler<Object> responseHandler;

  HttpTransportChannel(Transport transport, EventBusClientOptions options, boolean aggregateChunks, boolean compressed, Handler<Object> responseHandler) {
    super(transport, options);
    this.aggregateChunks = aggregateChunks;
    this.compressed = compressed;
    this.responseHandler = responseHandler;
  }

  @Override
  protected void initChannel(Channel channel) throws Exception {
    super.initChannel(channel);

    // TODO: things like max body size... configure it!

    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(new HttpClientCodec());
    if(this.compressed) {
      // pipeline.addLast(new HttpContentDecompressor());
    }
    if(this.aggregateChunks) {
      pipeline.addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
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
        ctx.close();
        HttpTransportChannel.this.responseHandler.handle(cause);
      }
    });
  }
}
