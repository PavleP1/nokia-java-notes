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
            g.setColor(t[3]);
            g.drawString(fit(prev, fBody, w - 40), 20, y + 8 + fBold.getHeight(), TL);
        }
        drawHeader(g, t, "Notes", notes.size() + "");
        drawBar(g, t, "New", "C/0 Delete", "Back");
    }

    private void notesKey(int k) {
        if (isLeft(k)) { openNote(-1); return; }
        if (isRight(k)) { nav(S_HOME, -1); return; }
        if (isClear(k) || k == KEY_NUM0) { deleteSel(); return; }
        int a = action(k);
        if (a == UP && sel > 0) { sel--; delArmed = false; }
        else if (a == DOWN && sel < notes.size() - 1) { sel++; delArmed = false; }
        else if (a == FIRE) { openNote(notes.size() > 0 ? sel : -1); return; }
        ensureVisible();
    }

    private void ensureVisible() {
        int gap = 8, view = h - barH - headH - gap * 2;
        int selY = sel * (cardH + gap);
        if (selY < listScroll) listScroll = selY;
        if (selY + cardH > listScroll + view) listScroll = selY + cardH - view;
        if (listScroll < 0) listScroll = 0;
    }

    private void deleteSel() {
        if (notes.size() == 0) return;
        long now = System.currentTimeMillis();
        if (delArmed && now - delTime < 3000) {
            notes.removeElementAt(sel);
            if (sel >= notes.size()) sel = notes.size() - 1;
            if (sel < 0) sel = 0;
            delArmed = false;
            save();
            ensureVisible();
            showToast("Deleted");
        } else {
            delArmed = true;
            delTime = now;
            showToast("Press again to delete");
        }
    }

    // ================= EDITOR
    private void drawEditor(Graphics g, int[] t) {
        if (lines == null) rewrap();
        int lh = fBody.getHeight() + 3, top = headH + 8;
        int bottom = h - barH - fBody.getHeight() - 8;
        int vis = (bottom - top) / lh, n = lines.size();
        int maxScroll = n > vis ? n - vis : 0;
        if (edScroll > maxScroll) edScroll = maxScroll;
        if (edScroll < 0) edScroll = 0;
        int y = top;
        for (int i = edScroll; i < n && i < edScroll + vis; i++) {
            g.setFont(i < titleLines ? fBold : fBody);
            g.setColor(t[2]);
            g.drawString((String) lines.elementAt(i), 12, y, TL);
            y += lh;
        }
        int last = n - 1;
        if (blink && last >= edScroll && last < edScroll + vis) {
            Font f = last < titleLines ? fBold : fBody;
            int cx = 12 + f.stringWidth((String) lines.elementAt(last));
            g.setColor(t[4]);
            g.fillRect(cx + 1, top + (last - edScroll) * lh, 2, fBody.getHeight());
        }
        g.setFont(fBody);
        g.setColor(t[3]);
        g.drawString(buf.length() + "/" + MAX_LEN, w - 10, h - barH - fBody.getHeight() - 3, TR);
        drawHeader(g, t, "Edit", MODES[mode]);
        drawBar(g, t, "Save", "# Mode  * Enter", "Del");
    }

    private void rewrap() {
        lines = new Vector();
        String s = buf.toString();
        Font f = fBold;
        int start = 0, lastSpace = -1, maxW = w - 24;
        boolean seenNl = false;
        titleLines = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                lines.addElement(s.substring(start, i));
                start = i + 1;
                lastSpace = -1;
                if (!seenNl) { seenNl = true; titleLines = lines.size(); f = fBody; }
            } else {
                if (c == ' ') lastSpace = i;
                if (f.substringWidth(s, start, i - start + 1) > maxW) {
                    int brk = lastSpace > start ? lastSpace + 1 : (i > start ? i : i + 1);
                    lines.addElement(s.substring(start, brk));
                    start = brk;
                    lastSpace = -1;
                }
            }
        }
        lines.addElement(s.substring(start));
        if (!seenNl) titleLines = lines.size();
    }

    private void openNote(int idx) {
        editIndex = idx;
        buf = new StringBuffer(idx >= 0 ? (String) notes.elementAt(idx) : "");
        mode = idx >= 0 ? 0 : 1;
        lastKey = -1;
        textChanged();
        nav(S_EDIT, 1);
    }

    private void commit() {
        String s = buf.toString();
        if (s.trim().length() == 0) {
            if (editIndex >= 0) notes.removeElementAt(editIndex);
            editIndex = -1;
        } else if (editIndex >= 0) {
            notes.setElementAt(s, editIndex);
        } else {
            notes.insertElementAt(s, 0);
            editIndex = 0;
        }
        if (editIndex >= 0) sel = editIndex;
        if (sel >= notes.size()) sel = notes.size() - 1;
        if (sel < 0) sel = 0;
    }

    private void saveNote() {
        commit();
        save();
        ensureVisible();
        nav(S_NOTES, -1);
        showToast("Saved");
    }

    private void textChanged() {
        lines = null;
        edScroll = 100000;
        blink = true;
        blinkTime = System.currentTimeMillis();
    }

    private void addChar(char c) {
        if (buf.length() >= MAX_LEN) return;
        buf.append(c);
        lastKey = -1;
        textChanged();
    }

    private void backspace() {
        if (buf.length() > 0) buf.setLength(buf.length() - 1);
        lastKey = -1;
        textChanged();
    }

    private void typeKey(int n) {
        if (mode == 3) { addChar((char) ('0' + n)); return; }
        String set = KEYS[n];
        long now = System.currentTimeMillis();
        if (n == lastKey && now - lastTime < 900 && buf.length() > 0) {
            keyIdx = (keyIdx + 1) % set.length();
            char c = set.charAt(keyIdx);
            buf.setCharAt(buf.length() - 1, curUpper ? Character.toUpperCase(c) : c);
        } else {
            if (buf.length() >= MAX_LEN) return;
            keyIdx = 0;
            curUpper = (mode == 1 || mode == 2);
            char c = set.charAt(0);
            buf.append(curUpper ? Character.toUpperCase(c) : c);
            if (mode == 1) mode = 0;
        }
        lastKey = n;
        lastTime = now;
        textChanged();
    }

    private void editorKey(int k) {
        if (k >= KEY_NUM0 && k <= KEY_NUM9) { typeKey(k - KEY_NUM0); return; }
        if (k == KEY_POUND) { mode = (mode + 1) % 4; lastKey = -1; return; }
        if (k == KEY_STAR) { addChar('\n'); return; }
        if (isLeft(k)) { saveNote(); return; }
        if (isRight(k) || isClear(k)) { backspace(); return; }
        int a = action(k);
        if (a == FIRE) addChar('\n');
        else if (a == UP && edScroll > 0) edScroll--;
        else if (a == DOWN) edScroll++;
    }
        // ================= ALARM
    private void drawAlarm(Graphics g, int[] t) {
        int bw = fBig.stringWidth("00") + 20, bh = fBig.getHeight() + 16, sepW = fBig.stringWidth(":") + 10;
        int x0 = (w - (bw * 2 + sepW)) / 2, y0 = headH + 24;
        for (int i = 0; i < 2; i++) {
            int x = x0 + i * (bw + sepW);
            if (alField == i) {
                g.setColor(t[4]);
                g.fillRoundRect(x - 2, y0 - 2, bw + 4, bh + 4, 16, 16);
            }
            g.setColor(t[1]);
            g.fillRoundRect(x, y0, bw, bh, 14, 14);
            g.setFont(fBig);
            g.setColor(t[2]);
            g.drawString(two(i == 0 ? alH : alM), x + bw / 2, y0 + 8, TC);
        }
        g.setFont(fBig);
        g.setColor(t[2]);
        g.drawString(":", x0 + bw + sepW / 2, y0 + 8, TC);

        int py = y0 + bh + 20, ph = fBold.getHeight() + 14;
        for (int i = 0; i < 2; i++) {
            int y = py + i * (ph + 8);
            boolean on = (i == 0) ? alOn : alDaily;
            if (alField == i + 2) {
                g.setColor(t[4]);
                g.fillRoundRect(10, y - 2, w - 20, ph + 4, 14, 14);
            }
            g.setColor(t[1]);
            g.fillRoundRect(12, y, w - 24, ph, 12, 12);
            g.setFont(fBold);
            g.setColor(t[2]);
            g.drawString(i == 0 ? "Alarm" : "Repeat daily", 22, y + 7, TL);
            g.setColor(on ? 0x30D158 : t[3]);
            g.drawString(on ? "ON" : "OFF", w - 22, y + 7, TR);
        }
        g.setFont(fBody);
        g.setColor(t[3]);
        String info = "Use 2/8 to change, 4/6 to move";
        if (alOn && alTime > 0) {
            long m = (alTime - System.currentTimeMillis()) / 60000L;
            if (m < 0) m = 0;
            info = "Rings in " + (m / 60) + "h " + (m % 60) + "m";
        }
        g.drawString(info, w / 2, py + 2 * (ph + 8) + 6, TC);
        drawHeader(g, t, "Alarm", alOn ? "ON" : "OFF");
        drawBar(g, t, "Save", "", "Back");
    }

    private void alarmKey(int k) {
        if (isLeft(k) || isRight(k)) { leaveAlarm(); return; }
        int a = action(k);
        if (a == LEFT && alField > 0) alField--;
        else if (a == RIGHT && alField < 3) alField++;
        else if (a == UP || a == DOWN) {
            int d = (a == UP) ? 1 : -1;
            if (alField == 0) alH = (alH + d + 24) % 24;
            else if (alField == 1) alM = (alM + d + 60) % 60;
            else if (alField == 2) alOn = !alOn;
            else alDaily = !alDaily;
        } else if (a == FIRE) {
            if (alField == 2) alOn = !alOn;
            else if (alField == 3) alDaily = !alDaily;
        }
    }

    private void leaveAlarm() {
        armAlarm(true);
        nav(S_HOME, -1);
    }

    private void armAlarm(boolean announce) {
        if (alOn) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, alH);
            c.set(Calendar.MINUTE, alM);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long tm = c.getTime().getTime();
            if (tm <= System.currentTimeMillis()) tm += 86400000L;
            alTime = tm;
            try { PushRegistry.registerAlarm("ModernNotes", tm); }
            catch (Throwable e) { showToast("Keep app open for alarm"); announce = false; }
            if (announce) showToast("Alarm set " + two(alH) + ":" + two(alM));
        } else {
            alTime = 0;
            try { PushRegistry.registerAlarm("ModernNotes", 0L); } catch (Throwable e) {}
            if (announce) showToast("Alarm off");
        }
        saveAlarm();
    }

    private void ring() {
        ringing = true;
        ringStart = System.currentTimeMillis();
        ringLast = 0;
        trans = false;
        camStop();
        screen = S_RING;
        try { disp.setCurrent(this); } catch (Throwable e) {}
        repaint();
    }

    private void dismiss(boolean snooze) {
        ringing = false;
        long now = System.currentTimeMillis();
        if (snooze) {
            alTime = now + 300000L;
            try { PushRegistry.registerAlarm("ModernNotes", alTime); } catch (Throwable e) {}
            saveAlarm();
        } else if (alDaily) {
            armAlarm(false);
        } else {
            alOn = false;
            armAlarm(false);
        }
        screen = S_HOME;
        repaint();
    }

    private void drawRing(Graphics g, int[] t) {
        boolean on = ((System.currentTimeMillis() / 500L) & 1L) == 0L;
        g.setColor(on ? 0xD32F2F : 0x7F1D1D);
        g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        g.setFont(fBig);
        g.drawString("ALARM!", w / 2, h / 3, TC);
        g.drawString(two(alH) + ":" + two(alM), w / 2, h / 3 + fBig.getHeight() + 8, TC);
        drawBar(g, t, "Snooze 5m", "Any key: stop", "Stop");
    }

    private void ringKey(int k) {
        dismiss(isLeft(k));
    }

    // ================= CAMERA
    private void camStart() {
        camStop();
        try {
            player = Manager.createPlayer("capture://video");
            player.realize();
            Control c = player.getControl("VideoControl");
            if (c == null) c = player.getControl("javax.microedition.media.control.VideoControl");
            vc = (VideoControl) c;
            vc.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, this);
            vc.setDisplayLocation(0, headH);
            vc.setDisplaySize(w, h - headH - barH);
            vc.setVisible(true);
            player.start();
            camOk = true;
            camErr = null;
        } catch (Throwable e) {
            camStop();
            camErr = "Camera unavailable";
        }
    }

    private void camStop() {
        camOk = false;
        try { if (vc != null) vc.setVisible(false); } catch (Throwable e) {}
        try { if (player != null) { player.stop(); player.close(); } } catch (Throwable e) {}
        player = null;
        vc = null;
    }

    private void drawCam(Graphics g, int[] t) {
        if (!camOk) {
            g.setColor(0x000000);
            g.fillRect(0, headH, w, h - headH - barH);
            g.setFont(fBody);
            g.setColor(0xFFFFFF);
            g.drawString(camErr != null ? camErr : "Starting camera...", w / 2, h / 2, TC);
        }
        drawHeader(g, t, "Camera", "LIVE");
        drawBar(g, t, "Snap", "5 = Snap", "Back");
    }

    private void camKey(int k) {
        if (isRight(k)) { nav(S_HOME, -1); return; }
        if (isLeft(k) || k == KEY_NUM5 || action(k) == FIRE) takePhoto();
    }

    private void takePhoto() {
        if (!camOk || vc == null) return;
        String[] opts = {"encoding=jpeg&width=240&height=180", "encoding=png&width=240&height=180", null};
        byte[] raw = null;
        for (int i = 0; i < opts.length && raw == null; i++) {
            try { raw = vc.getSnapshot(opts[i]); } catch (Throwable e) { raw = null; }
        }
        if (raw == null) { showToast("Capture failed"); return; }
        try {
            photo = Image.createImage(raw, 0, raw.length);
        } catch (Throwable e) { showToast("Capture failed"); return; }
        raw = null;
        fIdx = 0;
        filtered = photo;
        nav(S_PHOTO, 1);
    }

    private static int px(int p, int f) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int l = (r * 30 + g * 59 + b * 11) / 100;
        switch (f) {
            case 1: r = l; g = l; b = l; break;
            case 2: {
                int nr = (r * 393 + g * 769 + b * 189) / 1000;
                int ng = (r * 349 + g * 686 + b * 168) / 1000;
                int nb = (r * 272 + g * 534 + b * 131) / 1000;
                r = nr; g = ng; b = nb;
                break;
            }
            case 3: r = 255 - r; g = 255 - g; b = 255 - b; break;
            case 4: r += 26; b -= 26; break;
            case 5: r -= 26; b += 26; break;
            case 6: r = l + (r - l) * 16 / 10; g = l + (g - l) * 16 / 10; b = l + (b - l) * 16 / 10; break;
            case 7: r = r * 180 / 255 + 50; g = g * 180 / 255 + 50; b = b * 180 / 255 + 50; break;
        }
        return 0xFF000000 | (cl(r) << 16) | (cl(g) << 8) | cl(b);
    }

    private Image filter(Image src, int f) {
        if (f == 0) return src;
        int iw = src.getWidth(), ih = src.getHeight();
        Image out = Image.createImage(iw, ih);
        Graphics og = out.getGraphics();
        int[] row = new int[iw];
        for (int y = 0; y < ih; y++) {
            src.getRGB(row, 0, iw, 0, y, iw, 1);
            for (int x = 0; x < iw; x++) row[x] = px(row[x], f);
            og.drawRGB(row, 0, iw, 0, y, iw, 1, false);
        }
        return out;
    }

    private void drawPhoto(Graphics g, int[] t) {
        g.setColor(0x000000);
        g.fillRect(0, headH, w, h - headH - barH);
        if (filtered != null) {
            int ah = h - headH - barH;
            g.setClip(0, headH, w, ah);
            g.drawImage(filtered, (w - filtered.getWidth()) / 2, headH + (ah - filtered.getHeight()) / 2, TL);
            g.setClip(0, 0, w, h);
        }
        drawHeader(g, t, "Photo", FILTERS[fIdx]);
        drawBar(g, t, "Save", "< 4   6 >  Filter", "Retake");
    }

    private void photoKey(int k) {
        if (isLeft(k)) { savePhoto(); return; }
        if (isRight(k)) { nav(S_CAM, -1); return; }
        int a = action(k);
        if (a == LEFT || a == RIGHT) {
            fIdx = (fIdx + (a == RIGHT ? 1 : FILTERS.length - 1)) % FILTERS.length;
            try { filtered = filter(photo, fIdx); } catch (Throwable e) { filtered = photo; }
        }
    }

    // minimal PNG writer (uncompressed deflate) so filtered photos can be saved
    private int pcrc;
    private static int[] crcTab;

    private void pBegin(OutputStream o, String type, int len) throws IOException {
        wInt(o, len);
        pcrc = -1;
        byte[] tb = new byte[4];
        for (int i = 0; i < 4; i++) tb[i] = (byte) type.charAt(i);
        pPut(o, tb, 0, 4);
    }
    private void pPut(OutputStream o, byte[] b, int off, int len) throws IOException {
        o.write(b, off, len);
        if (crcTab == null) {
            crcTab = new int[256];
            for (int n = 0; n < 256; n++) {
                int c = n;
                for (int k = 0; k < 8; k++) c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
                crcTab[n] = c;
            }
        }
        int c2 = pcrc;
        for (int i = 0; i < len; i++) c2 = crcTab[(c2 ^ b[off + i]) & 255] ^ (c2 >>> 8);
        pcrc = c2;
    }
    private void pEnd(OutputStream o) throws IOException { wInt(o, ~pcrc); }
    private static void wInt(OutputStream o, int v) throws IOException {
        o.write((v >>> 24) & 255); o.write((v >>> 16) & 255); o.write((v >>> 8) & 255); o.write(v & 255);
    }

    private void writePng(Image img, OutputStream o) throws IOException {
        int iw = img.getWidth(), ih = img.getHeight();
        byte[] sig = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        o.write(sig, 0, 8);
        byte[] hd = new byte[13];
        hd[0] = (byte) (iw >> 24); hd[1] = (byte) (iw >> 16); hd[2] = (byte) (iw >> 8); hd[3] = (byte) iw;
        hd[4] = (byte) (ih >> 24); hd[5] = (byte) (ih >> 16); hd[6] = (byte) (ih >> 8); hd[7] = (byte) ih;
        hd[8] = 8; hd[9] = 2;
        pBegin(o, "IHDR", 13); pPut(o, hd, 0, 13); pEnd(o);
        int rb = 1 + 3 * iw;
        pBegin(o, "IDAT", 2 + ih * (5 + rb) + 4);
        byte[] zh = {0x78, 0x01};
        pPut(o, zh, 0, 2);
        int a = 1, b = 0;
        int[] pix = new int[iw];
        byte[] row = new byte[rb];
        byte[] bh = new byte[5];
        for (int y = 0; y < ih; y++) {
            img.getRGB(pix, 0, iw, 0, y, iw, 1);
            row[0] = 0;
            for (int x = 0; x < iw; x++) {
                int p = pix[x];
                row[1 + 3 * x] = (byte) (p >> 16);
                row[2 + 3 * x] = (byte) (p >> 8);
                row[3 + 3 * x] = (byte) p;
            }
            bh[0] = (byte) (y == ih - 1 ? 1 : 0);
            bh[1] = (byte) (rb & 255); bh[2] = (byte) ((rb >> 8) & 255);
            bh[3] = (byte) (~rb & 255); bh[4] = (byte) ((~rb >> 8) & 255);
            pPut(o, bh, 0, 5);
            pPut(o, row, 0, rb);
            for (int i = 0; i < rb; i++) {
                a = (a + (row[i] & 255)) % 65521;
                b = (b + a) % 65521;
            }
        }
        byte[] ad = {(byte) (b >> 8), (byte) b, (byte) (a >> 8), (byte) a};
        pPut(o, ad, 0, 4);
        pEnd(o);
        pBegin(o, "IEND", 0); pEnd(o);
    }

    private void savePhoto() {
        FileConnection fc = null;
        OutputStream os = null;
        try {
            String dir = System.getProperty("fileconn.dir.photos");
            if (dir == null || dir.length() == 0) dir = "file:///E:/";
            String name = "MN" + (System.currentTimeMillis() / 1000L) + ".png";
            fc = (FileConnection) Connector.open(dir + name, Connector.READ_WRITE);
            if (!fc.exists()) fc.create();
            os = fc.openOutputStream();
            writePng(filtered, os);
            os.flush();
            showToast("Saved " + name);
        } catch (Throwable e) {
            showToast("Save failed");
        } finally {
            try { if (os != null) os.close(); } catch (Throwable e) {}
            try { if (fc != null) fc.close(); } catch (Throwable e) {}
        }
    }

    // ================= SETTINGS
    private String ver() {
        String v = midlet.getAppProperty("MIDlet-Version");
        return v == null ? "1.1" : v;
    }

    private void drawSettings(Graphics g, int[] t) {
        String[] a = {"Theme", "Update from web", "About"};
        String[] b = {THEME_NAMES[theme], UPDATE_URL.length() == 0 ? "Set UPDATE_URL in code" : "Opens installer", "Modern Hub v" + ver()};
        for (int i = 0; i < 3; i++) {
            int y = headH + 8 + i * (cardH + 8);
            if (i == setSel) {
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
            g.drawString(a[i], 20, y + 6, TL);
            g.setFont(fBody);
            g.setColor(t[3]);
            g.drawString(fit(b[i], fBody, w - 40), 20, y + 8 + fBold.getHeight(), TL);
        }
        drawHeader(g, t, "Settings", null);
        drawBar(g, t, "Select", "", "Back");
    }

    private void setKey(int k) {
        if (isRight(k)) { nav(S_HOME, -1); return; }
        int a = action(k);
        if (a == UP && setSel > 0) setSel--;
        else if (a == DOWN && setSel < 2) setSel++;
        else if (isLeft(k) || a == FIRE) {
            if (setSel == 0) {
                theme = (theme + 1) % THEMES.length;
                headImg = null;
                save();
                showToast("Theme: " + THEME_NAMES[theme]);
            } else if (setSel == 1) {
                if (UPDATE_URL.length() == 0) showToast("Open new .jar to update");
                else if (!midlet.openUrl(UPDATE_URL)) showToast("Update failed");
            } else {
                showToast("Version " + ver());
            }
        }
    }

    // ================= input dispatch
    protected void keyPressed(int k) {
        if (trans) return;
        switch (screen) {
            case S_HOME: homeKey(k); break;
            case S_NOTES: notesKey(k); break;
            case S_EDIT: editorKey(k); break;
            case S_ALARM: alarmKey(k); break;
            case S_CAM: camKey(k); break;
            case S_PHOTO: photoKey(k); break;
            case S_SET: setKey(k); break;
            case S_RING: ringKey(k); break;
        }
        repaint();
    }

    protected void keyRepeated(int k) {
        int a = action(k);
        if (a == UP || a == DOWN || isClear(k) || isRight(k)) {
            if (screen == S_EDIT || screen == S_NOTES || screen == S_ALARM) keyPressed(k);
        }
    }
}
