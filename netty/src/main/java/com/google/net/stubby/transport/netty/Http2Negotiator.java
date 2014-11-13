package com.google.net.stubby.transport.netty;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2OrHttpChooser;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLEngine;

/**
 * A utility class that provides support methods for negotiating the use of HTTP/2 with the remote
 * endpoint.
 */
public class Http2Negotiator {
  private static final List<String> SUPPORTED_PROTOCOLS = Collections.unmodifiableList(
      Arrays.asList(
          Http2OrHttpChooser.SelectedProtocol.HTTP_2.protocolName(),
          "h2-15"));

  // Prefer ALPN to NPN so try it first.
  private static final String[] JETTY_TLS_NEGOTIATION_IMPL =
      {"org.eclipse.jetty.alpn.ALPN", "org.eclipse.jetty.npn.NextProtoNego"};

  private static final Logger log = Logger.getLogger(Http2Negotiator.class.getName());

  /**
   * A Netty-based negotiation that provides an pre-configured {@link ChannelInitializer} for to
   * negotiate the requested protocol.
   */
  public interface Negotiation {
    /**
     * Gets the {@link ChannelInitializer} for negotiating the protocol.
     */
    ChannelInitializer<SocketChannel> initializer();

    void onConnected(Channel channel);

    /**
     * Completion future for this negotiation.
     */
    ListenableFuture<Void> completeFuture();
  }

  /**
   * Create a TLS handler for HTTP/2 capable of using ALPN/NPN.
   */
  public static ChannelHandler serverTls(SSLEngine sslEngine) {
    Preconditions.checkNotNull(sslEngine, "sslEngine");
    if (!installJettyTLSProtocolSelection(sslEngine, SettableFuture.<Void>create(), true)) {
      throw new IllegalStateException("NPN/ALPN extensions not installed");
    }
    return new SslHandler(sslEngine, false);
  }

  /**
   * Creates an TLS negotiation for HTTP/2 using ALPN/NPN.
   */
  public static Negotiation tls(final ChannelHandler handler, final SSLEngine sslEngine) {
    Preconditions.checkNotNull(handler, "handler");
    Preconditions.checkNotNull(sslEngine, "sslEngine");

    final SettableFuture<Void> completeFuture = SettableFuture.create();
    if (!installJettyTLSProtocolSelection(sslEngine, completeFuture, false)) {
      throw new IllegalStateException("NPN/ALPN extensions not installed");
    }
    final ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(final SocketChannel ch) throws Exception {
        SslHandler sslHandler = new SslHandler(sslEngine, false);
        sslHandler.handshakeFuture().addListener(
            new GenericFutureListener<Future<? super Channel>>() {
              @Override
              public void operationComplete(Future<? super Channel> future) throws Exception {
                // If an error occurred during the handshake, throw it
                // to the pipeline.
                java.util.concurrent.Future<?> doneFuture =
                    future.isSuccess() ? completeFuture : future;
                doneFuture.get();
              }
            });
        ch.pipeline().addLast(sslHandler);
        ch.pipeline().addLast(handler);
      }
    };

    return new Negotiation() {
      @Override
      public ChannelInitializer<SocketChannel> initializer() {
        return initializer;
      }

      @Override
      public void onConnected(Channel channel) {
        // Nothing to do.
      }

      @Override
      public ListenableFuture<Void> completeFuture() {
        return completeFuture;
      }
    };
  }

  /**
   * Create a plaintext upgrade negotiation for HTTP/1.1 to HTTP/2.
   */
  public static Negotiation plaintextUpgrade(final Http2ConnectionHandler handler) {
    // Register the plaintext upgrader
    Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(handler);
    HttpClientCodec httpClientCodec = new HttpClientCodec();
    final HttpClientUpgradeHandler upgrader =
        new HttpClientUpgradeHandler(httpClientCodec, upgradeCodec, 1000);
    final UpgradeCompletionHandler completionHandler = new UpgradeCompletionHandler();
    final ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(upgrader);
        ch.pipeline().addLast(completionHandler);
      }
    };

    return new Negotiation() {
      @Override
      public ChannelInitializer<SocketChannel> initializer() {
        return initializer;
      }

      @Override
      public ListenableFuture<Void> completeFuture() {
        return completionHandler.getUpgradeFuture();
      }

      @Override
      public void onConnected(Channel channel) {
        // Trigger the HTTP/1.1 plaintext upgrade protocol by issuing an HTTP request
        // which causes the upgrade headers to be added
        DefaultHttpRequest upgradeTrigger =
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        channel.writeAndFlush(upgradeTrigger);
      }
    };
  }

  /**
   * Create a "no-op" negotiation that simply assumes the protocol to already be negotiated.
   */
  public static Negotiation plaintext(final ChannelHandler handler) {
    final ChannelInitializer<SocketChannel> initializer = new ChannelInitializer<SocketChannel>() {
      @Override
      public void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(handler);
      }
    };
    return new Negotiation() {
      private final SettableFuture<Void> completeFuture = SettableFuture.create();
      @Override
      public ChannelInitializer<SocketChannel> initializer() {
        return initializer;
      }

      @Override
      public void onConnected(Channel channel) {
        completeFuture.set(null);
      }

      @Override
      public ListenableFuture<Void> completeFuture() {
        return completeFuture;
      }
    };
  }

  /**
   * Report protocol upgrade completion using a promise.
   */
  private static class UpgradeCompletionHandler extends ChannelHandlerAdapter {
    private final SettableFuture<Void> upgradeFuture = SettableFuture.create();

    public ListenableFuture<Void> getUpgradeFuture() {
      return upgradeFuture;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (!upgradeFuture.isDone()) {
        if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED) {
          upgradeFuture.setException(new RuntimeException("HTTP/2 upgrade rejected"));
        } else if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
          upgradeFuture.set(null);
          ctx.pipeline().remove(this);
        }
      }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      if (!upgradeFuture.isDone()) {
        upgradeFuture.setException(new RuntimeException("Channel closed before upgrade complete"));
      }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
      super.channelUnregistered(ctx);
      if (!upgradeFuture.isDone()) {
        upgradeFuture.setException(
            new RuntimeException("Handler unregistered before upgrade complete"));
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      super.exceptionCaught(ctx, cause);
      if (!upgradeFuture.isDone()) {
        upgradeFuture.setException(cause);
      }
    }
  }

  /**
   * Find Jetty's TLS NPN/ALPN extensions and attempt to use them
   *
   * @return true if NPN/ALPN support is available.
   */
  private static boolean installJettyTLSProtocolSelection(final SSLEngine engine,
      final SettableFuture<Void> protocolNegotiated, boolean server) {
    for (String protocolNegoClassName : JETTY_TLS_NEGOTIATION_IMPL) {
      try {
        Class<?> negoClass;
        try {
          negoClass = Class.forName(protocolNegoClassName);
        } catch (ClassNotFoundException ignored) {
          // Not on the classpath.
          log.warning("Jetty extension " + protocolNegoClassName + " not found");
          continue;
        }
        Class<?> providerClass = Class.forName(protocolNegoClassName + "$Provider");
        Class<?> clientProviderClass = Class.forName(protocolNegoClassName + "$ClientProvider");
        Class<?> serverProviderClass = Class.forName(protocolNegoClassName + "$ServerProvider");
        Method putMethod = negoClass.getMethod("put", SSLEngine.class, providerClass);
        final Method removeMethod = negoClass.getMethod("remove", SSLEngine.class);
        putMethod.invoke(null, engine, Proxy.newProxyInstance(
            Http2Negotiator.class.getClassLoader(),
            new Class[] {server ? serverProviderClass : clientProviderClass},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String methodName = method.getName();
                if ("supports".equals(methodName)) {
                  // NPN client
                  return true;
                }
                if ("unsupported".equals(methodName)) {
                  // all
                  removeMethod.invoke(null, engine);
                  protocolNegotiated.setException(new RuntimeException(
                      "Endpoint does not support any of " + SUPPORTED_PROTOCOLS
                      + " in ALPN/NPN negotiation"));
                  return null;
                }
                if ("protocols".equals(methodName)) {
                  // ALPN client, NPN server
                  return SUPPORTED_PROTOCOLS;
                }
                if ("selected".equals(methodName) || "protocolSelected".equals(methodName)) {
                  // ALPN client, NPN server
                  removeMethod.invoke(null, engine);
                  String protocol = (String) args[0];
                  if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
                    RuntimeException e = new RuntimeException(
                        "Unsupported protocol selected via ALPN/NPN: " + protocol);
                    protocolNegotiated.setException(e);
                    if ("selected".equals(methodName)) {
                      // ALPN client
                      // Throwing exception causes TLS alert.
                      throw e;
                    } else {
                      return null;
                    }
                  }
                  protocolNegotiated.set(null);
                  return null;
                }
                if ("select".equals(methodName) || "selectProtocol".equals(methodName)) {
                  // ALPN server, NPN client
                  removeMethod.invoke(null, engine);
                  @SuppressWarnings("unchecked")
                  List<String> names = (List<String>) args[0];
                  for (String name : names) {
                    if (SUPPORTED_PROTOCOLS.contains(name)) {
                      protocolNegotiated.set(null);
                      return name;
                    }
                  }
                  RuntimeException e =
                      new RuntimeException("Protocol not available via ALPN/NPN: " + names);
                  protocolNegotiated.setException(e);
                  if ("select".equals(methodName)) {
                    // ALPN server
                    throw e; // Throwing exception causes TLS alert.
                  }
                  return null;
                }
                throw new IllegalStateException("Unknown method " + methodName);
              }
            }));
        return true;
      } catch (Exception e) {
        log.log(Level.SEVERE,
            "Unable to initialize protocol negotation for " + protocolNegoClassName, e);
      }
    }
    return false;
  }
}