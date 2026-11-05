package javax.microedition.io.file;
import java.io.IOException;
import java.io.OutputStream;
public interface FileConnection extends javax.microedition.io.Connection {
    boolean exists();
    void create() throws IOException;
    OutputStream openOutputStream() throws IOException;
}
