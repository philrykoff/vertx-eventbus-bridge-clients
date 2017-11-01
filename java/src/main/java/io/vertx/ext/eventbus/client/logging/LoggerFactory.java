package io.vertx.ext.eventbus.client.logging;

import io.vertx.ext.eventbus.client.logging.impl.JULLoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class LoggerFactory {

  public static final String LOGGER_DELEGATE_FACTORY_CLASS_NAME = "vertx.logger-delegate-factory-class-name";

  private static volatile LoggerFactoryImplBase delegateFactory;

  private static final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

  static {
    initialise();
  }
  private static synchronized void initialise() {
    LoggerFactoryImplBase delegateFactory;

    // If a system property is specified then this overrides any delegate factory which is set
    // programmatically - this is primarily of use so we can configure the logger delegate on the client side.
    // call to System.getProperty is wrapped in a try block as it will fail if the client runs in a secured
    // environment
    String className = JULLoggerFactory.class.getName();
    try {
      className = System.getProperty(LOGGER_DELEGATE_FACTORY_CLASS_NAME);
    } catch (Exception ignored) {
    }

    if (className != null) {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      try {
        Class<?> clz = loader.loadClass(className);
        delegateFactory = (LoggerFactoryImplBase) clz.newInstance();
      } catch (Exception e) {
        throw new IllegalArgumentException("Error instantiating transformer class \"" + className + "\"", e);
      }
    } else {
      delegateFactory = new JULLoggerFactory();
    }

    LoggerFactory.delegateFactory = delegateFactory;
  }

  public static Logger getLogger(Class<?> clazz) {
    String name = clazz.isAnonymousClass() ? clazz.getEnclosingClass().getCanonicalName() : clazz.getCanonicalName();
    return getLogger(name);
  }

  public static Logger getLogger(final String name) {
    Logger logger = loggers.get(name);

    if (logger == null) {
      logger = delegateFactory.createLogger(name);
      Logger oldLogger = loggers.putIfAbsent(name, logger);

      if (oldLogger != null) {
        logger = oldLogger;
      }
    }

    return logger;
  }

  public static void removeLogger(String name) {
    loggers.remove(name);
  }
}
