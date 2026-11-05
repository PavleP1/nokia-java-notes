package javax.microedition.media.control;
import javax.microedition.media.Control;
import javax.microedition.media.MediaException;
public interface VideoControl extends Control {
    int USE_GUI_PRIMITIVE = 0;
    int USE_DIRECT_VIDEO = 1;
    Object initDisplayMode(int mode, Object arg);
    void setDisplayLocation(int x, int y);
    void setDisplaySize(int w, int h) throws MediaException;
    void setVisible(boolean visible);
    byte[] getSnapshot(String type) throws MediaException;
}
