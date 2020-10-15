package loghub.receivers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.logstash.beats.Message;
import org.logstash.beats.Protocol;

import com.fasterxml.jackson.core.JsonProcessingException;

import loghub.Event;
import loghub.LogUtils;
import loghub.Pipeline;
import loghub.Tools;
import loghub.configuration.Properties;
import loghub.decoders.StringCodec;
import loghub.security.ssl.ClientAuthentication;
import loghub.security.ssl.ContextLoader;

public class TestBeats {

    private static Logger logger;

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub.receivers.Beats", "org.logstash.beats");
    }

    private Beats receiver;
    private int port;
    private BlockingQueue<Event> queue;
    
    @Test(timeout=5000)
    public void testSimple() throws IOException, InterruptedException {
        try {
            makeReceiver( i -> { /* */ }, Collections.emptyMap());
            List<Map<?, ?>> batch = Collections.singletonList(Collections.singletonMap("message", "LogHub"));
            sendFrame(encode(batch), new Socket());
            Event e = queue.poll(1, TimeUnit.SECONDS);
            Assert.assertNotNull(e);
            String message = (String) e.get("message");
            Assert.assertEquals("LogHub", message);
            Assert.assertTrue(Tools.isRecent.apply(e.getTimestamp()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (receiver != null) {
                receiver.stopReceiving();
            }
            throw e;
        }
    }

    @Test(timeout=5000)
    public void testGarbage() throws IOException, InterruptedException {
        try {
            makeReceiver( i -> { /* */ }, Collections.emptyMap());
            sendFrame(ByteBuffer.allocate(100), new Socket());
            Event e = queue.poll(1, TimeUnit.SECONDS);
            Assert.assertNull(e);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (receiver != null) {
                receiver.stopReceiving();
            }
            throw e;
        }
    }

    @Test(timeout=5000)
    public void testOversizedBatch() throws IOException, InterruptedException {
        try {
            makeReceiver( i -> {/* */ }, Collections.emptyMap());
            ByteBuffer out = ByteBuffer.allocate(4096);
            out.put(Protocol.VERSION_2);
            out.put((byte)'W');
            out.putInt(2);
            // First message
            out.put(Protocol.VERSION_2);
            out.put((byte)'J');
            out.putInt(1);
            out.putInt(Integer.MAX_VALUE);
            out.putInt(0);
            out.flip();
            sendFrame(out, new Socket());
            List<Map<?, ?>> batch = Collections.singletonList(Collections.singletonMap("message", "LogHub"));
            sendFrame(encode(batch), new Socket());
            Event e = queue.poll(1, TimeUnit.SECONDS);
            Assert.assertNotNull(e);
            String message = (String) e.get("message");
            Assert.assertEquals("LogHub", message);
            Assert.assertTrue(Tools.isRecent.apply(e.getTimestamp()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (receiver != null) {
                receiver.stopReceiving();
            }
            throw e;
        }
    }

    @Test(timeout=5000)
    public void testSSL() throws IOException, InterruptedException {
        try {
            makeReceiver( i -> {
                i.setWithSSL(true);
                // It should be required for a better test, needs to understand how to make client side TLS works
                i.setSSLClientAuthentication(ClientAuthentication.WANTED.name());
            },
                Collections.singletonMap("ssl.trusts", new String[] {getClass().getResource("/loghub.p12").getFile()})
            );
            Map<String, Object> properties = new HashMap<>();
            properties.put("trusts", new String[] {getClass().getResource("/loghub.p12").getFile()});
            SSLContext cssctx = ContextLoader.build(null, properties);
            List<Map<?, ?>> batch = Collections.singletonList(Collections.singletonMap("message", "LogHub"));
            Socket s = cssctx.getSocketFactory().createSocket();
            sendFrame(encode(batch), s);
            Event e = queue.poll(6, TimeUnit.SECONDS);
            String message = (String) e.get("message");
            Assert.assertEquals("LogHub", message);
            Assert.assertTrue(Tools.isRecent.apply(e.getTimestamp()));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (receiver != null) {
                receiver.stopReceiving();
            }
            throw e;
        }
    }

    @Test
    public void testAlreadyBinded() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Beats r = getReceiver(InetAddress.getLoopbackAddress().getHostAddress(), ss.getLocalPort())) {
            BlockingQueue<Event> receiver = new ArrayBlockingQueue<>(10);
            r.setOutQueue(receiver);
            r.setPipeline(new Pipeline(Collections.emptyList(), "testone", null));
            Assert.assertFalse(r.configure(new Properties(Collections.emptyMap())));
        }
    }

    private void sendFrame(ByteBuffer buf, Socket s) throws IOException {
        try(Socket socket = s) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            OutputStream os = socket.getOutputStream();
            os.write(buf.array(), buf.arrayOffset(), buf.remaining());
            os.flush();
        }

    }

    private void makeReceiver(Consumer<Beats.Builder> prepare, Map<String, Object> propsMap) {
        port = Tools.tryGetPort();
        queue = new ArrayBlockingQueue<>(1);
        Beats.Builder builder = Beats.getBuilder();
        builder.setPort(port);
        builder.setDecoder(StringCodec.getBuilder().build());
        prepare.accept(builder);
        
        receiver = new Beats(builder);
        receiver.setOutQueue(queue);
        receiver.setPipeline(new Pipeline(Collections.emptyList(), "testtcplinesstream", null));
        Assert.assertTrue(receiver.configure(new Properties(propsMap)));
        receiver.start();
    }

   private Beats getReceiver(String host, int port) {
        Beats.Builder builder = Beats.getBuilder();
        builder.setHost(host);
        builder.setPort(port);
        return builder.build();
    }
   
   private ByteBuffer encode(List<Map<?, ?>> batch) throws JsonProcessingException {
       ByteBuffer out = ByteBuffer.allocate(4096);
       out.put(Protocol.VERSION_2);
       out.put((byte)'W');
       out.putInt(batch.size());

       // Aggregates the payload that we could decide to compress or not.
       for (int i = 0; i < batch.size() ; i ++) {
           encodeMessageWithJson(out, i, batch.get(i));
       }
       out.flip();
       return out;

   }
   
   private void encodeMessageWithJson(ByteBuffer payload, int sequence, Map<?, ?> message) throws JsonProcessingException {
       payload.put(Protocol.VERSION_2);
       payload.put((byte)'J');
       payload.putInt(sequence);

       byte[] json = Message.MAPPER.get().writeValueAsBytes(message);
       payload.putInt(json.length);
       payload.put(json);
   }

}
