/*
 * P2PChat - Peer-to-Peer Chat Application
 *
 * Code taken from http://rox-xmlrpc.sourceforge.net/niotut/
 * Modified a little to fit.
 */
package netlib;

import java.io.IOException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class Connection implements Runnable
{
	private InetAddress hostAddress;
	private int port;
	private Selector selector;
	private SocketChannel channel;
	private NetEventListener listener;
	private List changeRequests = new LinkedList();
	private List pendingData = new ArrayList();
	private boolean connected = false;

	public Connection(InetAddress hostAddress, int port, NetEventListener listener) throws IOException
	{
		this.hostAddress = hostAddress;
		this.port = port;
		this.selector = this.initSelector();
		this.listener = listener;
		this.channel = this.initiateConnection();
	}

	public Connection(SocketChannel ch, NetEventListener listener) throws IOException
	{
		this.selector = initSelector();
		this.channel = ch;
		this.listener = listener;
		ch.configureBlocking(false);

		synchronized(changeRequests) {
			changeRequests.add(new ChangeRequest(ch, ChangeRequest.REGISTER, SelectionKey.OP_WRITE));
		}
	}

	private Selector initSelector() throws IOException
	{
		return SelectorProvider.provider().openSelector();
	}

	private SocketChannel initiateConnection() throws IOException
	{
		SocketChannel ch = SocketChannel.open();
		ch.configureBlocking(false);

		ch.connect(new InetSocketAddress(this.hostAddress, this.port));
		synchronized(this.changeRequests) {
			this.changeRequests.add(new ChangeRequest(ch, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
		}
		return ch;
	}

	public SocketChannel getChannel()
	{
		return channel;
	}

	public boolean isConnected()
	{
		return connected;
	}

	public void run()
	{
		while (true) {
			try {
				synchronized(changeRequests) {
					Iterator changes = changeRequests.iterator();
					while (changes.hasNext()) {
						ChangeRequest change = (ChangeRequest) changes.next();
						switch (change.type) {
						case ChangeRequest.CHANGEOPS:
							SelectionKey key = change.socket.keyFor(selector);
							key.interestOps(change.ops);
							break;
						case ChangeRequest.REGISTER:
							change.socket.register(selector, change.ops);
							break;
						}
					}
					changeRequests.clear();
				}
				selector.select();

				Iterator selectedKeys = selector.selectedKeys().iterator();
				while (selectedKeys.hasNext()) {
					SelectionKey  key = (SelectionKey)selectedKeys.next();
					selectedKeys.remove();

					if (!key.isValid())
						continue;

					if (key.isConnectable())
						finishConnection(key);
					else if (key.isReadable())
						read(key);
					else if (key.isWritable())
						write(key);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void finishConnection(SelectionKey key) throws IOException
	{
		try {
			channel.finishConnect();
		} catch (IOException e) {
			key.cancel();
			return;
		}

		key.interestOps(SelectionKey.OP_WRITE);
		if (listener != null && !listener.handleConnection(channel))
			close(channel);
		else
			connected = true;
	}

	private void read(SelectionKey key) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(1024);
		int count;
		try {
			count = channel.read(buffer);
		} catch (IOException e) {
			close(channel);
			return;
		}

		buffer.flip();
		if (count == -1
			|| (listener != null 
				&& !listener.handleRead(channel, buffer, count)))
			close(channel);
	}

	private void write(SelectionKey key) throws IOException
	{
		int count = 0;

		synchronized(pendingData) {
			while (!pendingData.isEmpty()) {
				ByteBuffer buf = (ByteBuffer) pendingData.get(0);
				channel.write(buf);

				count += buf.capacity() - buf.remaining();
				if (buf.remaining() > 0)
					break;
				pendingData.remove(0);
			}

			if (pendingData.isEmpty())
				key.interestOps(SelectionKey.OP_READ);
		}

		if (listener != null && !listener.handleWrite(channel, count))
			close(channel);
	}

	public void send(byte[] data)
	{
		synchronized (changeRequests) {
			changeRequests.add(new ChangeRequest(channel, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
			synchronized (pendingData) {
				pendingData.add(ByteBuffer.wrap(data));
			}
		}
		selector.wakeup();
	}

	public void close(SocketChannel ch)
	{
		if (listener != null && !listener.handleConnectionClose(ch))
			return;

		try {
			ch.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		ch.keyFor(selector).cancel();
		synchronized(changeRequests) {
			Iterator changes = changeRequests.iterator();
			while (changes.hasNext()) {
				ChangeRequest req = (ChangeRequest) changes.next();
				if (req.socket == ch) {
					changeRequests.remove(req);
					break;
				}
			}
		}
	}
}
