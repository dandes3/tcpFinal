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

	private boolean awaiting_ack = false;

	StudentSocketImpl(Demultiplexer D) {  // default constructor
		this.D = D;
		state = CLOSED;
		seqNum = 0;
		ackNum = 0;
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
		if (newState == state)
			return;

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
		notifyAll();
	}

	private synchronized void sendPacket(TCPPacket inPacket, boolean resend){
		if (inPacket == null)
			inPacket = last_packet_sent;
		else
			last_packet_sent = inPacket;

		if (inPacket.data != null) {
			System.out.println("really sending the following data: " + new String(inPacket.data));
			awaiting_ack = true;
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

		// only do timers for syns, fins, and data packets
		if((inPacket.synFlag && !inPacket.ackFlag) || inPacket.finFlag || inPacket.data != null){
			System.out.println("Creating new TimerTask at state " + stateString(state));
			timerList.put(new Integer(state),createTimerTask(1000, inPacket));
			packetList.put(new Integer(state), inPacket);
		}
	}

	private synchronized void cancelPacketTimer(){
		//must be called before changeToState is called!!!
		System.out.println("cancelling timers");

		try {
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
		} catch (NullPointerException e) {
			;
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
	private synchronized void attemptAppend(boolean sendBuf, byte[] buffer, int length){

		if(sendBuf){
			//System.out.println("In attemptAppend send");
			if ((sendBufLeft - length) < 0){
				System.out.println("Buffer circled around, panic and throw stuff.");
				return;
			}

			sendBufLeft -= length;
			sendBuffer.append(buffer, 0, length);
			//System.out.println(sendBufLeft);
			return;
		}
		else{
			//System.out.println("In attemptAppend recv");
			if ((recvBufLeft - length) < 0){
				System.out.println("Buffer circled around, panic and throw stuff.");
				return;
			}

			recvBufLeft -= length;
			recvBuffer.append(buffer, 0, length);
			//System.out.println(recvBufLeft);
			return;
		}
	}

	/**
	 * Basically a nice little wrapper function that protects the inherently unsafe *infinite* circular buffer.
	 * Wraps all attempts at reading with a safety fallout if the buffer tries to read garbage data.
	 */
	private synchronized void attemptRead(boolean sendBuf, byte[] buffer, int length) {

		if(sendBuf){

			//System.out.println("In attemptRead send");
			if ((length == 0) || ((sendBufLeft + length) > sendBufSize)) { // Control for bogus length of read 0
				System.out.println("Reading too far or given length of zero. I can't believe you've done this.");
			}

			sendBufLeft += length;
			sendBuffer.copyOut(buffer, sendBuffer.getBase(), length);
		}
		else{
			//System.out.println("In attemptRead recv");
			if ((length == 0) || ((recvBufLeft + length) > recvBufSize)) { // Control for bogus length of read 0
				System.out.println("Reading too far or given length of zero. I can't believe you've done this.");
			}

			recvBufLeft += length;
			recvBuffer.copyOut(buffer, recvBuffer.getBase(), length);
			recvBuffer.advance(length);
		}

	}

	synchronized void sendData() {
		System.out.println("In sendData");

		int sentSpace = -1;
		if (recvWindow > 0){ sentSpace = 0;}

		while(((sendBufSize - sendBufLeft) > 0) && sentSpace < recvWindow){

			int packSize = data_bytes_per_packet;
			if (packSize > (sendBufSize - sendBufLeft)){ packSize = (sendBufSize - sendBufLeft);}

			byte[] payload = new byte[packSize];
			attemptRead(true, payload, packSize);

			ackNum += packSize;
			sentSpace += packSize;

			TCPPacket payloadPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, false, recvBufLeft, payload);
			sendPacket(payloadPacket, false);

			seqNum += packSize;
		}
		notifyAll();

		System.out.println("Out of sendData");
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
		System.out.println("In getData");

		while ((recvBufSize - recvBufLeft) == 0){
			try {wait();}
			catch (InterruptedException e){e.printStackTrace();}
		}

		int minReaderVal = recvBufSize - recvBufLeft;
		if (length < minReaderVal) {
			minReaderVal = length;
		}

		attemptRead(false, buffer, minReaderVal);

		notifyAll();

		System.out.println("leaving getData");

		return minReaderVal;
	}

	/**
	 * accept data written by application into sendBuffer to send.
	 * Must block until ALL data is written.
	 * @param buffer array of bytes to copy into app
	 * @param length number of bytes to copy
	 */
	synchronized void dataFromApp(byte[] buffer, int length){
		while (awaiting_ack || state != ESTABLISHED || sendBufLeft == 0){
			try {wait();}
			catch (InterruptedException e){e.printStackTrace();}
		}

		attemptAppend(true, buffer, length);

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
		TCPPacket synPacket = new TCPPacket(localport, port, seqNum, ackNum, false, true, false, recvBufLeft, null);
		changeToState(SYN_SENT);
		sendPacket(synPacket, false);
		seqNum += 1;
	}

	/**
	 * Called by Demultiplexer when a packet comes in for this connection
	 * @param p The packet that arrived
	 */
	public synchronized void receivePacket(TCPPacket p){
		//System.out.println("Running receivePacket again");

		this.notifyAll();
		recvWindow = p.windowSize;

		System.out.println("Packet received from address " + p.sourceAddr + " with seqNum " + p.seqNum + " is being processed.");
		System.out.print("The packet is ");

		/* data received, send an ACK */
		if (p.data != null) {

			if (state != SYN_RCVD && state != ESTABLISHED) {
				System.out.println("unexpected data packet!");
				return;
			}

			System.out.println("a packet of data");

			cancelPacketTimer();
			changeToState(ESTABLISHED);

			attemptAppend(false, p.data, p.data.length);

			seqNum += p.data.length;
			ackNum = p.seqNum + p.data.length;

			TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
			sendPacket(ackPacket, false);

			return;
		}

		if (p.ackFlag && p.synFlag){
			System.out.println("a syn-ack.");

			if(state == SYN_SENT){
				//client state

				ackNum = p.seqNum + 1;

				cancelPacketTimer();
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				changeToState(ESTABLISHED);
				sendPacket(ackPacket, false);
			}
			else if (state == ESTABLISHED){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				sendPacket(ackPacket, false);
			}
			else if (state == FIN_WAIT_1){
				//client state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				sendPacket(ackPacket, false);
			}
		}
		else if(p.ackFlag){

			System.out.println("an ack.");

			cancelPacketTimer();

			if (p.seqNum != ackNum || p.ackNum != seqNum) {
				System.out.println("ack number wasn't as expected, so ignoring that packet");
				if (last_packet_sent != null) {
					System.out.println("ack number wasn't as expected, so resending previous packet");
					sendPacket(null, true);
				}
				return;
			}

			awaiting_ack = false;

			if (last_packet_sent.data != null)
				sendBuffer.advance(last_packet_sent.data.length);

			if(state == SYN_RCVD) {
				//server state

				changeToState(ESTABLISHED);

				if (p.data != null)
					receivePacket(p);
				return;
			}

			if(state == FIN_WAIT_1){
				//client state
				changeToState(FIN_WAIT_2);
			}
			else if(state == LAST_ACK){
				//server state

				cancelPacketTimer();
				changeToState(TIME_WAIT);
			}
			else if(state == CLOSING){
				//client or server state
				changeToState(TIME_WAIT);
			}
		}
		else if(p.synFlag){
			System.out.println("a syn.");

			if(state == LISTEN || state == SYN_RCVD){
				ackNum = p.seqNum + 1;
				seqNum = 9000;

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

				TCPPacket synackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, recvBufLeft, null);
				changeToState(SYN_RCVD);
				sendPacket(synackPacket, false);
				seqNum++;
			}

		}
		else if(p.finFlag){
			System.out.println("a fin.");

			if(state == ESTABLISHED){
				//server state

				ackNum = p.seqNum + 1;
				seqNum++;

				cancelPacketTimer();
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				changeToState(CLOSE_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_1){
				//client state or server state
				ackNum = p.seqNum + 1;
				seqNum++;

				cancelPacketTimer();
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				changeToState(CLOSING);
				sendPacket(ackPacket, false);
			}
			else if(state == FIN_WAIT_2){
				//client state
				ackNum++;
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				changeToState(TIME_WAIT);
				sendPacket(ackPacket, false);
			}
			else if(state == LAST_ACK){
				//server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				sendPacket(ackPacket, false);
			}
			else if(state == CLOSING){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				sendPacket(ackPacket, false);
			}
			else if(state == TIME_WAIT){
				//client or server state, strange message due to packet loss
				TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, recvBufLeft, null);
				sendPacket(ackPacket, false);
			}
		} else {
			System.out.println("<< Bad, we shouldn't be able to get here");
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

		/* FIXME: do we need to send all remaining data? */

		terminating = true;

		while(!reader.tryClose()){
			notifyAll();
			try{
				wait(1000);
			}
			catch(InterruptedException e){}
		}
		//writer.close();

		notifyAll();


		if(state == ESTABLISHED){
			//client state
			ackNum++;
			cancelPacketTimer();
			TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, recvBufLeft, null);
			changeToState(FIN_WAIT_1);
			sendPacket(finPacket, false);
			finSent = true;
			seqNum++;
		}
		else if(state == CLOSE_WAIT){
			//server state
			cancelPacketTimer();
			TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, recvBufLeft, null);
			changeToState(LAST_ACK);
			sendPacket(finPacket, false);
			finSent = true;
			seqNum++;
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
