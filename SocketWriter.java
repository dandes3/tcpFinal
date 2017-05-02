import java.net.*;
import java.io.*;

class SocketWriter extends Thread{

  private OutputStream output;
  private StudentSocketImpl socket;
  private boolean terminated = false;

  public static final int BUFLEN = 1500;
  
  public SocketWriter (OutputStream s, StudentSocketImpl sock){
    this.output = s;
    this.socket = sock;
    setDaemon(true);
  }

  public void close(){
    terminated = true;
    try{
      output.close();
    }
    catch(IOException e){}
  }


  public void run(){
    byte[] buffer = new byte[BUFLEN];

    try{
      while(!terminated){
	int nread = socket.getData(buffer, BUFLEN);
	if(!terminated)
	  output.write(buffer, 0, nread);
      }
    }
    catch (IOException e){
      if(!terminated)
	System.err.println("SocketWriter exiting on "+e);
    }
  }
}



      
