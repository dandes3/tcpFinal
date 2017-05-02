JFLAGS = -g
JC = javac
.SUFFIXES: .java .class
.java.class:
	 $(JC) $(JFLAGS) $*.java

CLASSES = \
        StudentSocketImpl-data.java \
        client3.java \
        client2.java \
	client1.java \
        server3.java \
        server2.java \
	server1.java

default: classes

classes: $(CLASSES:.java=.class)

clean:
	$(RM) *.class
