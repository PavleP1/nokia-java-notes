import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.Vector;

public class ModernNotes extends MIDlet {
    private NotesCanvas canvas;

    public void startApp() {
        if (canvas == null) {
            canvas = new NotesCanvas(this);
            Display.getDisplay(this).setCurrent(canvas);
            canvas.start();
        }
    }
    public void pauseApp() { if (canvas != null) canvas.stop(false); }
    public void destroyApp(boolean unconditional) { if (canvas != null) canvas.stop(true); }
    void exit() { destroyApp(true); notifyDestroyed(); }
}

class NotesCanvas extends Canvas implements Runnable {
    static final int MAX_LEN = 2000;
    static final int TL = Graphics.TOP | Graphics.LEFT;
    static final String[] KEYS = {" 0", ".,?!'-@1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    static final String[] MODES = {"abc", "Abc", "ABC", "123"};
    static final String[] THEME_NAMES = {"Dark", "Light", "Ocean"};
    // bg, card, text, subtext, accent, bar
    static final int[][] THEMES = {
        {0x121214, 0x1E1E22, 0xFFFFFF, 0x8E8E93, 0x0A84FF, 0x1C1C1E},
        {0xF2F2F7, 0xFFFFFF, 0x000000, 0x6C6C70, 0x007AFF, 0xE5E5EA},
        {0x0B1B2B, 0x13293D, 0xE8F1F8, 0x7F9BB3, 0x2EC4B6, 0x102235}
    };

    private final ModernNotes midlet;
    private volatile boolean running = true;
    private final Font fBody = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fBold = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private final Font fHead = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);

    private int w, h, headH, barH, cardH;
    private int theme;
    private final Vector notes = new Vector();
    private int sel, listScroll;

    private boolean editing;
    private int slide, target;          // integer easing, no floats (CLDC 1.0 safe)
    private int editIndex = -1;
    private StringBuffer buf = new StringBuffer();
    private Vector lines;
    private int titleLines, edScroll;

    private int mode = 1, lastKey = -1, keyIdx;
    private long lastTime;
    private boolean curUpper;

    private String toast;
    private long toastTime;
    private int toastOff;
    private boolean blink = true;
    private long blinkTime;
    private boolean delArmed;
    private long delTime;

    NotesCanvas(ModernNotes m) {
        midlet = m;
        setFullScreenMode(true);
        w = getWidth();
        h = getHeight();
        headH = fHead.getHeight() + 14;
        barH = fBody.getHeight() + 10;
        cardH = fBold.getHeight() + fBody.getHeight() + 16;
        load();
    }

    void start() { new Thread(this).start(); }

    void stop(boolean exiting) {
        if (exiting) running = false;
        if (editing) commit();
        save();
    }

    protected void sizeChanged(int nw, int nh) { w = nw; h = nh; lines = null; }

    // ---------------- animation loop: only repaints when something changes
    public void run() {
        while (running) {
            long now = System.currentTimeMillis();
            boolean dirty = false, anim = false;

            if (slide != target) {
                int d = target - slide, s = d / 3;
                if (s == 0) s = d > 0 ? 1 : -1;
                slide += s;
                dirty = anim = true;
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
            if (editing && slide == target && now - blinkTime > 500) {
                blink = !blink;
                blinkTime = now;
                dirty = true;
            }
            if (dirty) repaint();
            try { Thread.sleep(anim ? 35 : 100); } catch (InterruptedException e) {}
        }
    }

    private void showToast(String m) { toast = m; toastTime = System.currentTimeMillis(); }

    // ---------------- persistence (RMS)
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
        if (notes.size() == 0) {
            notes.addElement("Welcome to Modern Notes\nLeft softkey: new note\nRight softkey: delete\n* changes theme, # exits.\nNotes are saved automatically.");
        }
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

    // ---------------- helpers
    private static int mix(int a, int b, int p) {
        int r = (((a >> 16) & 255) * (255 - p) + ((b >> 16) & 255) * p) / 255;
        int g = (((a >> 8) & 255) * (255 - p) + ((b >> 8) & 255) * p) / 255;
        int bl = ((a & 255) * (255 - p) + (b & 255) * p) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    private String fit(String s, Font f, int maxW) {
        if (f.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.stringWidth(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private int action(int k) {
        try { return getGameAction(k); } catch (Exception e) { return 0; }
    }
    private boolean isLeft(int k) { return k == -6 || k == -21; }
    private boolean isRight(int k) { return k == -7 || k == -22; }
    private boolean isClear(int k) { return k == -8; }

    // ---------------- rendering
    protected void paint(Graphics g) {
        int nw = getWidth(), nh = getHeight();
        if (nw != w || nh != h) { w = nw; h = nh; lines = null; }
        int[] t = THEMES[theme];
        g.setColor(t[0]);
        g.fillRect(0, 0, w, h);

        int sl = slide;
        if (sl < w) { g.translate(-sl, 0); drawList(g, t); g.translate(sl, 0); }
        if (sl > 0) { g.translate(w - sl, 0); drawEditor(g, t); g.translate(sl - w, 0); }
        drawToast(g, t);
    }

    private void drawHeader(Graphics g, int[] t, String title, String pill) {
        for (int i = 0; i < headH; i++) {            // fake frosted-glass gradient
            g.setColor(mix(t[5], t[0], i * 130 / headH));
            g.drawLine(0, i, w, i);
        }
        g.setColor(mix(t[5], t[3], 90));
        g.drawLine(0, headH - 1, w, headH - 1);
        g.setFont(fHead);
        g.setColor(t[2]);
        g.drawString(title, 12, (headH - fHead.getHeight()) / 2, TL);
        int pw = fBold.stringWidth(pill) + 16, ph = fBold.getHeight() + 4;
        g.setColor(t[4]);
        g.fillRoundRect(w - pw - 10, (headH - ph) / 2, pw, ph, ph, ph);
        g.setFont(fBold);
        g.setColor(0xFFFFFF);
        g.drawString(pill, w - 10 - pw / 2, (headH - ph) / 2 + 2, Graphics.TOP | Graphics.HCENTER);
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
        g.drawString(r, w - 10, y, Graphics.TOP | Graphics.RIGHT);
        g.setFont(fBody);
        g.setColor(t[3]);
        g.drawString(c, w / 2, y, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawList(Graphics g, int[] t) {
        int gap = 8, top = headH + gap;
        if (notes.size() == 0) {
            g.setFont(fBody);
            g.setColor(t[3]);
            g.drawString("No notes yet", w / 2, h / 2, Graphics.TOP | Graphics.HCENTER);
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
            boolean on = (i == sel);
            if (on) {
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
        drawBar(g, t, "New", "* Theme  # Exit", "Delete");
    }

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
        g.drawString(buf.length() + "/" + MAX_LEN, w - 10, h - barH - fBody.getHeight() - 3, Graphics.TOP | Graphics.RIGHT);
        drawHeader(g, t, "Edit", MODES[mode]);
        drawBar(g, t, "Save", "# Mode  * Enter", "Del");
    }

    private void drawToast(Graphics g, int[] t) {
        if (toast == null || toastOff <= 0) return;
        int tw = fBold.stringWidth(toast) + 28, th = fBold.getHeight() + 12;
        int y = h - toastOff;
        g.setColor(t[4]);
        g.fillRoundRect((w - tw) / 2, y, tw, th, th, th);
        g.setFont(fBold);
        g.setColor(0xFFFFFF);
        g.drawString(toast, w / 2, y + 6, Graphics.TOP | Graphics.HCENTER);
    }

    // word wrap; first logical line is the note title (bold)
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

    // ---------------- notes logic
    private void openNote(int idx) {
        editIndex = idx;
        buf = new StringBuffer(idx >= 0 ? (String) notes.elementAt(idx) : "");
        mode = idx >= 0 ? 0 : 1;
        lastKey = -1;
        textChanged();
        editing = true;
        target = w;
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
        editing = false;
        target = 0;
        ensureVisible();
        showToast("Saved");
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
            showToast("Press Delete again");
        }
    }

    private void ensureVisible() {
        int gap = 8, view = h - barH - headH - gap * 2;
        int selY = sel * (cardH + gap);
        if (selY < listScroll) listScroll = selY;
        if (selY + cardH > listScroll + view) listScroll = selY + cardH - view;
        if (listScroll < 0) listScroll = 0;
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

    // multi-tap text entry
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

    // ---------------- input
    protected void keyPressed(int k) {
        if (slide != target) return;
        if (editing) editorKey(k); else listKey(k);
        repaint();
    }

    protected void keyRepeated(int k) {
        int a = action(k);
        if (a == UP || a == DOWN || isClear(k) || isRight(k)) keyPressed(k);
    }

    private void listKey(int k) {
        if (k == KEY_POUND) { midlet.exit(); return; }
        if (k == KEY_STAR) {
            theme = (theme + 1) % THEMES.length;
            save();
            showToast("Theme: " + THEME_NAMES[theme]);
            return;
        }
        if (isLeft(k)) { openNote(-1); return; }
        if (isRight(k) || isClear(k)) { deleteSel(); return; }
        int a = action(k);
        if (a == UP && sel > 0) { sel--; delArmed = false; }
        else if (a == DOWN && sel < notes.size() - 1) { sel++; delArmed = false; }
        else if (a == FIRE) { openNote(notes.size() > 0 ? sel : -1); return; }
        ensureVisible();
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
          }
