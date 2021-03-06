/*
 * This is an alternative server/client library for Processing. 
 * Copyright (C)2009 Andreas Löf 
 * Email: andreas@alternating.net
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package net.alternating.alternet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import processing.core.PApplet;

/**
 * 
 * This class acts as an asynchronous TCP server. It listens for new connections
 * on the port specified in the constructor and adds the new connections to the
 * list of connections to monitor.
 * <p>
 * A callback is done every time a new connection is established, a connection
 * is torn down or data is received if the PApplet invoking this server has the
 * correct methods defined.
 * <p>
 * The appropriate methods in processing are:<br>
 * <code>
 * void serverConnectEvent(Server serverInstance, RemoteAddress clientAddress);<br>
 * void serverDisconnectEvent(Server serverInstance, RemoteAddress clientAddress);<br>
 * void serverReceiveEvent(Server serverInstance, RemoteAddress clientAddress, String data);<br>
 * void serverReceiveEvent(Server serverInstance, RemoteAddress clientAddress, byte[] data);<br>
 * </code>
 * <p>
 * The <code>serverReceiveEvent(...)</code> method with the <code>String</code>
 * argument will receive the data as an UTF-8 string. The version with a
 * <code>byte[]</code> will receive the data without any conversions applied to
 * it.
 * <p>
 * The server itself runs in a separate thread.
 * 
 * @author Andreas L&ouml;f
 * @see RemoteAddress
 * @see Client
 */
public class Server extends Thread implements Deliverer {

	public static int bufferSize = 256 * 1024 * 1024;

	private int port;
	private ServerSocketChannel serverChannel;
	private ServerSocket serverSocket;

	private Selector selector;

	private Charset charset = Charset.forName("UTF-8");
	private CharsetDecoder decoder = charset.newDecoder();
	private PApplet parent;

	private Method connectEvent;
	private Method disconnectEvent;
	private Method receiveEventString;
	private Method receiveEventByteArray;

	private Map connectedClients;

	private boolean run = true;

	protected SocketChannel clientChannel;

	/**
	 * This constructs a new Server. The server will immediately start a new
	 * thread and start listening on the specified port on all of the network
	 * interfaces present in the computer.
	 * 
	 * 
	 * 
	 * @param parent
	 *            the processing applet that contains the server instance.
	 * @param port
	 *            the port that the server will listen for new connections on
	 */
	public Server(PApplet parent, int port) {
		this.parent = parent;
		this.port = port;

		parent.registerDispose(this);

		connectedClients = Collections.synchronizedMap(new TreeMap());

		try {
			connectEvent = parent.getClass().getMethod("serverConnectEvent",
					new Class[] { Server.class, RemoteAddress.class });
		} catch (Exception e) {
			// not declared, fine.
			// so we won't invoke this method.
			connectEvent = null;
		}

		try {
			disconnectEvent = parent.getClass().getMethod(
					"serverDisconnectEvent",
					new Class[] { Server.class, RemoteAddress.class });
		} catch (Exception e) {
			// not declared, fine.
			// so we won't invoke this method.
			disconnectEvent = null;
		}
		try {
			receiveEventString = parent.getClass().getMethod(
					"serverReceiveEvent",
					new Class[] { Server.class, RemoteAddress.class,
							String.class });
		} catch (Exception e) {
			// not declared, fine.
			// so we won't invoke this method.
			receiveEventString = null;
		}
		try {
			receiveEventByteArray = parent.getClass().getMethod(
					"serverReceiveEvent",
					new Class[] { Server.class, RemoteAddress.class,
							byte[].class });
		} catch (Exception e) {
			// not declared, fine.
			// so we won't invoke this method.
			receiveEventByteArray = null;
		}

		start();
	}

	/**
	 * Returns the port that this server instance is listening for new
	 * connections on
	 * 
	 * @return the port that the server is bound to
	 */
	public int getPort() {
		return port;
	}

	/**
	 * This method is doing the actual work of the server. It deals with all of
	 * the incoming connections and data.
	 * 
	 */
	public void run() {
		// used to identify the server
		SelectionKey serverKey;
		// FIXME this is probably not big enough in the long run
		ByteBuffer bf = ByteBuffer.allocate(bufferSize);

		try {
			// open a new selector
			selector = Selector.open();
			// initiate the server socket and register it with the selector
			serverChannel = ServerSocketChannel.open();
			serverSocket = serverChannel.socket();
			serverSocket.bind(new InetSocketAddress(port));
			port = serverSocket.getLocalPort();
			serverChannel.configureBlocking(false);
			serverKey = serverChannel
					.register(selector, SelectionKey.OP_ACCEPT);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		// run until we are told not to
		while (run) {
			SelectionKey key = null;
			// select the channels that have data coming in on them or new
			// connections
			try {
				while (selector.select() > 0) {

					// find the channels with activity and iterate through them
					Set keys = selector.selectedKeys();
					key = null;
					for (Iterator it = keys.iterator(); it.hasNext();) {

						key = (SelectionKey) it.next();
						// remove the key so we don't process it twice
						it.remove();
						try {
							// did we get a new connection?
							if (key.equals(serverKey) && key.isAcceptable()) {

								// accept the connection and add it to the
								// selector
								SocketChannel newConnection = serverChannel
										.accept();

								newConnection.configureBlocking(false);
								newConnection.register(selector,
										SelectionKey.OP_READ);

								RemoteAddress remoteSide = new RemoteAddress(
										newConnection.socket().getInetAddress()
												.toString(), newConnection
												.socket().getPort());

								newClientConnected(newConnection, remoteSide);

							}
							// it wasn't the server, thus it must be one of the
							// clients
							else {

								clientChannel = (SocketChannel) key.channel();

								// have we gotten new data?
								if (key.isReadable()) {
									// clear the receiving buffer
									bf.clear();

									// read data from the channel
									int bytesRead = -1;
									try {
										bytesRead = clientChannel.read(bf);
									} catch (IOException ioe) {
										ioe.printStackTrace();
									}

									// is the socket closed?
									if (bytesRead == -1) {
										clientDisconnected(key);
									}
									// we got data
									else {

										decodeData(bf, bytesRead);
									}
								} else if (key.isWritable()) {
									// if we cared about the channel being ready
									// for
									// writing, this is where
									// we'd handle it.
									// however, listening for the write status
									// would
									// lead to us doing busy waiting

								}
							}
							// these exceptions deal with a specific channel.
						} catch (ClosedChannelException e) {
							if (key != serverKey) {

								clientDisconnected(key);
							}
							try {
								key.channel().close();
							} catch (IOException e1) {
								e1.printStackTrace();
							}
							e.printStackTrace();
						} catch (CharacterCodingException e) {
							e.printStackTrace();
						} catch (IOException e) {
							if (key != serverKey) {

								clientDisconnected(key);
							}
							e.printStackTrace();
						}
					}
				}
				// this exception is about the selector
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}

	protected void clientDisconnected(SelectionKey key) {
		RemoteAddress remoteSide = new RemoteAddress(clientChannel.socket()
				.getInetAddress().toString(), clientChannel.socket().getPort());

		if (clientChannel.isOpen())
			try {
				clientChannel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		synchronized (connectedClients) {
			connectedClients.remove(remoteSide);
		}
		key.cancel();
		throwDisconnectedEvent(remoteSide);
	}

	protected void newClientConnected(SocketChannel newConnection,
			RemoteAddress remoteSide) {
		// add it to our list of active connections
		synchronized (connectedClients) {
			connectedClients.put(remoteSide, newConnection);
		}

		// notify the processing applet that we have a
		// new connection
		throwConnectionEvent(remoteSide);
	}

	protected void decodeData(ByteBuffer bf, int read)
			throws CharacterCodingException {
		//flip the buffer around so it's right
		bf.flip();
		// throw a received event for String
		// data
		throwReceiveEventString(bf);

		// throw a received event for a byte[]
		throwReceiveEventByteArray(bf);
	}

	/**
	 * This is a helper method used to notify the encapsulating processing
	 * applet that we have received data. The data will be delivered to the
	 * processing applet as a byte[].
	 * 
	 * @param bf
	 *            the ByteBuffer containing the data
	 * @param clientChannel
	 *            the SocketChannel the data came from
	 */
	public void throwReceiveEventByteArray(ByteBuffer bf) {
		if (receiveEventByteArray != null) {
			
			// byte[] data = (byte[]) bf.array().clone();
			int size = bf.limit();
			byte[] data = new byte[size];
			System.arraycopy(bf.array(), 0, data, 0, size);
			RemoteAddress remoteSide = new RemoteAddress(clientChannel.socket()
					.getInetAddress().toString(), clientChannel.socket()
					.getPort());
			Object[] args = { this, remoteSide, data };
			try {
				receiveEventByteArray.invoke(parent, args);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This is a helper method used to notify the encapsulating processing
	 * applet that we have received data. The data will be delivered to the
	 * processing applet as a String.
	 * 
	 * @param bf
	 *            the ByteBuffer containing the data
	 * @param clientChannel
	 *            the SocketChannel the data came from
	 */

	public void throwReceiveEventString(ByteBuffer bf)
			throws CharacterCodingException {
		if (receiveEventString != null) {
			
			String data = decoder.decode(bf).toString();
			RemoteAddress remoteSide = new RemoteAddress(clientChannel.socket()
					.getInetAddress().toString(), clientChannel.socket()
					.getPort());
			Object[] args = { this, remoteSide, data };
			try {
				receiveEventString.invoke(parent, args);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This is a a helper method that will tell the encapsulating processing
	 * applet that a client has disconnected
	 * 
	 * @param remoteSide
	 *            the address and port of the disconnected client
	 */
	private void throwDisconnectedEvent(RemoteAddress remoteSide) {
		if (disconnectEvent != null) {
			Object[] args = { this, remoteSide };
			try {
				disconnectEvent.invoke(parent, args);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This is a helper method that tells the encapsulating processing applet
	 * that a new client has connected.
	 * 
	 * @param remoteSide
	 *            the port and address of the new client
	 */
	private void throwConnectionEvent(RemoteAddress remoteSide) {
		if (connectEvent != null) {
			Object[] args = { this, remoteSide };
			try {
				connectEvent.invoke(parent, args);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * This method sends data to all connected clients.
	 * 
	 * @param data
	 *            the data to be sent
	 */
	public void sendToAll(String data) {
		try {
			this.sendToAll(data.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			this.sendToAll(data.getBytes());
		}
	}

	/**
	 * This method sends data to all connected clients.
	 * 
	 * @param data
	 *            the data to be sent
	 */
	public void sendToAll(int data) {
		this.sendToAll(Integer.toString(data));
	}

	/**
	 * This method sends data to all connected clients.
	 * 
	 * @param data
	 *            the data to be sent
	 */
	public void sendToAll(double data) {
		this.sendToAll(Double.toString(data));
	}

	/**
	 * This method sends data to all connected clients.
	 * 
	 * @param data
	 *            the data to be sent
	 */
	public void sendToAll(byte data) {
		this.sendToAll(Byte.toString(data));
	}

	/**
	 * This method sends data to all connected clients.
	 * 
	 * @param data
	 *            the data to be sent
	 */
	public void sendToAll(byte[] data) {
		synchronized (connectedClients) {
			Iterator keys = connectedClients.keySet().iterator();
			while (keys.hasNext()) {
				RemoteAddress address = (RemoteAddress) keys.next();
				this.sendTo(address, data);
			}
		}
	}

	/**
	 * This method sends data to a specified client identified by its
	 * RemoteAddress.
	 * 
	 * @param address
	 *            the connected client's remote address
	 * @param data
	 *            the data to be sent
	 * @see RemoteAddress
	 */
	public void sendTo(RemoteAddress address, String data) {
		try {
			sendTo(address, data.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			sendTo(address, data.getBytes());
		}
	}

	/**
	 * This method sends data to a specified client identified by its
	 * RemoteAddress.
	 * 
	 * @param address
	 *            the connected client's remote address
	 * @param data
	 *            the data to be sent
	 * @see RemoteAddress
	 */
	public void sendTo(RemoteAddress address, int data) {
		this.sendTo(address, Integer.toString(data));
	}

	/**
	 * This method sends data to a specified client identified by its
	 * RemoteAddress.
	 * 
	 * @param address
	 *            the connected client's remote address
	 * @param data
	 *            the data to be sent
	 * @see RemoteAddress
	 */
	public void sendTo(RemoteAddress address, double data) {
		this.sendTo(address, Double.toString(data));
	}

	/**
	 * This method sends data to a specified client identified by its
	 * RemoteAddress.
	 * 
	 * @param address
	 *            the connected client's remote address
	 * @param data
	 *            the data to be sent
	 * @see RemoteAddress
	 */
	public void sendTo(RemoteAddress address, byte data) {
		this.sendTo(address, Byte.toString(data));
	}

	/**
	 * This method sends data to a specified client identified by its
	 * RemoteAddress.
	 * 
	 * @param address
	 *            the connected client's remote address
	 * @param data
	 *            the data to be sent
	 * @see RemoteAddress
	 */
	public void sendTo(RemoteAddress address, byte[] data) {
		synchronized (connectedClients) {
			SocketChannel clientChannel = (SocketChannel) connectedClients
					.get(address);

			ByteBuffer bf = ByteBuffer.wrap(data);
			try {
				int written = 0;
				while (written < data.length) {
					int i = clientChannel.write(bf);
					written += i;
				}
			} catch (IOException e) {
				connectedClients.remove(address);
				try {
					clientChannel.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				e.printStackTrace();
			}
		}
	}

	/**
	 * This method is called by the processing applet that contains this server
	 * instance. All of the open network sockets are closed and the worker
	 * thread is told to terminate.
	 * 
	 */
	public void dispose() {
		run = false;
		try {
			serverChannel.close();
			synchronized (connectedClients) {
				Iterator keys = connectedClients.keySet().iterator();
				while (keys.hasNext()) {
					RemoteAddress address = (RemoteAddress) keys.next();
					SocketChannel clientChannel = (SocketChannel) connectedClients
							.get(address);
					clientChannel.close();
				}
			}
			selector.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * This method is used to disconnect a specific client.
	 * 
	 * @param clientAddress
	 *            the address of the client to disconnect.
	 */
	public void disconnectClient(RemoteAddress clientAddress) {
		synchronized (connectedClients) {
			SocketChannel clientChannel = (SocketChannel) connectedClients
					.get(clientAddress);
			if (clientChannel != null) {
				try {
					clientChannel.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * This method returns the number of currently connected clients to the
	 * server.
	 * 
	 * @return the number of connected clients.
	 */
	public int getNumberOfConnectedClients() {
		int num = 0;
		synchronized (connectedClients) {
			num = connectedClients.size();
		}
		return num;
	}

}
