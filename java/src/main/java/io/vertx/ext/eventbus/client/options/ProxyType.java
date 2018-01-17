package io.vertx.ext.eventbus.client.options;

/**
 * @author <a href="mailto:pl@linux.com">Phil Lehmann</a>
 *
 * Blatantly copied from io.vertx.core.net.ProxyType @author Alexander Lehmann
 */
public enum ProxyType {
  /**
   * HTTP CONNECT ssl proxy
   */
  HTTP_CONNECT,
  /**
   * direct HTTP proxy, which does not support CONNECT
   */
  HTTP_DIRECT,
  /**
   * SOCKS4/4a tcp proxy
   */
  SOCKS4,
  /**
   * SOCSK5 tcp proxy
   */
  SOCKS5;

  @Override
  public String toString () {
    switch(this)
    {
      case HTTP_CONNECT:
        return "connect HTTP";
      case HTTP_DIRECT:
        return "direct HTTP";
      default:
        return super.toString();
    }
  }
}
