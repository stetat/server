import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

public class NioServer {
    static class Deferred {
        long deadline;
        SelectionKey key;
        ByteBuffer response;
        Deferred(long d, SelectionKey k, ByteBuffer r) {
            deadline = d;
            key = k;
            response = r;
        }
    }

    static PriorityQueue<Deferred> timers = new PriorityQueue<>(Comparator.comparingLong(d -> d.deadline));

    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress("localhost", 8080));
        serverChannel.configureBlocking(false);

        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        while(true) {
            long timeout;
            if(timers.isEmpty()) {
                timeout = 0;
            } else {
                long delay = timers.peek().deadline - System.currentTimeMillis();
                timeout = Math.max(1, delay);
            }
            selector.select(timeout);
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while(it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if(key.isAcceptable()) {
                    SocketChannel client = serverChannel.accept();
                    client.configureBlocking(false).register(selector, SelectionKey.OP_READ, new StringBuilder());
                    System.out.println("accepted" + client.getRemoteAddress());
                } else if(key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    int n = client.read(buffer);

                    if(n == -1) {
                        key.cancel();
                        client.close();
                        continue;
                    }

                    buffer.flip();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    StringBuilder acc = (StringBuilder) key.attachment();
                    acc.append(new String(bytes, StandardCharsets.UTF_8));

                    if(acc.toString().contains("\r\n\r\n")) {
                        System.out.println("FULL REQUEST received:\n" + acc);

                        String requestText = acc.toString();
                        String requestLine = requestText.split("\r\n")[0];
                        String path = requestLine.split(" ")[1];


                        String body = "Hello from NIO!";
                        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                        String headers = "HTTP/1.1 200 OK\r\n" +
                                         "Content-Type: text/plain\r\n" +
                                         "Content-Length: " + bodyBytes.length + "\r\n" +
                                         "Connection: close\r\n" +
                                         "\r\n";
                        byte[] responseBytes = (headers + body).getBytes(StandardCharsets.UTF_8);

                        ByteBuffer out = ByteBuffer.wrap(responseBytes);
                        if(path.equals("/slow")) {
                            long deadline = System.currentTimeMillis() + (1000 * 3);
                            timers.add(new Deferred(deadline, key, out));
                        } else {
                            client.write(out);
                            if (!out.hasRemaining()) {
                                key.cancel();
                                client.close();
                            } else {
                                key.attach(out);
                                key.interestOps(SelectionKey.OP_WRITE);
                            }
                        }
                    }

                } else if(key.isWritable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    ByteBuffer out = (ByteBuffer) key.attachment();
                    client.write(out);
                    if(!out.hasRemaining()) {
                        key.cancel();
                        client.close();

                    }
                }
            }

            long now = System.currentTimeMillis();
            while(!timers.isEmpty() && timers.peek().deadline <= now) {
                Deferred d = timers.poll();
                SocketChannel client = (SocketChannel) d.key.channel();
                client.write(d.response);
                if(!d.response.hasRemaining()) {
                    d.key.cancel();
                    client.close();
                } else {
                    d.key.attach(d.response);
                    d.key.interestOps(SelectionKey.OP_WRITE);
                }
            }

        }
    }
}
