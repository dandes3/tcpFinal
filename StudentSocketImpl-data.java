import java.net.*;
import java.io.*;
import java.util.*;

class StudentSocketImpl extends BaseSocketImpl {

	// SocketImpl data members:
	//   protected InetAddress address;
	//   protected int port;
	//   protected int localport;

	private Demultiplexer D;
	private Timer tcpTimer;

	private int state;
	private int seqNum;
	private int ackNum;
	private Hashtable<Integer, TCPTimerTask> timerList; //holds the timers for sent packets
	private Hashtable<Integer, TCPPacket> packetList;	  //holds the packets associated with each timer
	//(for timer identification)
	private boolean wantsToClose = false;
	private boolean finSent = false;

	private static final int CLOSED = 0;
	private static final int SYN_SENT = 1;
	private static final int LISTEN = 2;
	private static final int SYN_RCVD = 3;
	private static final int ESTABLISHED = 4;
	private static final int FIN_WAIT_1 = 5;
	private static final int FIN_WAIT_2 = 6;
	private static final int CLOSING = 7;
	private static final int CLOSE_WAIT = 8;
	private static final int LAST_ACK = 9;
	private static final int TIME_WAIT = 10;

	static final int data_bytes_per_packet = 1000;

	private TCPPacket last_packet_sent = null;

	private PipedOutputStream appOS;
	private PipedInputStream appIS;
	private PipedInputStream pipeAppToSocket;
	private PipedOutputStream pipeSocketToApp;
	private SocketReader reader;
	private SocketWriter writer;
	private boolean terminating = false;
	private boolean pushed = false;

	private InfiniteBuffer sendBuffer;
	private InfiniteBuffer recvBuffer;

	private int sendBufLeft;
	private int sendBufSize; 

	private int recvBufLeft;
	private int recvBufSize; 

	private int recvWindow;
	private int unAckPackTrack; 


	StudentSocketImpl(Demultiplexer D) {  // default constructor
		this.D = D;
		state = CLOSED;
		unAckPackTrack = 0;
		seqNum = -1;
		ackNum = -1;
		timerList = new Hashtable<Integer, TCPTimerTask>();
		packetList = new Hashtable<Integer, TCPPacket>();

		try {
			pipeAppToSocket = new PipedInputStream();
			pipeSocketToApp = new PipedOutputStream();

			appIS = new PipedInputStream(pipeSocketToApp);
			appOS = new PipedOutputStream(pipeAppToSocket);
		}
		catch(IOException e){
			System.err.println("unable to create piped sockets");
			System.exit(1);
		}

		initBuffers();
		reader = new SocketReader(pipeAppToSocket, this);
		reader.start();
		writer = new SocketWriter(pipeSocketToApp, this);
		writer.start();
	}

	private String stateString(int inState){
		if(inState == 0){
			return "CLOSED";
		}
		else if(inState == 1){
			return "SYN_SENT";
		}
		else if(inState == 2){
			return "LISTEN";
		}
		else if(inState == 3){
			return "SYN_RCVD";
		}
		else if(inState == 4){
			return "ESTABLISHED";
		}
		else if(inState == 5){
			return "FIN_WAIT_1";
		}
		else if(inState == 6){
			return "FIN_WAIT_2";
		}
		else if(inState == 7){
			return "CLOSING";
		}
		else if(inState == 8){
			return "CLOSE_WAIT";
		}
		else if(inState == 9){
			return "LAST_ACK";
		}
		else if(inState == 10){
			return "TIME_WAIT";
		}
		else
			return "Invalid state number";
	}

	private synchronized void changeToState(int newState){
		System.out.println("!!! " + stateString(state) + "->" + stateString(newState));
		state = newState;

		if(newState == CLOSE_WAIT && wantsToClose && !finSent){
			try{
				close();
			}
			catch(IOException ioe){}
		}
		else if(newState == TIME_WAIT){
			createTimerTask(3000, null);
		}
	}

	private synchronized void sendPacket(TCPPacket inPacket, boolean resend){
		if(inPacket.ackFlag == true && inPacket.synFlag == false){
			inPacket.seqNum = -2;
		}

		if (resend) {
			//the packet is for resending, and requires the original state as the key
			Enumeration keyList = timerList.keys();
			Integer currKey = new Integer(-1);

			try {
				for(int i = 0; i<10; i++){
					currKey = (Integer)keyList.nextElement();

					if(packetList.get(currKey) == inPacket){
						System.out.println("Recreating TimerTask from state " + stateString(currKey));
						TCPWrapper.send(inPacket, address);
						timerList.put(currKey,createTimerTask(1000, inPacket));
						break;
					}
				}
			}
			catch(NoSuchElementException nsee){
			}

			return;
		}

		// new timer, and requires the current state as a key
		TCPWrapper.send(inPacket, address);

		// only do timers for syns, syn-acks, and fins
		if(inPacket.synFlag == true || inPacket.finFlag == true){
			System.out.println("Creating new TimerTask at state " + stateString(state));
			timerList.put(new Integer(state),createTimerTask(1000, inPacket));
			packetList.put(new Integer(state), inPacket);
		}
	}

	private synchronized void incrementCounters(TCPPacket p){
		ackNum = p.seqNum + 1;

		if(p.ackNum != -1)
			seqNum = p.ackNum;
	}

	private synchronized void cancelPacketTimer(){
		//must be called before changeToState is called!!!

		if(state != CLOSING){
			timerList.get(state).cancel();
			timerList.remove(state);
			packetList.remove(state);
		}
		else{
			//the only time the state changes before an ack is received... so it must
			//look back to where the fin timer started
			timerList.get(FIN_WAIT_1).cancel();
			timerList.remove(FIN_WAIT_1);
			packetList.remove(FIN_WAIT_1);
		}
	}

	/**
	 * initialize buffers and set up sequence numbers
	 */
	private void initBuffers(){
		// Buffers are made and trackers are set to keep endpoints on the current buffer size 
		//  amount of space that is left to write to.
		sendBuffer = new InfiniteBuffer();
		sendBufSize = sendBuffer.getBufferSize();
		sendBufLeft = sendBufSize;

		recvBuffer = new InfiniteBuffer();
		recvBufSize = recvBuffer.getBufferSize();
		recvBufLeft = recvBufSize;
	}

	/**
	 * Basically a nice little wrapper function that protects the inherently unsafe *infinite* circular buffer. 
	 * Wraps all attempts at appending with a safety fallout if the buffer tries to circle around and overwrite. 
	 */
	private synchronized void attemptAppend(InfiniteBuffer toAppend, int bufLeft, int bufSize, byte[] buffer, int length){
		if ((bufLeft - bufSize) < 0){
			System.out.println("Buffer circled around, panic and throw stuff.");
			return;
		}

		bufLeft -= length;
		toAppend.append(buffer, 0, length);
		return;
	}

	/**
	 * Basically a nice little wrapper function that protects the inherently unsafe *infinite* circular buffer. 
	 * Wraps all attempts at reading with a safety fallout if the buffer tries to read garbage data. 
	 */
	private synchronized void attemptRead(InfiniteBuffer toRead, int bufLeft, int bufSize, byte[] buffer, int length){
		if ((length == 0) || ((bufLeft + length) > bufSize)) { // Control for bogus length of read 0
			System.out.println("Reading too far or given length of zero. I can't believe you've done this.");
			return;
		}

		bufLeft += length; 
		toRead.copyOut(buffer, toRead.getBase(), length);
		toRead.advance(length);
		return;
	}

	synchronized void sendData() {
		// TODO: Figure out how to set up a tracking space

		int sentSpace = -1;
		if (recvWindow > 0){ sentSpace = 0;}

		while(unAckPackTrack <= 7 && ((sendBufSize - sendBufLeft) > 0) && sentSpace < recvWindow){
			int packSize = 1000;
			if (packSize > (sendBufSize - sendBufLeft)){ packSize = (sendBufSize - sendBufLeft);}
			if (packSize > (recvWindow - sentSpace)){ packSize = (recvWindow - sentSpace);}

			byte[] payload = new byte[packSize];
			attemptRead(sendBuffer, sendBufLeft, sendBufSize, payload, packSize);

			TCPPacket payloadPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, false, sendBufSize - sendBufLeft, payload);

			sentSpace += packSize;
			seqNum += packSize;
			unAckPackTrack ++;
			String plaintext = new String(payload);

			/** Please god work */
			sendPacket(payloadPacket, false);
			System.out.println("Attempted a packet send with data");

		}
		notifyAll();

	}

	/**
	 * Called by the application-layer code to copy data out of the
	 * recvBuffer into the application's space.
	 * Must block until data is available, or until terminating is true
	 * @param buffer array of bytes to return to application
	 * @param length desired maximum number of bytes to copy
	 * @return number of bytes copied (by definition > 0)
	 */
	synchronized int getData(byte[] buffer, int length){
		while ((recvBufSize - recvBufLeft) == 0){
			try {wait();} 
			catch (InterruptedException e){e.printStackTrace();}
		}

		int minReaderVal = recvBufSize - recvBufLeft; 
		if (length < minReaderVal) {
			minReaderVal = length;
		}

		attemptRead(recvBuffer, recvBufLeft, recvBufSize, buffer, minReaderVal);

		notifyAll();
		return minReaderVal;
	}

	/**
	 * accept data written by application into sendBuffer to send.
	 * Must block until ALL data is written.
	 * @param buffer array of bytes to copy into app
	 * @param length number of bytes to copy
	 */
	synchronized void dataFromApp(byte[] buffer, int length){
		System.out.println("In dataFromApp");
		while (sendBufLeft == 0){
			System.out.println("In dataFromApp wait loop");
			try {wait();} 
			catch (InterruptedException e){e.printStackTrace();}
		}

		attemptAppend(sendBuffer, sendBufLeft, sendBufSize, buffer, length);

		if (terminating){pushed = true;}

		sendData();
	}

	/**
	 * Connects this socket to the specified port number on the specified host.
	 *
	 * @param      address   the IP address of the remote host.
	 * @param      port      the port number.
	 * @exception  IOException  if an I/O error occurs when attempting a
	 *               connection.
	 */
	public synchronized void connect(InetAddress address, int port) throws IOException{
		//client state
		localport = D.getNextAvailablePort();
		this.address = address;
		this.port = port;

		D.registerConnection(address, localport, port, this);

		seqNum = 100;
		TCPPacket synPacket = new TCPPacket(localport, port, seqNum, ackNum, false, true, false, 1, null);
		changeToState(SYN_SENT);
		sendPacket(synPacket, false);
	}

	/**
	 * Called by Demultiplexer when a packet comes in for this connection
	 * @param p The packet that arrived
	 */
	public synchronized void receivePacket(TCPPacket p){
		System.out.println("Running receivePacket again");

		this.notifyAll();
		recvWindow = p.windowSize;

		System.out.println("Packet received from address " + p.sourceAddr + " with seqNum " + p.seqNum + " is being processed.");
		System.out.print("The packet is ");

		//if (p.data != null){
		//	if (state == ESTABLISHED){ sendData();}
		//}

		if(p.ackFlag == true && p.synFlag == true){
			System.out.println("a syn-ack.");

			if(state == SYN_SENT){
				//client state
				incrementCounters(p);
				cancelPacketTimer();
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(ESTABLISHED);
				sendPacket(ackPacket, false);
			}
			else if (state == ESTABLISHED){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if (state == FIN_WAIT_1){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
		}
		else if(p.ackFlag == true){
			System.out.println("an ack.");
			//for the love of God, do not incrementCounters(p) in here

			if(state == SYN_RCVD){
				//server state
				cancelPacketTimer();
				changeToState(ESTABLISHED);
				System.out.println("This is location 1");
			}
			else if(state == FIN_WAIT_1){
				//client state
				cancelPacketTimer();
				changeToState(FIN_WAIT_2);
			}
			else if(state == LAST_ACK){
				//server state
				cancelPacketTimer();
				changeToState(TIME_WAIT);
			}
			else if(state == CLOSING){
				//client or server state
				cancelPacketTimer();
				changeToState(TIME_WAIT);

			/* ACKed a data packet we sent */
		    } //else if (last_packet_sent != null) {
			// 	int expected_next_seq = seqNum + last_packet_sent.getData();

			// 	/* advance the buffer if we didn't lose the packet */
			// 	if (p.ackNum == expected_next_seq) {
			// 		seqNum = expected_next_seq;
			// 		sendBuffer.advance(last_packet_sent.getData());
			// 		sendData();

			// 	/* resend the packet if it appears to have been lost */
			// 	} else {
			// 		sendPacket(last_packet_sent, true);
			// 	}
			// }
		}
		else if(p.synFlag == true){
			System.out.println("a syn.");

			if(state == LISTEN){
				//server state
				try{
					D.unregisterListeningSocket(localport, this);	                     //***********tricky*************
					D.registerConnection(p.sourceAddr, p.destPort, p.sourcePort, this); //***********tricky*************
				}
				catch(IOException e){
					System.out.println("Error occured while attempting to establish connection");
				}

				this.address = p.sourceAddr;
				this.port = p.sourcePort;

				incrementCounters(p);
				TCPPacket synackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1, null);
				changeToState(SYN_RCVD);
				sendPacket(synackPacket, false);
			}

		}
		else if(p.finFlag == true){
			System.out.println("a fin.");

			if(state == ESTABLISHED){
				//server state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(CLOSE_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_1){
				//client state or server state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(CLOSING);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_2){
				//client state
				incrementCounters(p);
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				changeToState(TIME_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == LAST_ACK){
				//server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if(state == CLOSING){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
			else if(state == TIME_WAIT){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
				sendPacket(ackPacket, false);
			}
		}
		else{
			System.out.println("a chunk of data.");
		}
	}

	/**
	 * Waits for an incoming connection to arrive to connect this socket to
	 * Ultimately this is called by the application calling
	 * ServerSocket.accept(), but this method belongs to the Socket object
	 * that will be returned, not the listening ServerSocket.
	 * Note that localport is already set prior to this being called.
	 */
	public synchronized void acceptConnection() throws IOException {
		//server state
		changeToState(LISTEN);

		D.registerListeningSocket (localport, this);

		seqNum = 10000;

		try{
			this.wait();
		}
		catch(InterruptedException e){
			System.err.println("Error occured when trying to wait.");
		}
	}


	/**
	 * Returns an input stream for this socket.  Note that this method cannot
	 * create a NEW InputStream, but must return a reference to an
	 * existing InputStream (that you create elsewhere) because it may be
	 * called more than once.
	 *
	 * @return     a stream for reading from this socket.
	 * @exception  IOException  if an I/O error occurs when creating the
	 *               input stream.
	 */
	public InputStream getInputStream() throws IOException {
		return appIS;
	}

	/**
	 * Returns an output stream for this socket.  Note that this method cannot
	 * create a NEW InputStream, but must return a reference to an
	 * existing InputStream (that you create elsewhere) because it may be
	 * called more than once.
	 *
	 * @return     an output stream for writing to this socket.
	 * @exception  IOException  if an I/O error occurs when creating the
	 *               output stream.
	 */
	public OutputStream getOutputStream() throws IOException {
		return appOS;
	}


	/**
	 * Closes this socket.
	 *
	 * @exception  IOException  if an I/O error occurs when closing this socket.
	 */
	public synchronized void close() throws IOException {
		if(address==null)
			return;

		System.out.println("*** close() was called by the application.");

		/* TODO: send all remaining data */

		terminating = true;

		while(!reader.tryClose()){
			notifyAll();
			try{
				wait(1000);
			}
			catch(InterruptedException e){}
		}
		writer.close();

		notifyAll();

		if(state == ESTABLISHED){
			//client state
			TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
			changeToState(FIN_WAIT_1);
			sendPacket(finPacket, false);
			finSent = true;
		}
		else if(state == CLOSE_WAIT){
			//server state
			TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
			changeToState(LAST_ACK);
			sendPacket(finPacket, false);
			finSent = true;
		}
		else{
			System.out.println("Attempted to close while not established (ESTABLISHED) or waiting to close (CLOSE_WAIT)");
			//timer task here... try the closing process again
			wantsToClose = true;
		}
	}

	/**
	 * create TCPTimerTask instance, handling tcpTimer creation
	 * @param delay time in milliseconds before call
	 * @param ref generic reference to be returned to handleTimer
	 */
	private TCPTimerTask createTimerTask(long delay, Object ref){
		if(tcpTimer == null)
			tcpTimer = new Timer(false);
		return new TCPTimerTask(tcpTimer, delay, this, ref);
	}


	/**
	 * handle timer expiration (called by TCPTimerTask)
	 * @param ref Generic reference that can be used by the timer to return
	 * information.
	 */
	public synchronized void handleTimer(Object ref){

		if(ref == null){
			// this must run only once the last timer (30 second timer) has expired
			tcpTimer.cancel();
			tcpTimer = null;

			try{
				D.unregisterConnection(address, localport, port, this);
			}
			catch(IOException e){
				System.out.println("Error occured while attempting to close connection");
			}
		}
		else{	//its a packet that needs to be resent
			System.out.println("XXX Resending Packet");
			sendPacket((TCPPacket)ref, true);
		}
	}
}
