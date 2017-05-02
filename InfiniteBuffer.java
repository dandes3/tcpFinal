import java.util.*;
import java.io.*;


class InfiniteBuffer {

  // points to the beginning of the buffer
  private int base;

  // points to the next place to write in the buffer
  private int next;

  // these should not change after initialization
  private byte[] buffer;
  private int bufferSize;

    
  public InfiniteBuffer() {
    // the number 10000 is about 19-20 data chunks (one data chunk
    // for each packet) i think.
    this(10000);
  }



  public InfiniteBuffer(int bufferSize) {
    this.bufferSize = bufferSize;
    buffer = new byte[bufferSize];
    base = 0;
    next = 0;
  }

  public final int getBufferSize(){
    return bufferSize;
  }

  public synchronized void setOffset(int offset){
    base+=offset;
    next+=offset;
  }

  public final int getBase(){
    return base;
  }

  public synchronized void advanceTo(int newBase){
    base = newBase;
  }

  public final int getNext(){
    return next;
  }

  public synchronized void append(byte[] data, int dataOffset, int len){
    // loop through, circularly, adding data to the end of the
    // buffer....
    for (int i=0; i<len; i++) {
      buffer[(next+i)%bufferSize] = data[i+dataOffset];
    }

    next = next+len;
  }
    
  public synchronized void copyOut(byte[] data, int baseOrigin, int len){
    for(int i=0;i<len;i++)
      data[i] = buffer[(baseOrigin+i)%bufferSize];
  }

  public synchronized void advance(int addToBase){
    base += addToBase;
  }
}
