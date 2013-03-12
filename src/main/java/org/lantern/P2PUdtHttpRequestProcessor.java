package org.lantern;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.udt.UdtChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.lantern.util.Threads;
import org.littleshoot.util.FiveTuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2PUdtHttpRequestProcessor implements HttpRequestProcessor {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private io.netty.channel.ChannelFuture cf;
    
    private final ClientSocketChannelFactory clientSocketChannelFactory;

    /**
     * These need to be synchronized with HTTP responses in the case where we
     * need to issue multiple HTTP range requests in response to 206 responses.
     * This is particularly relevant for LAE because of response size limits.
     */
    private final Queue<HttpRequest> httpRequests = 
        new ConcurrentLinkedQueue<HttpRequest>();

    private final ProxyTracker proxyTracker;

    private FiveTuple proxyAddress;

    private final ChannelGroup channelGroup;

    private final Stats stats;

    private final LanternTrustStore trustStore;

    private GlobalTrafficShapingHandler trafficHandler;

    private ProxyHolder proxyHolder;

    public P2PUdtHttpRequestProcessor( 
        final ProxyTracker proxyTracker, 
        final HttpRequestTransformer transformer, 
        final ClientSocketChannelFactory clientSocketChannelFactory,
        final ChannelGroup channelGroup, final Stats stats,
        final LanternTrustStore trustStore) {
        this.proxyTracker = proxyTracker;
        this.clientSocketChannelFactory = clientSocketChannelFactory;
        this.channelGroup = channelGroup;
        this.stats = stats;
        this.trustStore = trustStore;
    }
    
    private boolean hasProxy() {
        if (this.proxyAddress != null) {
            return true;
        }
        final ProxyHolder ph = this.proxyTracker.getJidProxy();
        
        if (ph != null) {
            this.proxyHolder = ph;
            this.proxyAddress = ph.getFiveTuple();
            //this.trafficHandler = ph.getTrafficShapingHandler();
            return true;
        }
        log.info("No proxy!");
        return false;
    }

    @Override
    public boolean processRequest(final Channel browserToProxyChannel,
        final ChannelHandlerContext ctx, final HttpRequest request) {
        if (!hasProxy()) {
            return false;
        }
        final HttpMethod method = request.getMethod();
        final boolean connect = method == HttpMethod.CONNECT;
        
        if (cf == null) {
            if (connect) {
                cf = openOutgoingConnectChannel(browserToProxyChannel, request);
            } else {
                cf = openOutgoingChannel(browserToProxyChannel, request);
            }
        }
        if (!connect) {
            try {
                LanternUtils.writeRequest(this.httpRequests, request, cf);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public boolean processChunk(final ChannelHandlerContext ctx, 
        final HttpChunk chunk) throws IOException {
        try {
            cf.channel().write(LanternUtils.encoder.encode(chunk));
            return true;
        } catch (final Exception e) {
            throw new IOException("Could not write chunk?", e);
        }
    }

    @Override
    public void close() {
        if (cf == null) {
            return;
        }
        cf.channel().flush().addListener(new io.netty.channel.ChannelFutureListener() {
            
            @Override
            public void operationComplete(io.netty.channel.ChannelFuture future)
                    throws Exception {
                cf.channel().close();
            }
        });
    }

    private io.netty.channel.ChannelFuture openOutgoingChannel(
        final Channel browserToProxyChannel, final HttpRequest request) {
        browserToProxyChannel.setReadable(false);

        final Bootstrap boot = new Bootstrap();
        final ThreadFactory connectFactory = Threads.newThreadFactory("connect");
        final NioEventLoopGroup connectGroup = new NioEventLoopGroup(1,
                connectFactory, NioUdtProvider.BYTE_PROVIDER);

        try {
            boot.group(connectGroup)
                .channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                .handler(new ChannelInitializer<UdtChannel>() {
                    @Override
                    public void initChannel(final UdtChannel ch) 
                        throws Exception {
                        final io.netty.channel.ChannelPipeline p = ch.pipeline();
                        p.addLast(
                            //new LoggingHandler(LogLevel.INFO),
                            new HttpResponseClientHandler(
                                browserToProxyChannel, request));
                    }
                });
            // Start the client.
            
            // We need to bind to the local address here, as that's what is
            // NAT/firewall traversed (anything else might not work).
            try {
                boot.bind(proxyAddress.getLocal()).sync();
            } catch (final InterruptedException e) {
                log.error("Could not sync on bind? Reuse address no working?", e);
            }
            return boot.connect(proxyAddress.getRemote());
        } finally {
            // Shut down the event loop to terminate all threads.
            boot.shutdown();
        }
    }
    
    private io.netty.channel.ChannelFuture openOutgoingConnectChannel(
        final Channel browserToProxyChannel, final HttpRequest request) {
        browserToProxyChannel.setReadable(false);


        final Bootstrap boot = new Bootstrap();
        final ThreadFactory connectFactory = Threads.newThreadFactory("connect");
        final NioEventLoopGroup connectGroup = new NioEventLoopGroup(1,
                connectFactory, NioUdtProvider.BYTE_PROVIDER);

        try {
            boot.group(connectGroup)
                .channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                .handler(new ChannelInitializer<UdtChannel>() {
                    @Override
                    public void initChannel(final UdtChannel ch) 
                        throws Exception {
                        final io.netty.channel.ChannelPipeline p = ch.pipeline();
                        p.addLast(
                            //new LoggingHandler(LogLevel.INFO),
                            new HttpResponseClientHandler(
                                browserToProxyChannel, request));
                    }
                });
            // We need to bind to the local address here, as that's what is
            // NAT/firewall traversed (anything else might not work).
            try {
                boot.bind(proxyAddress.getLocal()).sync();
            } catch (final InterruptedException e) {
                log.error("Could not sync on bind? Reuse address no working?", e);
            }
            return boot.connect(proxyAddress.getRemote());
        } finally {
            // Shut down the event loop to terminate all threads.
            boot.shutdown();
        }
    }
    
    private void remove(final ChannelPipeline cp, final String name) {
        final ChannelHandler ch = cp.get(name);
        if (ch != null) {
            cp.remove(name);
        }
    }

    private static class HttpResponseClientHandler extends ChannelInboundByteHandlerAdapter {

        private static final Logger log = 
                LoggerFactory.getLogger(HttpResponseClientHandler.class);


        private final Channel browserToProxyChannel;

        private HttpResponseClientHandler(
            final Channel browserToProxyChannel, final HttpRequest request) {
            this.browserToProxyChannel = browserToProxyChannel;
        }

        @Override
        public void channelActive(final io.netty.channel.ChannelHandlerContext ctx) throws Exception {
            log.debug("Channel active " + NioUdtProvider.socketUDT(ctx.channel()).toStringOptions());
        }

        @Override
        public void inboundBufferUpdated(final io.netty.channel.ChannelHandlerContext ctx,
                final ByteBuf in) {
            
            // TODO: We should be able to do this more efficiently than
            // converting to a string and back out.
            final String response = in.toString(LanternConstants.UTF8);
            log.debug("INBOUND UPDATED!!\n"+response);
            
            synchronized (browserToProxyChannel) {
                final ChannelBuffer wrapped = 
                    ChannelBuffers.wrappedBuffer(response.getBytes());
                this.browserToProxyChannel.write(wrapped);
                this.browserToProxyChannel.notifyAll();
            }
        }

        @Override
        public void exceptionCaught(final io.netty.channel.ChannelHandlerContext ctx,
                final Throwable cause) {
            log.debug("close the connection when an exception is raised", cause);
            ctx.close();
        }

        @Override
        public ByteBuf newInboundBuffer(final io.netty.channel.ChannelHandlerContext ctx)
                throws Exception {
            log.debug("NEW INBOUND BUFFER");
            return ctx.alloc().directBuffer(
                    ctx.channel().config().getOption(ChannelOption.SO_RCVBUF));
        }

    }
}
