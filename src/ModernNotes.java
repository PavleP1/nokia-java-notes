import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;
import javax.microedition.io.Connector;
import javax.microedition.io.PushRegistry;
import javax.microedition.io.file.FileConnection;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.Control;
import javax.microedition.media.control.VideoControl;
import java.io.*;
import java.util.Calendar;
import java.util.Vector;

public class ModernNotes extends MIDlet {
    private HubCanvas canvas;

    public void startApp() {
        if (canvas == null) {
            canvas = new HubCanvas(this);
            canvas.start();
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.onResume();
    }
    public void pauseApp() { if (canvas != null) canvas.stop(false); }
    public void destroyApp(boolean unconditional) { if (canvas != null) canvas.stop(true); }
    void exit() { destroyApp(true); notifyDestroyed(); }
    boolean openUrl(String url) {
        try { return platformRequest(url); } catch (Exception e) { return false; }
    }
}

class HubCanvas extends Canvas implements Runnable {
    static final int S_HOME = 0, S_NOTES = 1, S_EDIT = 2, S_ALARM = 3, S_CAM = 4, S_PHOTO = 5, S_SET = 6, S_RING = 7;
    static final int TRANS_MS = 220;
    static final String UPDATE_URL = "";   // optional: http://yourserver/ModernNotes.jad
    static final int MAX_LEN = 2000;
    static final int TL = Graphics.TOP | Graphics.LEFT;
    static final int TC = Graphics.TOP | Graphics.HCENTER;
    static final int TR = Graphics.TOP | Graphics.RIGHT;
    static final String[] KEYS = {" 0", ".,?!'-@1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    static final String[] MODES = {"abc", "Abc", "ABC", "123"};
    static final String[] THEME_NAMES = {"Dark", "Light", "Ocean"};
    static final String[] FILTERS = {"Normal", "Mono", "Sepia", "Invert", "Warm", "Cool", "Vivid", "Fade"};
    static final String[] HOME_NAMES = {"Notes", "Alarm", "Camera", "Settings"};
    static final int[] HOME_COLS = {0x0A84FF, 0xFF9F0A, 0x30D158, 0xBF5AF2};
    // bg, card, text, subtext, accent, bar
    static final int[][] THEMES = {
        {0x121214, 0x1E1E22, 0xFFFFFF, 0x8E8E93, 0x0A84FF, 0x1C1C1E},
        {0xF2F2F7, 0xFFFFFF, 0x000000, 0x6C6C70, 0x007AFF, 0xE5E5EA},
        {0x0B1B2B, 0x13293D, 0xE8F1F8, 0x7F9BB3, 0x2EC4B6, 0x102235}
    };

    private final ModernNotes midlet;
    private final Display disp;
    private volatile boolean running = true;
    private boolean started;
    private final Font fBody = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fBold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font fHead = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private final Font fBig = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);

    private int w, h, headH, barH, cardH;
    private int theme, screen, homeSel, setSel;
    private Image headImg, backImg;

    // transitions
    private Image tFrom, tTo;
    private volatile boolean trans;
    private long tStart;
    private int tDir;

    // notes
    private final Vector notes = new Vector();
    private int sel, listScroll, editIndex = -1;
    private StringBuffer buf = new StringBuffer();
    private Vector lines;
    private int titleLines, edScroll;
    private int mode = 1, lastKey = -1, keyIdx;
    private long lastTime;
    private boolean curUpper, blink = true, delArmed;
    private long blinkTime, delTime;

    // toast
    private String toast;
    private long toastTime;
    private int toastOff;

    // alarm
    private int alH = 7, alM = 0, alField;
    private boolean alOn, alDaily, ringing;
    private long alTime, ringStart, ringLast;

    // camera
    private Player player;
    private VideoControl vc;
    private boolean camOk, camPending;
    private String camErr;
    private Image photo, filtered;
    private int fIdx;

    HubCanvas(ModernNotes m) {
        midlet = m;
        disp = Display.getDisplay(m);
        setFullScreenMode(true);
        w = getWidth();
        h = getHeight();
        headH = fHead.getHeight() + 14;
        barH = fBody.getHeight() + 10;
        cardH = fBold.getHeight() + fBody.getHeight() + 16;
        load();
        loadAlarm();
    }

    void start() {
        if (started) return;
        started = true;
        new Thread(this).start();
    }

    void stop(boolean exiting) {
        if (exiting) running = false;
        if (screen == S_EDIT) commit();
        save();
        camStop();
    }

    void onResume() {
        long now = System.currentTimeMillis();
        if (alOn && alTime > 0 && !ringing) {
            if (now >= alTime && now - alTime < 600000L) ring();
            else if (now - alTime >= 600000L && now >= alTime) {
                if (alDaily) armAlarm(false);
                else { alOn = false; alTime = 0; saveAlarm(); }
            }
        }
    }

    protected void sizeChanged(int nw, int nh) { w = nw; h = nh; headImg = null; lines = null; }
    protected void showNotify() { if (screen == S_CAM && !camOk) camPending = true; }
    protected void hideNotify() { camStop(); }

    // ================= main loop (time based, repaints only when needed)
    public void run() {
        while (running) {
            long now = System.currentTimeMillis();
            boolean dirty = false, anim = false;

            if (trans) {
                anim = dirty = true;
                if (now - tStart >= TRANS_MS) { trans = false; tFrom = null; tTo = null; }
            }
            int tOff = (toast != null && now - toastTime < 1800) ? barH + fBold.getHeight() + 20 : 0;
            if (toastOff != tOff) {
                int d = tOff - toastOff, s = d / 3;
                if (s == 0) s = d > 0 ? 1 : -1;
                toastOff += s;
                dirty = anim = true;
            } else if (tOff == 0) {
                toast = null;
            }
            if (screen == S_EDIT && !trans && now - blinkTime > 500) { blink = !blink; blinkTime = now; dirty = true; }
            if (camPending) { camPending = false; camStart(); dirty = true; }

            if (alOn && alTime > 0 && !ringing && now >= alTime && now - alTime < 600000L) ring();
            if (ringing) {
                dirty = true;
                if (now - ringLast >= 2000) {
                    ringLast = now;
                    try { AlertType.ALARM.playSound(disp); disp.vibrate(800); disp.flashBacklight(1000); } catch (Throwable e) {}
                }
                if (now - ringStart > 90000L) dismiss(false);
            }
            if (dirty) repaint();
            try { Thread.sleep(anim ? 30 : 100); } catch (InterruptedException e) {}
        }
    }

    private void showToast(String m) { toast = m; toastTime = System.currentTimeMillis(); }

    // ================= persistence
    private void load() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("mnotes", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                theme = in.readInt();
                int n = in.readInt();
                for (int i = 0; i < n; i++) notes.addElement(in.readUTF());
            }
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
        if (theme < 0 || theme >= THEMES.length) theme = 0;
        if (notes.size() == 0) notes.addElement("Welcome to Modern Hub\nNotes, alarm and camera in one app.\nNotes are saved automatically.");
    }

    synchronized void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(theme);
            o.writeInt(notes.size());
            for (int i = 0; i < notes.size(); i++) o.writeUTF((String) notes.elementAt(i));
            o.flush();
            byte[] d = bo.toByteArray();
            rs = RecordStore.openRecordStore("mnotes", true);
            if (rs.getNumRecords() == 0) rs.addRecord(d, 0, d.length);
            else rs.setRecord(1, d, 0, d.length);
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private void loadAlarm() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("malarm", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                alH = in.readInt(); alM = in.readInt();
                alOn = in.readBoolean(); alDaily = in.readBoolean();
                alTime = in.readLong();
            }
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private void saveAlarm() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(alH); o.writeInt(alM);
            o.writeBoolean(alOn); o.writeBoolean(alDaily);
            o.writeLong(alTime);
            o.flush();
            byte[] d = bo.toByteArray();
            rs = RecordStore.openRecordStore("malarm", true);
            if (rs.getNumRecords() == 0) rs.addRecord(d, 0, d.length);
            else rs.setRecord(1, d, 0, d.length);
        } catch (Exception e) {
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    // ================= helpers
    private static int mix(int a, int b, int p) {
        int r = (((a >> 16) & 255) * (255 - p) + ((b >> 16) & 255) * p) / 255;
        int g = (((a >> 8) & 255) * (255 - p) + ((b >> 8) & 255) * p) / 255;
        int bl = ((a & 255) * (255 - p) + (b & 255) * p) / 255;
        return (r << 16) | (g << 8) | bl;
    }
    private static int cl(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
    private static String two(int v) { return v < 10 ? "0" + v : "" + v; }

    private String fit(String s, Font f, int maxW) {
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
    private int action(int k) { try { return getGameAction(k); } catch (Exception e) { return 0; } }
    private boolean isLeft(int k) { return k == -6 || k == -21; }
    private boolean isRight(int k) { return k == -7 || k == -22; }
    private boolean isClear(int k) { return k == -8; }

    // ================= navigation with cached-snapshot slide transitions
    private Image snap(int s) {
        Image im = Image.createImage(w, h);
        renderScreen(im.getGraphics(), s);
        return im;
    }

    private void nav(int ns, int dir) {
        int os = screen;
        if (ns == os) return;
        Image a = null, b = null;
        if (ns != S_CAM && os != S_CAM && ns != S_RING && os != S_RING) {
            try { a = snap(os); b = snap(ns); } catch (Throwable e) { a = null; b = null; }
        }
        screen = ns;
        if (a != null && b != null) {
            tFrom = a; tTo = b; tDir = dir;
            tStart = System.currentTimeMillis();
            trans = true;
        } else {
            trans = false;
        }
        if (os == S_CAM) camStop();
        if (ns == S_CAM) { camErr = null; camPending = true; }
    }

    protected void paint(Graphics g) {
        int nw = getWidth(), nh = getHeight();
        if (nw != w || nh != h) { w = nw; h = nh; headImg = null; lines = null; }
        Graphics gg = g;
        if (!isDoubleBuffered()) {
            try {
                if (backImg == null || backImg.getWidth() != w || backImg.getHeight() != h) backImg = Image.createImage(w, h);
                gg = backImg.getGraphics();
            } catch (Throwable e) { gg = g; backImg = null; }
        }
        if (trans && tFrom != null && tTo != null) drawTrans(gg);
        else renderScreen(gg, screen);
        drawToast(gg, THEMES[theme]);
        if (gg != g) g.drawImage(backImg, 0, 0, TL);
    }

    private void drawTrans(Graphics g) {
        int el = (int) (System.currentTimeMillis() - tStart);
        if (el > TRANS_MS) el = TRANS_MS;
        int p = el * 256 / TRANS_MS;
        int e = 256 - ((256 - p) * (256 - p)) / 256;   // ease-out
        int off = w * e / 256;
        if (tDir > 0) {
            g.drawImage(tFrom, -off / 3, 0, TL);
            g.drawImage(tTo, w - off, 0, TL);
        } else {
            g.drawImage(tTo, -(w - off) / 3, 0, TL);
            g.drawImage(tFrom, off, 0, TL);
        }
    }

    private void renderScreen(Graphics g, int s) {
        int[] t = THEMES[theme];
        if (s != S_CAM) { g.setColor(t[0]); g.fillRect(0, 0, w, h); }
        switch (s) {
            case S_HOME: drawHome(g, t); break;
            case S_NOTES: drawNotes(g, t); break;
            case S_EDIT: drawEditor(g, t); break;
            case S_ALARM: drawAlarm(g, t); break;
            case S_CAM: drawCam(g, t); break;
            case S_PHOTO: drawPhoto(g, t); break;
            case S_SET: drawSettings(g, t); break;
            case S_RING: drawRing(g, t); break;
        }
    }

    // ================= shared widgets
    private void drawHeader(Graphics g, int[] t, String title, String pill) {
        if (headImg == null) {
            try {
                Image im = Image.createImage(w, headH);
                Graphics ig = im.getGraphics();
                for (int i = 0; i < headH; i++) {
                    ig.setColor(mix(t[5], t[0], i * 130 / headH));
                    ig.drawLine(0, i, w, i);
                }
                ig.setColor(mix(t[5], t[3], 90));
                ig.drawLine(0, headH - 1, w, headH - 1);
                headImg = im;
            } catch (Throwable e) { headImg = null; }
        }
        if (headImg != null) g.drawImage(headImg, 0, 0, TL);
        else { g.setColor(t[5]); g.fillRect(0, 0, w, headH); }
        g.setFont(fHead);
        g.setColor(t[2]);
        g.drawString(title, 12, (headH - fHead.getHeight()) / 2, TL);
        if (pill != null) {
            int pw = fBold.stringWidth(pill) + 16, ph = fBold.getHeight() + 4;
            g.setColor(t[4]);
            g.fillRoundRect(w - pw - 10, (headH - ph) / 2, pw, ph, ph, ph);
            g.setFont(fBold);
            g.setColor(0xFFFFFF);
            g.drawString(pill, w - 10 - pw / 2, (headH - ph) / 2 + 2, TC);
        }
    }

    private void drawBar(Graphics g, int[] t, String l, String c, String r) {
        g.setColor(t[5]);
        g.fillRect(0, h - barH, w, barH);
        g.setColor(mix(t[5], t[3], 90));
        g.drawLine(0, h - barH, w, h - barH);
        g.setFont(fBold);
        g.setColor(t[4]);
        int y = h - barH + 5;
        g.drawString(l, 10, y, TL);
        g.drawString(r, w - 10, y, TR);
        g.setFont(fBody);
        g.setColor(t[3]);
        g.drawString(c, w / 2, y, TC);
    }

    private void drawToast(Graphics g, int[] t) {
        if (toast == null || toastOff <= 0) return;
        int tw = fBold.stringWidth(toast) + 28, th = fBold.getHeight() + 12;
        int y = h - toastOff;
        g.setColor(t[4]);
        g.fillRoundRect((w - tw) / 2, y, tw, th, th, th);
        g.setFont(fBold);
        g.setColor(0xFFFFFF);
        g.drawString(toast, w / 2, y + 6, TC);
    }

    // ================= HOME
    private void drawIcon(Graphics g, int i, int cx, int cy, int col, int bg) {
        g.setColor(col);
        if (i == 0) {
            g.fillRoundRect(cx - 11, cy - 14, 22, 28, 5, 5);
            g.setColor(bg);
            g.drawLine(cx - 6, cy - 6, cx + 6, cy - 6);
            g.drawLine(cx - 6, cy, cx + 6, cy);
            g.drawLine(cx - 6, cy + 6, cx + 2, cy + 6);
        } else if (i == 1) {
            g.fillArc(cx - 13, cy - 13, 26, 26, 0, 360);
            g.fillArc(cx - 16, cy - 16, 10, 10, 0, 360);
            g.fillArc(cx + 6, cy - 16, 10, 10, 0, 360);
            g.setColor(bg);
            g.drawLine(cx, cy, cx, cy - 8);
            g.drawLine(cx, cy, cx + 6, cy + 3);
        } else if (i == 2) {
            g.fillRoundRect(cx - 15, cy - 9, 30, 21, 6, 6);
            g.fillRect(cx - 6, cy - 13, 12, 6);
            g.setColor(bg);
            g.fillArc(cx - 7, cy - 6, 14, 14, 0, 360);
            g.setColor(col);
            g.fillArc(cx - 4, cy - 3, 8, 8, 0, 360);
        } else {
            for (int k = 0; k < 3; k++) {
                int yy = cy - 10 + k * 10;
                g.drawLine(cx - 14, yy, cx + 14, yy);
                g.drawLine(cx - 14, yy + 1, cx + 14, yy + 1);
                g.fillArc(cx - 8 + (k * 8) % 16, yy - 4, 9, 9, 0, 360);
            }
        }
    }

    private void drawHome(Graphics g, int[] t) {
        int gap = 12, tw = (w - gap * 3) / 2;
        int th = (h - headH - barH - gap * 3) / 2;
        if (th > tw) th = tw;
        for (int i = 0; i < 4; i++) {
            int x = gap + (i % 2) * (tw + gap), y = headH + gap + (i / 2) * (th + gap);
            if (i == homeSel) {
                g.setColor(HOME_COLS[i]);
                g.fillRoundRect(x - 2, y - 2, tw + 4, th + 4, 18, 18);
            }
            g.setColor(t[1]);
            g.fillRoundRect(x, y, tw, th, 16, 16);
            drawIcon(g, i, x + tw / 2, y + th / 2 - 8, HOME_COLS[i], t[1]);
            g.setFont(fBold);
            g.setColor(t[2]);
            g.drawString(HOME_NAMES[i], x + tw / 2, y + th - fBold.getHeight() - 6, TC);
        }
        drawHeader(g, t, "Modern Hub", null);
        drawBar(g, t, "Open", "", "Exit");
    }

    private void homeKey(int k) {
        if (k == KEY_POUND || isRight(k)) { midlet.exit(); return; }
        int a = action(k);
        int col = homeSel % 2, row = homeSel / 2;
        if (isLeft(k) || a == FIRE) { openHome(homeSel); return; }
        if (a == LEFT) col = 0; else if (a == RIGHT) col = 1;
        else if (a == UP) row = 0; else if (a == DOWN) row = 1;
        homeSel = row * 2 + col;
    }

    private void openHome(int i) {
        if (i == 0) { ensureVisible(); nav(S_NOTES, 1); }
        else if (i == 1) nav(S_ALARM, 1);
        else if (i == 2) nav(S_CAM, 1);
        else { setSel = 0; nav(S_SET, 1); }
    }

    // ================= NOTES
    private void drawNotes(Graphics g, int[] t) {
        int gap = 8, top = headH + gap;
        if (notes.size() == 0) {
            g.setFont(fBody);
            g.setColor(t[3]);
            g.drawString("No notes yet", w / 2, h / 2, TC);
        }
        for (int i = 0; i < notes.size(); i++) {
            int y = top + i * (cardH + gap) - listScroll;
            if (y + cardH < headH || y > h - barH) continue;
            String s = (String) notes.elementAt(i);
            int nl = s.indexOf('\n');
            String title = nl < 0 ? s : s.substring(0, nl);
            String prev = nl < 0 ? "" : s.substring(nl + 1).replace('\n', ' ').trim();
            if (title.length() == 0) title = "Untitled";
            if (prev.length() == 0) prev = "No additional text";
            if (i == sel) {
                g.setColor(t[4]);
                g.fillRoundRect(8, y - 1, w - 16, cardH + 2, 14, 14);
                g.setColor(t[1]);
                g.fillRoundRect(10, y + 1, w - 20, cardH - 2, 12, 12);
            } else {
                g.setColor(t[1]);
                g.fillRoundRect(8, y, w - 16, cardH, 14, 14);
            }
            g.setFont(fBold);
            g.setColor(t[2]);
            g.drawString(fit(title, fBold, w - 40), 20, y + 6, TL);
            g.setFont(fBody);
            g
