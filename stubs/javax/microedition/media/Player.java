package javax.microedition.media;
public interface Player {
    void realize() throws MediaException;
    void start() throws MediaException;
    void stop() throws MediaException;
    void close();
    Control getControl(String type);
}
