package io.vertx.ext.eventbus.client.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.SucceededFuture;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.json.JsonCodec;
import io.vertx.ext.eventbus.client.EventBusClientOptions;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.HttpTransportOptions;
import io.vertx.ext.eventbus.client.options.ProxyOptions;
import io.vertx.ext.eventbus.client.options.ProxyType;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 *
 * Based on http://sockjs.github.io/sockjs-protocol/sockjs-protocol-0.3.3.html
 *
 * Noteworthy sections not copied here:
 * - Session URLs
 *   http://sockjs.github.io/sockjs-protocol/sockjs-protocol-0.3.3.html#section-36
 *
 * Not implemented URLs:
 * - disabled_websocket_echo - identical to echo, but with websockets disabled
 * - cookie_needed_echo - identical to echo, but with JSESSIONID cookies sent
 */
public class HttpTransport extends Transport {

  private enum AddSessionPart {
    AfterAddress,
    BeforeAddress
  }

  private enum State {
    Created,
    Connecting,
    Connected,
    Closed
  }

  private Logger logger;
  private JsonCodec jsonCodec;

  private String serverId;
  private String sessionPart;

  private Cookie jSessionIdCookie = null;

  private Promise<Void> connectFuture;
  private State state;
  private Channel currentXhrFetchRequestChannel;

  public HttpTransport(NioEventLoopGroup group, EventBusClientOptions options, JsonCodec jsonCodec) {
    super(group, options);
    this.bootstrap.channel(NioSocketChannel.class);
    this.serverId = String.format("%03d", new SecureRandom().nextInt(1000));
    this.logger = LoggerFactory.getLogger(HttpTransport.class);
    this.jsonCodec = jsonCodec;
    this.state = State.Created;
  }

  @Override
  public Future<Void> connect() {
    this.sessionPart = "/" + serverId + "/" + UUID.randomUUID().toString();
    this.connectFuture = this.group.next().newPromise();
    this.state = State.Connecting;
    this.performConnect();
    return this.connectFuture;
  }

  @Override
  public String toString() {
    return (HttpTransport.this.options.<HttpTransportOptions>getTransportOptions().isStreaming() ? "streaming" : "long-polling") + " HTTP transport";
  }

  private void performConnect() {
    if(HttpTransport.this.options.<HttpTransportOptions>getTransportOptions().isStreaming()) {
      HttpTransport.this.performXhrStreaming();
    } else {
      HttpTransport.this.performXhr();
    }
  }

  /**
   * Xhr polling url: /<server>/<session>/xhr
   */
  private Future<Void> performXhr() {
    return this.performRequest(HttpMethod.POST, "/xhr", AddSessionPart.BeforeAddress, null, true, true, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        HttpTransport.this.handleMessages(msg);
      }
    });
  }

  /**
   * Xhr streaming url: /<server>/<session>/xhr_streaming
   */
  private Future<Void> performXhrStreaming() {
    return this.performRequest(HttpMethod.POST, "/xhr_streaming", AddSessionPart.BeforeAddress, null, false, true, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        HttpTransport.this.handleMessages(msg);
      }
    });
  }

  // TODO: cancel performXhr or Streaming when closing down

  /**
   * SockJS client accepts following frames:
   *  o - Open frame. Every time a new session is established, the server must immediately send the open frame.
   *      This is required, as some protocols (mostly polling) can't distinguish between a properly established
   *      connection and a broken one - we must convince the client that it is indeed a valid url and it can be
   *      expecting further messages in the future on that url.
   *  h - Heartbeat frame. Most loadbalancers have arbitrary timeouts on connections. In order to keep connections
   *      from breaking, the server must send a heartbeat frame every now and then. The typical delay is 25 seconds
   *      and should be configurable.
   *  a - Array of json-encoded messages. For example: a["message1", "message2"].
   *  c - Close frame. This frame is send to the browser every time the client asks for data on closed connection.
   *      This may happen multiple times. Close frame contains a code and a string explaining a reason of closure,
   *      like: c[3000,"Go away!"] or c[2010,"Another connection still open"] (only one polling con. is allowed).
   *
   * @param msg the performRequest responseHandler object
   */
  private void handleMessages(Object msg) {
    if (msg instanceof HttpContent) {
      HttpContent content = (HttpContent) msg;
      String message = content.content().toString(CharsetUtil.UTF_8);

      if(message.length() > 1) {
        char command = message.charAt(0);
        switch (command) {
          case 'o':
            this.state = State.Connected;
            synchronized (HttpTransport.this) {
              if(this.connectFuture != null && !this.connectFuture.isDone()) {
                this.connectFuture.setSuccess(null);
                this.connectFuture = null;
                this.connectedHandler.handle(null);
              }
            }
            break;
          case 'h':
            // NOOP; heartbeat command from server
            break;
          case 'a':
            List busMessages = this.jsonCodec.decode(message.substring(1), ArrayList.class);
            for(Object busMessage : busMessages) {
              this.messageHandler.handle((String) busMessage);
            }
            break;
          case 'c':
            if(this.state == State.Connected) {
              this.logger.info("Server sent close command: " + message);
              this.close(null, false);
            } else {
              this.close(new Throwable("Server sent close command although we did not connect, yet."), false);
            }
            return;
          default:
            this.close(new Throwable("Unexpected command '" + command + "' received from server. Complete message: " + message), false);
            return;
        }
      }

      if (content instanceof LastHttpContent) {
        this.performConnect();
      }
    }
  }

  /**
   * Xhr send url: /<server>/<session>/xhr_send
   *
   * Framing accepted by the server
   *   SockJS server does not have any framing defined. All incoming data is treated as incoming messages, either
   *   single json-encoded messages or an array of json-encoded messages, depending on transport.
   */
  private Future<Void> performXhrSend(String message) {
    if(message == null) {
      this.handleError("Could not send message.", new Throwable("Message must not be null."));
      return null;
    }
    return this.performRequest(HttpMethod.POST, "/xhr_send", AddSessionPart.BeforeAddress, "[\"" + message.replace("\"", "\\\"") + "\"]", true, false, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        if (msg instanceof HttpResponse) {
          HttpResponse response = (HttpResponse) msg;
          if(response.status().code() != 204)
          {
            HttpTransport.this.close(new Throwable("Unexpected /xhr_send response status code '" + response.status().code() + "' received from server."), false);
          }
        }
        if (msg instanceof HttpContent) {
          HttpContent content = (HttpContent) msg;
          String data = content.content().toString(CharsetUtil.UTF_8);
          if(data.length() > 0) {
            HttpTransport.this.close(new Throwable("Unexpected /xhr_send response content: '" + data + "'."), false);
          }
        }
      }
    });
  }

  /**
   * Close url: /close
   *
   * Server immediately closes the session.
   */
  private Future<Void> performClose() {
    return this.performRequest(HttpMethod.POST, "/close", AddSessionPart.AfterAddress, null, true, false, null);
  }

  /**
     * Sends a request to the bridge.
     *
     * @param address the path on the server to call (excluding host, port, path defined in {@code EventBusClientOptions} and sessionPart), e.g. /close
     * @param addSessionPart whether the sessionPart should be added before the path
     * @param aggregateChunks whether any response chunks should be aggregated (else they are sent to the handler as chunks)
     * @param isXhrFetchRequest whether to store the channel, so it can be canceled later
     * @param responseHandler the object provided to the handler may be of types HttpResponse, HttpContent, LastHttpContent
     */
  private synchronized Future<Void> performRequest(HttpMethod method, String address, AddSessionPart addSessionPart, String body, boolean aggregateChunks, boolean isXhrFetchRequest, Handler<Object> responseHandler) {

    final HttpTransportOptions httpOptions = this.options.<HttpTransportOptions>getTransportOptions();
    ProxyOptions proxyOptions = this.options.getProxyOptions();
    boolean isDirectHttpProxy = proxyOptions != null && proxyOptions.getType() == ProxyType.HTTP_DIRECT;

    Promise<Void> responseFuture = this.group.next().newPromise();
    final AtomicReference<HttpResponseStatus> responseStatus = new AtomicReference<>(null);

    Bootstrap requestBootstrap = this.bootstrap.clone();
    requestBootstrap.handler(new HttpTransportChannel(this, aggregateChunks,
      new Handler<Channel>() {
        @Override
        public void handle(Channel channel) {

          if(isXhrFetchRequest) {
            HttpTransport.this.currentXhrFetchRequestChannel = channel;
          }

          String hostAndPort;
          if((options.isSsl() && options.getPort() == 443) || (!options.isSsl() && options.getPort() == 80)) {
            hostAndPort = options.getHost();
          } else {
            hostAndPort = options.getHost() + ":" + options.getPort();
          }

          StringBuilder url = new StringBuilder();
          if(isDirectHttpProxy)
          {
            url.append("http");
            if(options.isSsl()) {
              url.append("s");
            }
            url.append("://").append(hostAndPort);
          }
          url.append(httpOptions.getPath());
          if(addSessionPart == HttpTransport.AddSessionPart.BeforeAddress) {
            url.append(HttpTransport.this.sessionPart);
          }
          url.append(address);
          if(addSessionPart == HttpTransport.AddSessionPart.AfterAddress) {
            url.append(HttpTransport.this.sessionPart);
          }

          FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, url.toString());

          request.headers().set(HttpHeaderNames.HOST, hostAndPort);
          request.headers().set(HttpHeaderNames.CONNECTION, "Keep-Alive");
          request.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");

          if(isDirectHttpProxy && proxyOptions.getUsername() != null) {
            String authToken = proxyOptions.getUsername() + ":" + (proxyOptions.getPassword() != null ? proxyOptions.getPassword() : "");
            try {
              request.headers().set(HttpHeaderNames.PROXY_AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(authToken.getBytes("utf-8")));
            } catch (UnsupportedEncodingException e) {
              HttpTransport.this.close(new Throwable("Could not encode proxy authentication header.", e), false);
              return;
            }
          }

          if(httpOptions.getEnableJSessionIdCookie()) {
            request.headers().set("access-control-allow-origin", "true");
            if(HttpTransport.this.jSessionIdCookie != null) {
              request.headers().set(HttpHeaderNames.COOKIE, io.netty.handler.codec.http.cookie.ClientCookieEncoder.STRICT.encode(HttpTransport.this.jSessionIdCookie));
            }
          }

          if(body != null) {
            ByteBuf bodyBuffer = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBuffer.readableBytes());
            request.content().clear().writeBytes(bodyBuffer);
          }

          ChannelFuture writeFuture = channel.writeAndFlush(request);
          writeFuture.addListener(new GenericFutureListener<Future<Void>>() {
            @Override
            public void operationComplete(Future<Void> future) {
              if(!future.isSuccess()) {
                HttpTransport.this.close(new Throwable("Could not send request to server."), false);
              }
            }
          });
        }
      },
      new Handler<Object>() {
        @Override
        public void handle(Object msg) {
          if(msg instanceof Throwable) {
            HttpTransport.this.close(new Throwable("Request for address " + address + " resulted in an exception."), false);
            return;
          }
          else if(responseHandler != null) {
            responseHandler.handle(msg);
          }
          if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            responseStatus.set(response.status());
            if (httpOptions.getEnableJSessionIdCookie() && !response.headers().isEmpty()) {
              List<String> rawCookies = response.headers().getAll("Set-Cookie");
              for(String rawCookie : rawCookies) {
                Cookie cookie = ClientCookieDecoder.LAX.decode(rawCookie);
                if(cookie.name().equals("JSESSIONID")) {
                  HttpTransport.this.jSessionIdCookie = cookie;
                }
              }
            }
          }
          if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            if (content instanceof LastHttpContent) {
              if(responseStatus.get().code() == 200 || responseStatus.get().code() == 204) {
                synchronized (HttpTransport.this) {
                  if(!responseFuture.isDone()) {
                    responseFuture.setSuccess(null);
                  }
                }
              } else if(responseStatus.get().code() == 404) {
                HttpTransport.this.close(new Throwable("Server returned status 404: session unknown."), false);
              } else {
                HttpTransport.this.close(new Throwable("Request for address " + address + " resulted in status code " + responseStatus.get().code() + "."), false);
              }
            }
          }
        }
      }));

    Future<InetSocketAddress> addressFuture;
    if(isDirectHttpProxy) {
      // As the bootstrap resolver has been set to NoopAddressResolverGroup, we need to resolve manually here
      addressFuture = DefaultAddressResolverGroup.INSTANCE.getResolver(this.group.next()).resolve(InetSocketAddress.createUnresolved(proxyOptions.getHost(), proxyOptions.getPort()));
    } else {
      addressFuture = new SucceededFuture<>(this.group.next(), InetSocketAddress.createUnresolved(this.options.getHost(), this.options.getPort()));
    }

    addressFuture.addListener(new GenericFutureListener<Future<InetSocketAddress>>() {
      @Override
      public void operationComplete(Future<InetSocketAddress> addressFuture) throws Exception {
        if(!addressFuture.isSuccess()) {
          HttpTransport.this.close(new Throwable("Request for address " + address + " could not be performed. Proxy address could not be resolved.", addressFuture.cause()), false);
          return;
        }

        requestBootstrap.connect(addressFuture.getNow()).addListener(new GenericFutureListener<Future<Void>>() {
          @Override
          public void operationComplete(Future<Void> future) throws Exception {
            if(!future.isSuccess()) {
              HttpTransport.this.close(new Throwable("Request for address " + address + " could not be performed.", future.cause()), false);
            }
          }
        });
      }
    });

    return responseFuture;
  }


  @Override
  public void handleError(String message, Throwable throwable) {
    if(this.state != State.Closed) {
      super.handleError(message, throwable);
    }
  }

  @Override
  public void send(String message) {
    if(this.state != State.Connected) {
      this.logger.error("Could not send message on unconnected transport: " + message);
      return;
    }
    this.performXhrSend(message);
  }

  @Override
  public Future<Void> close() {
    if(this.state == State.Closed) {
      this.logger.error("Could not close unconnected transport.");
      return group.next().<Void>newFailedFuture(new Throwable("Could not close unconnected transport."));
    }
    return this.close(null, true);
  }

  private synchronized Future<Void> close(Throwable cause, boolean tellBridge) {

    if(this.connectFuture != null && !this.connectFuture.isDone()) {
      this.connectFuture.setFailure(cause == null ? new Exception() : cause);
      this.connectFuture = null;
    }
    else if(cause != null) {
      this.handleError(cause.getMessage(), cause.getCause());
    }

    if(this.state == State.Connected) {
      this.closeHandler.handle(false);
    }
    this.state = State.Closed;

    if(this.currentXhrFetchRequestChannel != null && this.currentXhrFetchRequestChannel.isOpen()) {
      this.currentXhrFetchRequestChannel.close();
    }

    if(tellBridge) {
      // TODO: performClose results in a 404
      // return this.performClose();
    }
    return group.next().<Void>newSucceededFuture(null);
  }
}
