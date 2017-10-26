package io.vertx.ext.eventbus.client.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.*;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import io.vertx.ext.eventbus.client.Handler;
import io.vertx.ext.eventbus.client.json.JsonCodec;
import io.vertx.ext.eventbus.client.options.EventBusClientOptions;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.HttpTransportOptions;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private Logger logger;
  private JsonCodec jsonCodec;

  private String serverId;
  private String sessionPart;

  private Cookie jSessionIdCookie = null;

  private AtomicBoolean connected;
  private AtomicBoolean closed;
  private Channel currentXhrFetchRequestChannel;

  public HttpTransport(NioEventLoopGroup group, EventBusClientOptions options, JsonCodec jsonCodec) {
    super(group, options);
    this.bootstrap.channel(NioSocketChannel.class);
    this.serverId = String.format("%03d", new SecureRandom().nextInt(1000));
    this.logger = LoggerFactory.getLogger(HttpTransport.class);
    this.jsonCodec = jsonCodec;
    this.connected = new AtomicBoolean(false);
    this.closed = new AtomicBoolean(false);
  }

  @Override
  public Future<Void> connect() {
    this.sessionPart = "/" + serverId + "/" + UUID.randomUUID().toString();
    Promise<Void> connectFuture = this.group.next().newPromise();
    if(HttpTransport.this.options.getHttpTransportOptions().isStreaming()) {
      HttpTransport.this.performXhrStreaming(connectFuture);
    } else {
      HttpTransport.this.performXhr(connectFuture);
    }
    return connectFuture;
  }

  /**
   * Xhr polling url: /<server>/<session>/xhr
   */
  private Future<Void> performXhr(Promise<Void> connectFuture) {
    return this.performRequest(HttpMethod.POST, "/xhr", AddSessionPart.BeforeAddress, null, true, true, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        HttpTransport.this.handleMessages(connectFuture, msg);
      }
    });
  }

  /**
   * Xhr streaming url: /<server>/<session>/xhr_streaming
   */
  private Future<Void> performXhrStreaming(Promise<Void> connectFuture) {
    return this.performRequest(HttpMethod.POST, "/xhr_streaming", AddSessionPart.BeforeAddress, null, false, true, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        HttpTransport.this.handleMessages(connectFuture, msg);
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
   * @param connectFuture the connect future, which is being completed by handleMessages upon receiving a positive response
   * @param msg the performRequest responseHandler object
   */
  private void handleMessages(Promise<Void> connectFuture, Object msg) {
    if (msg instanceof HttpContent) {
      HttpContent content = (HttpContent) msg;
      String message = content.content().toString(CharsetUtil.UTF_8);

      if(message.length() > 1) {
        char command = message.charAt(0);
        switch (command) {
          case 'o':
            this.connected.set(true);
            this.closed.set(false);
            if(connectFuture != null) {
              synchronized (HttpTransport.this) {
                if(!connectFuture.isDone()) {
                  connectFuture.setSuccess(null);
                  this.connectedHandler.handle(null);
                }
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
            if(this.connected.getAndSet(false)) {
              this.logger.info("Server sent close command: " + message);
              this.close(false);
            }
          default:
            this.logger.error("Unexpected command received from server: " + command);
            break;
        }
      }

      if (content instanceof LastHttpContent) {
        if(this.connected.get()) {
          if(this.options.getHttpTransportOptions().isStreaming()) {
            this.performXhrStreaming(null);
          } else {
            this.performXhr(null);
          }
        }
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
    return this.performRequest(HttpMethod.POST, "/xhr_send", AddSessionPart.BeforeAddress, "[\"" + message.replace("\"", "\\\"") + "\"]", true, false, this.createLogHandler("/xhr_send"));
  }

  /**
   * Close url: /close
   *
   * Server immediately closes the session.
   */
  private Future<Void> performClose() {
    return this.performRequest(HttpMethod.POST, "/close", AddSessionPart.AfterAddress, null, true, false, null);
  }

  private Handler<Object> createLogHandler(String address) {
    return new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        if (msg instanceof HttpContent) {
          HttpContent content = (HttpContent) msg;
          String data = content.content().toString(CharsetUtil.UTF_8);
          if(data.length() > 0) {
            HttpTransport.this.logger.info("Request for address " + address + " returned response: " + data);
          }
        }
      }
    };
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

    final EventBusClientOptions options = this.options;
    final HttpTransportOptions httpOptions = this.options.getHttpTransportOptions();
    Promise<Void> responseFuture = this.group.next().newPromise();
    final AtomicReference<HttpResponseStatus> responseStatus = new AtomicReference<>(null);

    this.bootstrap.handler(new HttpTransportChannel(this, this.options, aggregateChunks, false, new Handler<Object>() {
      @Override
      public void handle(Object msg) {
        if(msg instanceof Throwable) {
          HttpTransport.this.handleError(responseFuture, "Request for address " + address + " resulted in an exception.", (Throwable) msg);
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
              if(HttpTransport.this.connected.getAndSet(false)) {
                HttpTransport.this.logger.error("Server returned status 404, indicating that the current session is unknown. Disconnecting.");
                HttpTransport.this.close(false);
              }
            } else {
              HttpTransport.this.handleError(responseFuture,
                                             "Request for address " + address + " resulted in an error.",
                                             new Throwable("Request for address " + address + " did returned status code " + responseStatus.get().code() + ", indicating failure."));
            }
          }
        }
      }
    }));
    ChannelFuture channelFuture = this.bootstrap.connect(this.options.getHost(), this.options.getPort());
    channelFuture.addListener(new GenericFutureListener<Future<Void>>() {
      @Override
      public void operationComplete(Future<Void> future) {
        if(!future.isSuccess()) {
          responseHandler.handle(new Exception("Could not perform request for address " + address + ".", future.cause()));
          HttpTransport.this.handleError(responseFuture, "Could not perform request for address " + address + ".", future.cause());
          if(HttpTransport.this.connected.getAndSet(false)) {
            HttpTransport.this.close(false);
          }
          return;
        }

        Channel channel = channelFuture.channel();

        if(isXhrFetchRequest) {
          HttpTransport.this.currentXhrFetchRequestChannel = channel;
        }

        StringBuilder url = new StringBuilder();
        url.append("http");
        if(options.isSsl()) {
          url.append("s");
        }
        url.append("://").append(options.getHost()).append(httpOptions.getPath());
        if(addSessionPart == AddSessionPart.BeforeAddress) {
          url.append(HttpTransport.this.sessionPart);
        }
        url.append(address);
        if(addSessionPart == AddSessionPart.AfterAddress) {
          url.append(HttpTransport.this.sessionPart);
        }

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, url.toString());

        request.headers().set(HttpHeaderNames.HOST, options.getHost());
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");

        if(httpOptions.getEnableJSessionIdCookie()) {
          request.headers().set("access-control-allow-origin", "true");
          if(HttpTransport.this.jSessionIdCookie != null) {
            request.headers().set(HttpHeaderNames.COOKIE, ClientCookieEncoder.STRICT.encode(HttpTransport.this.jSessionIdCookie));
          }
        }

        if(body != null)
        {
          ByteBuf bodyBuffer = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
          request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBuffer.readableBytes());
          request.content().clear().writeBytes(bodyBuffer);
        }

        channel.writeAndFlush(request);
        // Do not close the channel, as sometimes it needs to stay open to fetch a response, server closes
      }
    });
    return responseFuture;
  }

  @Override
  public void handleError(String message, Throwable throwable) {
    if(!this.closed.get()) {
      super.handleError(message, throwable);
    }
  }

  private void handleError(Promise<Void> future, String message, Throwable throwable) {
    this.handleError(message, throwable);

    synchronized (HttpTransport.this) {
      if(!future.isDone()) {
        future.setFailure(new Throwable(message, throwable));
      }
    }
  }

  @Override
  public void send(String message) {
    if(!this.connected.get()) {
      this.logger.error("Could not send message on unconnected transport: " + message);
      return;
    }
    this.performXhrSend(message);
  }

  @Override
  public Future<Void> close() {
    if(!this.connected.getAndSet(false)) {
      this.logger.error("Could not close unconnected transport.");
      return group.next().<Void>newFailedFuture(new Throwable("Could not close unconnected transport."));
    }
    return this.close(true);
  }

  private Future<Void> close(boolean tellBridge) {
    this.closed.set(true);
    if(this.currentXhrFetchRequestChannel != null && this.currentXhrFetchRequestChannel.isOpen()) {
      this.currentXhrFetchRequestChannel.close();
    }
    this.closeHandler.handle(null);
    if(tellBridge) {
      // TODO: performClose results in a 404
      // return this.performClose();
    }
    return group.next().<Void>newSucceededFuture(null);
  }
}
