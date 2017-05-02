import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private Demultiplexer D;
  private Timer tcpTimer;

  private PipedOutputStream appOS;
  private PipedInputStream appIS;

  private PipedInputStream pipeAppToSocket;
  private PipedOutputStream pipeSocketToApp;

  private SocketReader reader;
  private SocketWriter writer;

  private boolean terminating = false;

  private InfiniteBuffer sendBuffer;
  private InfiniteBuffer recvBuffer;


  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;

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

  /**
   * initialize buffers and set up sequence numbers
   */
  private void initBuffers(){
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
  }

  /**
   * accept data written by application into sendBuffer to send.
   * Must block until ALL data is written.
   * @param buffer array of bytes to copy into app
   * @param length number of bytes to copy 
   */
  synchronized void dataFromApp(byte[] buffer, int length){
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
    localport = D.getNextAvailablePort();
  }
  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
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

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }

}
