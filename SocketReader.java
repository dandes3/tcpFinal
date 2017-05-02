import java.net.*;
import java.io.*;

class SocketReader extends Thread{

  private InputStream input;
  private StudentSocketImpl socket;
  private boolean terminated = false;

  public static final int BUFLEN = 1500;
  
  public SocketReader (InputStream s, StudentSocketImpl sock){
    this.input = s;
    this.socket = sock;
    setDaemon(true);
  }

  public boolean tryClose(){
    try{
      if(input.available()>0){
	return false;
      }
    }
    catch(IOException e){}

    terminated = true;

    synchronized(input){
      input.notifyAll();
    }

    return !this.isAlive();
  }

  public void run(){
    byte[] buffer = new byte[BUFLEN];

    try{
      while(!terminated){
	synchronized(input){
	  while(input.available()==0 && !terminated){
	    try{
	      input.wait();
	    }
	    catch(InterruptedException e){}
	  }
	}

	if(!terminated){
	  int nread = input.read(buffer, 0, BUFLEN);
	  socket.dataFromApp(buffer, nread);
	}
      }
    }
    catch (IOException e){
      if(!terminated)
	System.err.println("SocketReader exiting on "+e);
    }
  }
}



      
