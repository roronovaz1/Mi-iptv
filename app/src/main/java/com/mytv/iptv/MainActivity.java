package com.mytv.iptv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import android.util.Base64;
import java.util.*;

public class MainActivity extends Activity {
    static final String BASE = "https://iptv-org.github.io/iptv/";
    static final String UA = "Mozilla/5.0 (Linux; Android) MiddyaIPTV";
    // Edit to add/remove playlists (any iptv-org .m3u path works)
    static final String[][] SOURCES = {
        {"All channels (search)", "index.m3u"}, {"Bengali", "languages/ben.m3u"},
        {"India", "countries/in.m3u"}, {"Bangladesh", "countries/bd.m3u"},
        {"News", "categories/news.m3u"}, {"Sports", "categories/sports.m3u"},
        {"Movies", "categories/movies.m3u"}, {"Music", "categories/music.m3u"},
        {"Kids", "categories/kids.m3u"}, {"Entertainment", "categories/entertainment.m3u"}
    };
    static final int[] COLORS = {0xFF4F8CFF, 0xFF8B5CF6, 0xFF06B6D4, 0xFFF59E0B,
                                 0xFFEF4444, 0xFF10B981, 0xFFEC4899};

    static class Row { final String name, url; Row(String n, String u) { name = n; url = u; } }

    ExoPlayer player;
    ListView list;
    TextView title, sub, now;
    EditText search;
    View panel, spinner;
    BaseAdapter adapter;
    final List<Row> all = new ArrayList<>(), shown = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean inChannels = false, hasVideo = false;
    String listName = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1); } catch (Throwable t) { }
        installTrust();
        setContentView(R.layout.activity_main);
        panel = findViewById(R.id.panel);
        spinner = findViewById(R.id.spinner);
        title = findViewById(R.id.title);
        sub = findViewById(R.id.sub);
        now = findViewById(R.id.now);
        search = findViewById(R.id.search);
        list = findViewById(R.id.list);

        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item, p, false);
                String name = shown.get(i).name;
                TextView av = v.findViewById(R.id.avatar);
                String ch = "•";
                for (int k = 0; k < name.length(); k++)
                    if (Character.isLetterOrDigit(name.charAt(k))) { ch = name.substring(k, k + 1).toUpperCase(); break; }
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(COLORS[Math.abs(name.hashCode()) % COLORS.length]);
                av.setBackground(g);
                av.setText(ch);
                ((TextView) v.findViewById(R.id.label)).setText(name);
                return v;
            }
        };
        list.setAdapter(adapter);

        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory().setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(12000);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(f)).build();
        ((PlayerView) findViewById(R.id.player)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                spinner.setVisibility(s == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }
            @Override public void onPlayerError(PlaybackException e) {
                spinner.setVisibility(View.GONE);
                showPanel();
                sub.setText("Can't play this channel (" + e.getErrorCodeName() + "). Try another.");
            }
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            if (!inChannels) load(pos); else play(shown.get(pos));
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        search.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                list.requestFocus();
                return true;
            }
            return false;
        });
        showSources();
    }

    // Old TVs lack newer root certificates: trust system roots + a bundled Mozilla CA list.
    void installTrust() {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null, null);
                for (Enumeration<String> en = sys.aliases(); en.hasMoreElements();) {
                    String a = en.nextElement();
                    java.security.cert.Certificate c = sys.getCertificate(a);
                    if (c != null) ks.setCertificateEntry("s_" + a, c);
                }
            } catch (Exception ignored) { }
            int id = getResources().getIdentifier("cacert", "raw", getPackageName());
            if (id != 0) {
                InputStream in = getResources().openRawResource(id);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                String pem = new String(bo.toByteArray(), "US-ASCII");
                String B = "-----BEGIN CERTIFICATE-----", E = "-----END CERTIFICATE-----";
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                int pos = 0, i = 0;
                while (true) {
                    int s = pem.indexOf(B, pos); if (s < 0) break;
                    int e = pem.indexOf(E, s); if (e < 0) break;
                    String b64 = pem.substring(s + B.length(), e).replaceAll("\\s", "");
                    pos = e + E.length();
                    try {
                        ks.setCertificateEntry("b_" + (i++), cf.generateCertificate(
                            new ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))));
                    } catch (Exception ignored) { }
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        } catch (Throwable ignored) { }
    }

    void showSources() {
        inChannels = false;
        shown.clear();
        for (String[] s : SOURCES) shown.add(new Row(s[0], s[1]));
        adapter.notifyDataSetChanged();
        search.setVisibility(View.GONE);
        sub.setText("Choose a list");
        showPanel();
        list.setSelection(0);
    }

    void filter(String q) {
        if (!inChannels) return;
        q = q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Row r : all) if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(r);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        sub.setText(listName + " • " + shown.size() + " channels");
    }

    void load(int idx) {
        final String name = SOURCES[idx][0], url = BASE + SOURCES[idx][1];
        sub.setText("Loading " + name + "…");
        spinner.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<Row> rows = null; String err = null;
            try { rows = fetch(url); }
            catch (Exception e) {
                String m = e.getMessage();
                err = e.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 90 ? m.substring(0, 90) : m));
            }
            final List<Row> res = rows; final String er = err;
            ui.post(() -> {
                spinner.setVisibility(View.GONE);
                if (res == null || res.isEmpty()) {
                    sub.setText("Couldn't load list. " + (er == null ? "Empty list." : er));
                    return;
                }
                inChannels = true;
                listName = name;
                all.clear(); all.addAll(res);
                search.setText("");
                search.setVisibility(View.VISIBLE);
                filter("");
                list.requestFocus();
            });
        }).start();
    }

    static List<Row> fetch(String url) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(40000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Bad redirect");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            List<Row> out = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line, cur = null;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTINF")) {
                    int k = line.lastIndexOf(',');
                    cur = k >= 0 ? line.substring(k + 1).trim() : "Channel";
                } else if (!line.isEmpty() && !line.startsWith("#") && cur != null) {
                    out.add(new Row(cur, line)); cur = null;
                }
            }
            r.close();
            return out;
        }
        throw new IOException("Too many redirects");
    }

    void play(Row r) {
        player.setMediaItem(MediaItem.fromUri(r.url));
        player.prepare();
        player.setPlayWhenReady(true);
        hasVideo = true;
        panel.setVisibility(View.GONE);
        now.setText(r.name);
        now.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideNow);
        ui.postDelayed(hideNow, 4000);
    }

    final Runnable hideNow = () -> now.setVisibility(View.GONE);

    void showPanel() {
        now.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        list.requestFocus();
    }

    @Override public boolean onKeyDown(int key, KeyEvent e) {
        boolean open = panel.getVisibility() == View.VISIBLE;
        if (!open && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MENU
                || key == KeyEvent.KEYCODE_BACK)) {
            showPanel();
            return true;
        }
        if (open && key == KeyEvent.KEYCODE_BACK) {
            if (inChannels) { showSources(); return true; }
            if (hasVideo) { panel.setVisibility(View.GONE); return true; }
        }
        if (open && hasVideo && key == KeyEvent.KEYCODE_DPAD_RIGHT && getCurrentFocus() == list) {
            panel.setVisibility(View.GONE);
            return true;
        }
        return super.onKeyDown(key, e);
    }

    @Override protected void onStop() { super.onStop(); player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
