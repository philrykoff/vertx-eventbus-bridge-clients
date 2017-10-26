package io.vertx.ext.eventbus.client.options;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 */
public class HttpTransportOptions {

  /**
   * The default path to connect the HTTP client to = /eventbus
   */
  public static final String DEFAULT_PATH = "/eventbus";

  /**
   * The default value of whether JSESSIONID cookie should be set by the server = false
   *
   * This is useful for sticky session load balancing.
   */
  public static final boolean DEFAULT_ENABLE_JSESSIONID_COOKIE = false;

  /**
   * The default value of whether to use streaming instead of polling for messages from server = true
   */
  public static final boolean DEFAULT_STREAMING = true;

  private String path;
  private boolean enableJSessionIdCookie;
  private boolean streaming;
  private boolean maxBodySize;

  public HttpTransportOptions() {
    init();
  }

  private void init() {
    this.path = DEFAULT_PATH;
    this.enableJSessionIdCookie = DEFAULT_ENABLE_JSESSIONID_COOKIE;
    this.streaming = DEFAULT_STREAMING;
  }

  /**
   * Set the path to connect the WebSocket client to
   *
   * @param path the path
   * @return a reference to this, so the API can be used fluently
   */
  public HttpTransportOptions setPath(String path) {
    if (path == null) {
      throw new IllegalArgumentException("path must not be null");
    }
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
   * Set whether JSESSIONID cookie should be set by the server
   *
   * @param enableJSessionIdCookie whether JSESSIONID cookie should be set by the server
   * @return a reference to this, so the API can be used fluently
   */
  public HttpTransportOptions setEnableJSessionIdCookie(boolean enableJSessionIdCookie) {
    this.enableJSessionIdCookie = enableJSessionIdCookie;
    return this;
  }

  /**
   * Get whether JSESSIONID cookie should be set by the server
   *
   * @return whether JSESSIONID cookie should be set by the server
   */
  public boolean getEnableJSessionIdCookie() {
    return this.enableJSessionIdCookie;
  }

  /**
   * Set whether to use streaming instead of polling for server messages
   *
   * @param streaming whether to use streaming instead of polling for server messages
   * @return a reference to this, so the API can be used fluently
   */
  public HttpTransportOptions setStreaming(boolean streaming) {
    this.streaming = streaming;
    return this;
  }

  /**
   * Get whether to use streaming instead of polling for server messages
   *
   * @return whether to use streaming instead of polling for server messages
   */
  public boolean isStreaming() {
    return this.streaming;
  }
}
