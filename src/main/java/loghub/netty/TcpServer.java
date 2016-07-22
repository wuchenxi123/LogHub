package loghub.netty;

import java.net.InetSocketAddress;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.ServerSocketChannel;
import loghub.configuration.Properties;

public class TcpServer extends AbstractNettyServer<TcpFactory, ServerBootstrap, ServerChannel, ServerSocketChannel, InetSocketAddress> {

    @Override
    protected TcpFactory getNewFactory(Properties properties) {
        return new TcpFactory(poller);
    }

}
