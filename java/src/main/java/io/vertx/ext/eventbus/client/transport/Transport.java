package io.vertx.ext.eventbus.client.transport;

import io.netty.channel.*;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.vertx.ext.eventbus.client.EventBusClient;
import io.vertx.ext.eventbus.client.EventBusClientOptions;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.TrustOptions;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public abstract class Transport extends ChannelInitializer {

  protected EventBusClientOptions options;
  protected Logger logger;

  Handler<Void> connectedHandler;
  Handler<String> messageHandler;
  Handler<Void> closeHandler;

  private Handler<Throwable> exceptionHandler;

  Transport(EventBusClientOptions options) {
    this.options = options;
    this.logger = LoggerFactory.getLogger(Transport.class);
  }

  @Override
  protected void initChannel(Channel channel) throws Exception {
    ChannelPipeline pipeline = channel.pipeline();

    channel.config().setConnectTimeoutMillis(this.options.getConnectTimeout());

    if(this.options.isSsl()) {
      TrustManagerFactory trustManagerFactory = null;
      SSLParameters sslParams = new SSLParameters();

      if(this.options.isTrustAll()) {
        trustManagerFactory = InsecureTrustManagerFactory.INSTANCE;
      }
      else if(this.options.getTrustOptions() != null) {
        TrustOptions trustOptions = this.options.getTrustOptions();

        KeyStore keyStore = trustOptions.getKeyStore();

        trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);

        if(this.options.isVerifyHost())
        {
          sslParams.setEndpointIdentificationAlgorithm("HTTPS");
        }
      }

      SSLContext clientContext = SSLContext.getInstance("TLS");
      clientContext.init(null, trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), new SecureRandom());
      SSLEngine sslEngine = clientContext.createSSLEngine(this.options.getHost(), this.options.getPort());
      sslEngine.setUseClientMode(true);
      sslEngine.setSSLParameters(sslParams);

      SslHandler sslHandler = new SslHandler(sslEngine, false);
      sslHandler.handshakeFuture().addListener(new GenericFutureListener<Future<? super Channel>>() {
          @Override
          public void operationComplete(Future future) {
            Transport.this.sslHandshakeHandler(future);
          }
        });
      pipeline.addLast("sslHandler", sslHandler);
      pipeline.addLast("sslExceptionHandler", new ChannelHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

          // NOOP - suppress connection errors, as those are being handled by the handshake listener above
        }
      });
    }

    if (this.options.getIdleTimeout() > 0) {
      pipeline.addLast("idleStateHandler", new IdleStateHandler(0, 0, this.options.getIdleTimeout(), TimeUnit.MILLISECONDS));
      pipeline.addLast("idleEventHandler", new ChannelDuplexHandler() {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
          if (evt instanceof IdleStateEvent) {
            ctx.close();
          }
        }
      });
    }
  }

  public void connectedHandler(Handler<Void> handler) {
    connectedHandler = handler;
  }

  public void messageHandler(Handler<String> handler) {
    messageHandler = handler;
  }

  public void closeHandler(Handler<Void> handler) {
    closeHandler = handler;
  }

  public void setExceptionHandler(Handler<Throwable> handler) {
    this.exceptionHandler = handler;
  }

  protected void handleError(String message, Throwable t) {
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

  public abstract void sslHandshakeHandler(Future<Channel> future);
  public abstract void send(String message);
}
