package io.vertx.ext.eventbus.client.options;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 *         <p>
 *         Based on io.vertx.core.http.HttpClientOptions @author Tim Fox
 */
public class WebSocketTransportOptions {

  /**
   * The default path to connect the WebSocket client to = /eventbus
   */
  public static final String DEFAULT_PATH = "/eventbus";

  /**
   * The default value for maximum websocket frame size = 65536 bytes
   */
  public static final int DEFAULT_MAX_WEBSOCKET_FRAME_SIZE = 65536;

  /**
   * Default idle timeout = 0 ms (0 = disabled)
   */
  public static final int DEFAULT_IDLE_TIMEOUT = 0;

  private String path;
  private int maxWebsocketFrameSize;
  private int idleTimeout;

  public WebSocketTransportOptions() {
    init();
  }

  private void init() {
    this.path = DEFAULT_PATH;
    this.maxWebsocketFrameSize = DEFAULT_MAX_WEBSOCKET_FRAME_SIZE;
    this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
  }

  /**
   * Set the path to connect the WebSocket client to
   *
   * @param path the path
   * @return a reference to this, so the API can be used fluently
   */
  public WebSocketTransportOptions setPath(String path) {
    this.path = path;
    return this;
  }

  /**
   * Get the path to connect the WebSocket client to
   *
   * @return the path
   */
  public String getPath() {
    return this.path;
  }

  /**
   * Get the maximum websocket framesize to use
   *
   * @return the max websocket framesize
   */
  public int getMaxWebsocketFrameSize() {
    return this.maxWebsocketFrameSize;
  }

  /**
   * Set the max websocket frame size
   *
   * @param maxWebsocketFrameSize the max frame size, in bytes
   * @return a reference to this, so the API can be used fluently
   */
  public WebSocketTransportOptions setMaxWebsocketFrameSize(int maxWebsocketFrameSize) {
    this.maxWebsocketFrameSize = maxWebsocketFrameSize;
    return this;
  }

  /**
   * Set the idle timeout, in seconds. zero means don't timeout.
   * This determines if a connection will timeout and be closed if no data is received within the timeout.
   *
   * @param idleTimeout the idle timeout, in milliseconds
   * @return a reference to this, so the API can be used fluently
   */
  public WebSocketTransportOptions setIdleTimeout(int idleTimeout) {
    if (idleTimeout < 0) {
      throw new IllegalArgumentException("idleTimeout must be >= 0");
    }
    this.idleTimeout = idleTimeout;
    return this;
  }

  /**
   * @return the idle timeout, in milliseconds (0 means no timeout)
   */
  public int getIdleTimeout() {
    return this.idleTimeout;
  }
}
