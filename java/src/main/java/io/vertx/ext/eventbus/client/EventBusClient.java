package io.vertx.ext.eventbus.client;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import io.vertx.ext.eventbus.client.json.GsonCodec;
import io.vertx.ext.eventbus.client.json.JsonCodec;
import io.vertx.ext.eventbus.client.logging.Logger;
import io.vertx.ext.eventbus.client.logging.LoggerFactory;
import io.vertx.ext.eventbus.client.options.HttpTransportOptions;
import io.vertx.ext.eventbus.client.options.TcpTransportOptions;
import io.vertx.ext.eventbus.client.options.WebSocketTransportOptions;
import io.vertx.ext.eventbus.client.transport.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class EventBusClient {

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus TCP bridge
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient tcp(EventBusClientOptions eventBusClientOptions) {
    return EventBusClient.tcp(eventBusClientOptions, new GsonCodec());
  }

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus TCP bridge
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @param jsonCodec the JSON codec to use
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient tcp(EventBusClientOptions eventBusClientOptions, JsonCodec jsonCodec) {

    if (eventBusClientOptions == null) {
      eventBusClientOptions = new EventBusClientOptions();
    }
    if(eventBusClientOptions.getTransportOptions() == null) {
      eventBusClientOptions.setTransportOptions(new TcpTransportOptions());
    }

    NioEventLoopGroup group = new NioEventLoopGroup(1);
    return new EventBusClient(group, new TcpTransport(group, eventBusClientOptions), eventBusClientOptions, jsonCodec);
  }

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus SockJS bridge using WebSockets
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient websocket(EventBusClientOptions eventBusClientOptions) {
    return EventBusClient.websocket(eventBusClientOptions, new GsonCodec());
  }

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus SockJS bridge using WebSockets
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @param jsonCodec the JSON codec to use
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient websocket(EventBusClientOptions eventBusClientOptions, JsonCodec jsonCodec) {

    if (eventBusClientOptions == null) {
      eventBusClientOptions = new EventBusClientOptions();
    }
    if(eventBusClientOptions.getTransportOptions() == null) {
      eventBusClientOptions.setTransportOptions(new WebSocketTransportOptions());
    }

    NioEventLoopGroup group = new NioEventLoopGroup(1);
    return new EventBusClient(group, new WebSocketTransport(group, eventBusClientOptions), eventBusClientOptions, jsonCodec);
  }

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus TCP bridge
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient http(EventBusClientOptions eventBusClientOptions) {
    return EventBusClient.http(eventBusClientOptions, new GsonCodec());
  }

  /**
   * Creates an {@code EventBusClient} instance to connect to a Vert.x EventBus TCP bridge
   * @param eventBusClientOptions the {@code EventBusClient} options
   * @param jsonCodec the JSON codec to use
   * @return the created {@code EventBusClient}
   */
  public static EventBusClient http(EventBusClientOptions eventBusClientOptions, JsonCodec jsonCodec) {

    if (eventBusClientOptions == null) {
      eventBusClientOptions = new EventBusClientOptions();
    }
    if(eventBusClientOptions.getTransportOptions() == null) {
      eventBusClientOptions.setTransportOptions(new HttpTransportOptions());
    }

    NioEventLoopGroup group = new NioEventLoopGroup(1);
    return new EventBusClient(group, new HttpTransport(group, eventBusClientOptions, jsonCodec), eventBusClientOptions, jsonCodec);
  }

  private DeliveryOptions defaultOptions = new DeliveryOptions();
  private final Transport transport;
  private final NioEventLoopGroup group;
  private final EventBusClientOptions eventBusClientOptions;
  private final JsonCodec codec;
  private Logger logger;

  private final ConcurrentMap<String, HandlerList> consumerMap = new ConcurrentHashMap<>();
  private ScheduledFuture<?> pingPeriodic;
  private Future<Void> connectFuture;
  private ScheduledFuture<?> reconnectFuture;
  private boolean initializedTransport;
  private boolean connected = false;
  private boolean closed = false;
  private int reconnectTries;

  private Handler<Handler<Void>> connectedHandler;
  private volatile Handler<Throwable> exceptionHandler;
  private Handler<Void> closeHandler;

  private EventBusClient(NioEventLoopGroup group, Transport transport, EventBusClientOptions eventBusClientOptions, JsonCodec jsonCodec) {
    this.group = group;
    this.transport = transport;
    this.eventBusClientOptions = eventBusClientOptions;
    this.codec = jsonCodec;
    this.logger = LoggerFactory.getLogger(EventBusClient.class);
  }

  private ArrayDeque<Handler<Transport>> pendingTasks = new ArrayDeque<>();

  private synchronized void execute(Handler<Transport> task) {
    if (connected) {
      task.handle(transport);
    } else if(closed) {
      logger.error("This EventBusClient is closed.");
    } else {
      pendingTasks.add(task);
      if (connectFuture == null && reconnectFuture == null) {
        initializeTransport();
        logger.info("Connecting for executing task...");
        connectTransport();
      }
    }
  }

  private synchronized void initializeTransport() {

    if(initializedTransport) {
      return;
    }
    initializedTransport = true;

    transport.connectedHandler(new Handler<Void>() {
      @Override
      public void handle(Void v) {
        synchronized (EventBusClient.this) {

          logger.info("Connected to bridge.");
          // TODO: send only when client was idle for pingInterval?
          pingPeriodic = group.next().scheduleAtFixedRate(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                              send(codec.encode(Collections.singletonMap("type", "ping")));
                                                            }
                                                          },
            EventBusClient.this.eventBusClientOptions.getPingInterval(),
            EventBusClient.this.eventBusClientOptions.getPingInterval(),
            TimeUnit.MILLISECONDS);
          connected = true;
          reconnectTries = 0;
          connectFuture = null;

          if(EventBusClient.this.connectedHandler != null)  {

            EventBusClient.this.connectedHandler.handle(new Handler<Void>() {
              @Override
              public void handle(Void v) {
                EventBusClient.this.handlePendingTasks();
              }
            });
          }
          else  {
            EventBusClient.this.handlePendingTasks();
          }
        }
      }
    });
    transport.messageHandler(new Handler<String>() {
      @Override
      public void handle(String json) {
        Map msg = codec.decode(json, Map.class);
        handleMsg(msg);
      }
    });
    transport.closeHandler(new Handler<Boolean>() {
      @Override
      public void handle(Boolean cancelledConnect) {
        synchronized (EventBusClient.this) {
          connected = false;
          connectFuture = null;
          if(cancelledConnect) {
            logger.info("Cancelled connecting to bridge.");
          } else {
            logger.info("Closed connection to bridge.");
            if (closeHandler != null) {
              closeHandler.handle(null);
            }
          }
          if(pingPeriodic != null) {
            pingPeriodic.cancel(false);
          }
          autoReconnect();
        }
      }
    });
  }

  private synchronized void connectTransport() {

    if(connected || closed || connectFuture != null || reconnectFuture != null) {
      return;
    }

    String host = EventBusClient.this.eventBusClientOptions.getHost();
    Integer port = EventBusClient.this.eventBusClientOptions.getPort();

    if(EventBusClient.this.eventBusClientOptions.getProxyOptions() != null) {
      logger.info("Using " + EventBusClient.this.eventBusClientOptions.getProxyOptions().toString() + ".");
    }
    logger.info("Connecting to bridge at " + host + ":" + port + " using " +
      (EventBusClient.this.eventBusClientOptions.isSsl() ? "TLS secured " : "") +
      this.transport.toString() + "...");

    connectFuture = transport.connect()
      .addListener(new GenericFutureListener<Future<? super Void>>() {
      @Override
      public void operationComplete(Future future) {

        if(!future.isSuccess()) {
          handleError("Connecting to bridge failed.", future.cause());
          connectFuture = null;
          autoReconnect();
        }
      }
    });
  }

  private synchronized void autoReconnect() {

    if(!closed &&
       reconnectFuture == null &&
       EventBusClient.this.eventBusClientOptions.isAutoReconnect() &&
      (EventBusClient.this.eventBusClientOptions.getMaxAutoReconnectTries() == 0 ||
        reconnectTries < EventBusClient.this.eventBusClientOptions.getMaxAutoReconnectTries()))
    {
      ++reconnectTries;
      int interval = EventBusClient.this.eventBusClientOptions.getAutoReconnectInterval();
      logger.info("Auto reconnecting in " + interval + "ms (try number " + reconnectTries + ")...");
      reconnectFuture = group.next().schedule(new Runnable() {
        @Override
        public void run() {
          logger.info("Auto reconnecting...", reconnectFuture);
          reconnectFuture = null;
          connectTransport();
        }
      }, interval, TimeUnit.MILLISECONDS);
    }
  }

  private void handlePendingTasks() {

    // First register, then send pending tasks, as those tasks may result in messages being sent to registered channels
    if(this.eventBusClientOptions.getReregisterChannelsUponConnect()) {
      for(String address : consumerMap.keySet()) {
        if(consumerMap.get(address).reregisterAtServer) {
          logger.info("Registering address: " + address);
          send("register", address, null, this.defaultOptions == null ? null : this.defaultOptions.getHeaders(), null);
        }
      }
    }

    Handler<Transport> t;
    while ((t = this.pendingTasks.poll()) != null) {
      t.handle(this.transport);
    }
  }

  private void handleMsg(Map msg) {
    String type = (String) msg.get("type");
    if (type != null) {
      switch (type) {
        case "message":
        case "rec": {
          String address = (String) msg.get("address");
          if (address == null) {
            // TCP bridge that replies an error...
            return;
          }
          logger.info("Received message for address: " + address);
          HandlerList consumers = consumerMap.get(address);
          if (consumers != null) {
            Map body = (Map) msg.get("body");
            Map<String, String> headers;
            Map msgHeaders = (Map) msg.get("headers");
            if (msgHeaders == null) {
              headers = Collections.emptyMap();
            } else {
              headers = (Map<String, String>) msgHeaders;
            }
            String replyAddress = (String) msg.get("replyAddress");
            consumers.send(new Message(this, address, headers, body, replyAddress));
          }
          break;
        }
        case "err": {
          String address = (String) msg.get("address");
          String message = (String) msg.get("message");
//          int failureCode = msg.get("failureCode").getAsInt();
//          String failureType = msg.get("failureType").getAsString();
          if(address == null) {
            logger.info("Received error without address present, probably the address was not found: " + message);
            return;
          }
          HandlerList consumers = consumerMap.get(address);
          if (consumers != null) {
            consumers.fail(new RuntimeException(message));
          }
        }
      }
    }
  }

  /**
   * Sets the default delivery options (message send timeout and headers) to be used for subsequent messages.
   * @param defaultOptions the new default delivery options
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient setDefaultDeliveryOptions(DeliveryOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
    return this;
  }

  /**
   * Connects to the bridge server
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient connect() {
    closed = false;
    initializeTransport();
    logger.info("Connecting as requested...");
    connectTransport();
    return this;
  }

  /**
   * Returns whether the client is currently connected to the bridge server.
   * @return whether the client is currently connected to the bridge server
   */
  public boolean isConnected() {
    return connected;
  }

  /**
   * Closes the connection to the bridge server, if it is open.
   *
   * Until {@code connect} is invoked, the client will not connect to the server
   * (neither through auto reconnect, nor by sending a message).
   */
  public void close() {
    closed = true;
    transport.close();
  }

  /**
   * Sends a message.
   * <p>
   * The message will be delivered to at most one of the handlers registered to the address.
   *
   * @param address  the address to send it to
   * @param message  the message, may be {@code null}
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient send(String address, Object message) {
    send(address, message, defaultOptions, null);
    return this;
  }

  /**
   * Sends a message.
   * <p>
   * The message will be delivered to at most one of the handlers registered to the address.
   *
   * @param address  the address to send it to
   * @param message  the message, may be {@code null}
   * @return a reference to this, so the API can be used fluently
   */
  public <T> EventBusClient send(String address, Object message, final Handler<AsyncResult<Message<T>>> replyHandler) {
    return send(address, message, defaultOptions, replyHandler);
  }

  /**
   * Like {@link #send(String, Object)} but specifying {@code options} that can be used to configure the delivery.
   *
   * @param address  the address to send it to
   * @param message  the message, may be {@code null}
   * @param options  delivery options
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient send(String address, Object message, DeliveryOptions options) {
    return send(address, message, options, null);
  }

  /**
   * Like {@link #send(String, Object, DeliveryOptions)} but specifying a {@code replyHandler} that will be called if the recipient
   * subsequently replies to the message.
   *
   * @param address  the address to send it to
   * @param message  the message, may be {@code null}
   * @param options  delivery options
   * @param replyHandler  reply handler will be called when any reply from the recipient is received, may be {@code null}
   * @return a reference to this, so the API can be used fluently
   */
  public <T> EventBusClient send(String address, Object message, DeliveryOptions options, final Handler<AsyncResult<Message<T>>> replyHandler) {
    final String replyAddr;
    if (replyHandler != null) {
      final AtomicBoolean registered = new AtomicBoolean(true);
      replyAddr = UUID.randomUUID().toString();

      final MessageHandler<T> messageHandler = new MessageHandler<T>() {
        @Override
        public String address() { return replyAddr; };
        @Override
        public void handleMessage(Message<T> msg) {
          if (registered.compareAndSet(true, false)) {
            this.cancelTimeout();
            unregister(this, false);
            replyHandler.handle(AsyncResult.<Message<T>>success(msg));
          }
        }
        @Override
        public void handleError(Throwable err) {
          if (registered.compareAndSet(true, false)) {
            this.cancelTimeout();
            unregister(this, false);
            replyHandler.handle(AsyncResult.<Message<T>>failure(err));
          }
        }
      };

      messageHandler.setTimeout(group.next().schedule(new Runnable() {
        @Override
        public void run() {
          messageHandler.handleError(new TimeoutException());
        }
      }, options.getSendTimeout(), TimeUnit.MILLISECONDS));

      register(messageHandler, null, false);
    } else {
      replyAddr = null;
    }
    send("send", address, message, options.getHeaders(), replyAddr);
    return this;
  }

  /**
   * Publish a message.<p>
   * The message will be delivered to all handlers registered to the address.
   *
   * @param address  the address to publish it to
   * @param message  the message, may be {@code null}
   * @return a reference to this, so the API can be used fluently
   *
   */
  public EventBusClient publish(String address, Object message) {
    send("publish", address, message, defaultOptions.getHeaders(), null);
    return this;
  }

  /**
   * Like {@link #publish(String, Object)} but specifying {@code options} that can be used to configure the delivery.
   *
   * @param address  the address to publish it to
   * @param message  the message, may be {@code null}
   * @param options  delivery options
   * @return a reference to this, so the API can be used fluently
   *
   */
  public EventBusClient publish(String address, Object message, DeliveryOptions options) {
    send("publish", address, message, options == null ? null : options.getHeaders(), null);
    return this;
  }

  /**
   * Create a consumer and register it against the specified address.
   *
   * @param address  the address that will register it at
   * @param handler  the handler that will process the received messages
   * @return the event bus message consumer
   */
  public <T> MessageConsumer<T> consumer(String address, Handler<Message<T>> handler) {
    return consumer(address, defaultOptions, handler);
  }

  /**
   * Like {@link #consumer(String, Handler)} but specifying {@code options} that can be used to configure the delivery.
   *
   * @param address  the address that will register it at
   * @param options  delivery options
   * @param handler  the handler that will process the received messages
   * @return the event bus message consumer
   */
  public <T> MessageConsumer<T> consumer(String address, DeliveryOptions options, Handler<Message<T>> handler) {
    MessageConsumer<T> consumer = new MessageConsumer<>(this, address, handler);
    register(consumer.handler(), options == null ? null : options.getHeaders(), true);
    return consumer;
  }

  private void register(MessageHandler<?> handler, Map<String, String> headers, boolean atServer) {
    String address = handler.address();
    synchronized (consumerMap) {
      HandlerList consumers = consumerMap.get(address);
      List<MessageHandler> handlers;
      if (consumers == null) {
        handlers = Collections.singletonList((MessageHandler) handler);
      } else {
        ArrayList<MessageHandler> tmp = new ArrayList<>(consumers.handlers);
        tmp.add(handler);
        handlers = new ArrayList<>(tmp);
      }
      // If we would just create a task for it, that would be send upon connection creation redundandly to all other re-registered handlers
      if(atServer) {
        if(connected) {
          logger.info("Registering address: " + address);
          send("register", address, null, headers, null);
        } else {
          initializeTransport();
          connectTransport();
        }
      }
      consumerMap.put(address, new HandlerList(handlers, atServer));
    }
  }

  void unregister(MessageHandler<?> handler, boolean atServer) {
    String address = handler.address();
    synchronized (consumerMap) {
      HandlerList consumers = consumerMap.get(address);
      if (consumers == null) {
        return;
      }
      if (!consumers.handlers.contains(handler)) {
        return;
      }
      List<MessageHandler> handlers = new ArrayList<>(consumers.handlers);
      handlers.remove(handler);
      if (atServer && handlers.isEmpty()) {
        consumerMap.remove(address);
        Map<String, Object> obj = new HashMap<>();
        obj.put("type", "unregister");
        obj.put("address", address);
        final String msg = codec.encode(obj);
        send(msg);
      } else {
        consumerMap.put(address, new HandlerList(new ArrayList<>(handlers), atServer));
      }
    }
  }

  /**
   * Set a connected handler, which is called everytime the connection is (re)established.
   *
   * The close handler is being handed over a Future, which it must complete in order for any queued messages
   * to be flushed. This allows the client to perform any initializing operations such as authorization before
   * sending other messages to the server.
   *
   * @param connectedHandler the connected handler
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient connectedHandler(Handler<Handler<Void>> connectedHandler) {
    this.connectedHandler = connectedHandler;
    return this;
  }

  /**
   * Set a default exception handler.
   *
   * @param exceptionHandler the exception handler
   * @return a reference to this, so the API can be used fluently
   */
  public EventBusClient exceptionHandler(Handler<Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
    this.transport.exceptionHandler(exceptionHandler);
    return this;
  }

  /**
   * Set a default close handler.
   *
   * @param closeHandler the close handler
   * @return a reference to this, so the API can be used fluently
   */
  public synchronized EventBusClient closeHandler(Handler<Void> closeHandler) {
    this.closeHandler = closeHandler;
    return this;
  }

  private void handleError(String message, Throwable t) {
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

  private void send(String type, String address, Object body, Map<String, String> headers, String replyAddress) {
    Map<String, Object> obj = new HashMap<>();
    obj.put("type", type);
    obj.put("address", address);
    if (replyAddress != null) {
      obj.put("replyAddress", replyAddress);
    }
    if (headers != null) {
      obj.put("headers", headers);
    }
    if (body != null) {
      obj.put("body", body);
    }
    final String msg = codec.encode(obj);
    send(msg);
  }

  private void send(final String message) {
    execute(new Handler<Transport>() {
      @Override
      public void handle(Transport event) {
        if(message.length() > eventBusClientOptions.getMessagePrintLimit()) {
          logger.trace("Sending message with " + message.length() + " chars.");
        } else {
          logger.trace("Sending message: " + message);
        }
        transport.send(message);
      }
    });
  }

  private class HandlerList {

    private final List<MessageHandler> handlers;
    private final boolean reregisterAtServer;

    HandlerList(List<MessageHandler> handlers, boolean reregisterAtServer) {
      this.handlers = handlers;
      this.reregisterAtServer = reregisterAtServer;
    }

    void send(Message message) {
      for (MessageHandler handler : handlers) {
        try {
          handler.handleMessage(message);
        } catch (Throwable t) {
          handleError("Exception in message handler.", t);
        }
      }
    }

    void fail(Throwable cause) {
      for (MessageHandler handler : handlers) {
        try {
          handler.handleError(cause);
        } catch (Throwable t) {
          handleError("Exception in message error handler.", t);
        }
      }
    }
  }
}
