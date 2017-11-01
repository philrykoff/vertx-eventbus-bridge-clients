package io.vertx.ext.eventbus.client.options;

/**
 * Stub
 */
public class TcpTransportOptions implements TransportOptions {

  /**
   * Default idle timeout = 0 ms (0 = disabled)
   */
  public static final int DEFAULT_IDLE_TIMEOUT = 0;

  private int idleTimeout;

  public TcpTransportOptions() {
    init();
  }

  private void init() {
    this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
  }

  /**
   * Set the idle timeout, in seconds. zero means don't timeout.
   * This determines if a connection will timeout and be closed if no data is received within the timeout.
   *
   * @param idleTimeout the idle timeout, in milliseconds
   * @return a reference to this, so the API can be used fluently
   */
  public TcpTransportOptions setIdleTimeout(int idleTimeout) {
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
