package io.vertx.ext.eventbus.client.transport;

import io.netty.channel.*;
import io.netty.handler.proxy.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.vertx.ext.eventbus.client.EventBusClientOptions;
import io.vertx.ext.eventbus.client.options.ProxyOptions;
import io.vertx.ext.eventbus.client.options.ProxyType;
import io.vertx.ext.eventbus.client.options.TrustOptions;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
abstract class TransportChannel extends ChannelInitializer {

  Transport transport;
  EventBusClientOptions options;

  TransportChannel(Transport transport, EventBusClientOptions options) {
    this.transport = transport;
    this.options = options;
  }

  /**
   * Adds Proxy, TLS and idle timeout handlers to the channel pipeline.
   *
   * @param channel channel to which to add the handlers to
   * @throws Exception any exception
   */
  @Override
  protected void initChannel(Channel channel) throws Exception {
    final ChannelPipeline pipeline = channel.pipeline();

    channel.config().setConnectTimeoutMillis(this.options.getConnectTimeout());

    if(this.options.getProxyOptions() == null && !this.options.isSsl()) {
      pipeline.addLast(new ChannelInboundHandlerAdapter() {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
          super.channelActive(ctx);
          TransportChannel.this.handshakeCompleteHandler(channel);
        }
      });
    }

    if(this.options.getProxyOptions() != null) {
      ProxyOptions proxyOptions = this.options.getProxyOptions();

      String proxyHost = proxyOptions.getHost();
      int proxyPort = proxyOptions.getPort();
      String proxyUsername = proxyOptions.getUsername();
      String proxyPassword = proxyOptions.getPassword();
      ProxyType proxyType = proxyOptions.getType();
      SocketAddress proxyAddress = new InetSocketAddress(proxyHost, proxyPort);

      final ProxyHandler proxyHandler;

      switch(proxyType) {
        default:
        case HTTP:
          proxyHandler = proxyUsername != null && proxyPassword != null ?
            new HttpProxyHandler(proxyAddress, proxyUsername, proxyPassword) : new HttpProxyHandler(proxyAddress);
          break;
        case SOCKS4:
          proxyHandler = proxyUsername != null ?
            new Socks4ProxyHandler(proxyAddress, proxyUsername) : new Socks4ProxyHandler(proxyAddress);
          break;
        case SOCKS5:
          proxyHandler = proxyUsername != null && proxyPassword != null ?
            new Socks5ProxyHandler(proxyAddress, proxyUsername, proxyPassword) : new Socks5ProxyHandler(proxyAddress);
          break;
      }

      pipeline.addLast("proxyHandler", proxyHandler);
      pipeline.addLast(new ChannelInboundHandlerAdapter() {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
          if (evt instanceof ProxyConnectionEvent) {
            pipeline.remove(proxyHandler);
            pipeline.remove(this);
            if(!TransportChannel.this.options.isSsl()) {
              TransportChannel.this.handshakeCompleteHandler(channel);
            }
          }
          ctx.fireUserEventTriggered(evt);
        }
      });
      pipeline.addLast("proxyExceptionHandler", new ChannelHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
          // TODO: ignore if closed
          transport.handleError("A proxy exception occured.", cause);
        }
      });
    }

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
      sslHandler.handshakeFuture().addListener(new GenericFutureListener<Future<Channel>>() {
          @Override
          public void operationComplete(Future<Channel> future) {
            if(future.isSuccess()) {
              TransportChannel.this.handshakeCompleteHandler(future.getNow());
            }
          }
        });
      pipeline.addLast("sslHandler", sslHandler);
      pipeline.addLast("sslExceptionHandler", new ChannelHandlerAdapter() {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
          transport.handleError("A TLS exception occured.", cause);
        }
      });
    }
  }

  /**
   * This method is being called by {@code TransportChannel} when the proxy & TLS handshake has been completed successfully.
   *
   * It can be overriden by implementations of {@code TransportChannel} to perform tasks.
   *
   * @param channel the channel for which the TLS handshake has been completed
   */
  void handshakeCompleteHandler(Channel channel) {
    // NOOP
  }

  /**
   * {@code TransportChannel} implementations can use this method to implement error handling for messages sent to the server.
   * @param handlerCtx the channel context
   * @param message the message being sent
   * @param future the channel future created by a {@code write} method
   */
  void handleFutureSendError(final ChannelHandlerContext handlerCtx, final String message, ChannelFuture future) {
    future.addListener(new GenericFutureListener<Future<Void>>() {
      @Override
      public void operationComplete(Future<Void> future) {
        // Suppress "Could not send because connection is closed" and SSLExceptions, as they are handled in sslExceptionHandler
        //noinspection ThrowableResultOfMethodCallIgnored
        if(!future.isSuccess() && handlerCtx.channel().isOpen() && !(future.cause() instanceof SSLException)) {
          if(message.length() > options.getMessagePrintLimit()) {
            transport.handleError("Could not send message with " + message.length() + " chars.", future.cause());
          } else {
            transport.handleError("Could not send message: " + message, future.cause());
          }
        }
      }
    });
  }
}
