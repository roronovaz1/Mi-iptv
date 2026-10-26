package com.mytv.iptv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
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
import java.util.*;

public class MainActivity extends Activity {
    static final String BASE = "https://iptv-org.github.io/iptv/";
    // Edit this list to add/remove playlists (any iptv-org .m3u path works)
    static final String[][] SOURCES = {
        {"Bengali", "languages/ben.m3u"}, {"India", "countries/in.m3u"},
        {"Bangladesh", "countries/bd.m3u"}, {"News", "categories/news.m3u"},
        {"Sports", "categories/sports.m3u"}, {"Movies", "categories/movies.m3u"},
        {"Music", "categories/music.m3u"}, {"Kids", "categories/kids.m3u"},
        {"Entertainment", "categories/entertainment.m3u"}
    };

    ExoPlayer player;
    ListView list;
    TextView title;
    View panel;
    ArrayAdapter<String> adapter;
    final List<String> names = new ArrayList<>(), urls = new ArrayList<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean inChannels = false, hasVideo = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        panel = findViewById(R.id.panel);
        title = findViewById(R.id.title);
        list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, R.layout.item, names);
        list.setAdapter(adapter);

        DefaultHttpDataSource.Factory f = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10000).setReadTimeoutMs(10000);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(f)).build();
        ((PlayerView) findViewById(R.id.player)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                Toast.makeText(MainActivity.this, "Channel not working, try another", Toast.LENGTH_LONG).show();
                showPanel();
            }
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            if (!inChannels) load(pos); else play(pos);
        });
        showSources();
    }

    void showSources() {
        inChannels = false;
        names.clear(); urls.clear();
        for (String[] s : SOURCES) names.add(s[0]);
        adapter.notifyDataSetChanged();
        title.setText("Mi IPTV");
        showPanel();
    }

    void load(int idx) {
        final String name = SOURCES[idx][0], url = BASE + SOURCES[idx][1];
        title.setText("Loading " + name + "…");
        new Thread(() -> {
            final List<String> n = new ArrayList<>(), u = new ArrayList<>();
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(20000);
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                String line, cur = null;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF")) {
                        int k = line.lastIndexOf(',');
                        cur = k >= 0 ? line.substring(k + 1).trim() : "Channel";
                    } else if (!line.isEmpty() && !line.startsWith("#") && cur != null) {
                        n.add(cur); u.add(line); cur = null;
                    }
                }
                r.close();
            } catch (Exception e) { n.clear(); u.clear(); }
            ui.post(() -> {
                if (n.isEmpty()) {
                    Toast.makeText(this, "Could not load list. Check internet.", Toast.LENGTH_LONG).show();
                    title.setText("Mi IPTV");
                    return;
                }
                inChannels = true;
                names.clear(); names.addAll(n);
                urls.clear(); urls.addAll(u);
                adapter.notifyDataSetChanged();
                list.setSelection(0);
                title.setText(name + " (" + n.size() + ")");
            });
        }).start();
    }

    void play(int pos) {
        player.setMediaItem(MediaItem.fromUri(urls.get(pos)));
        player.prepare();
        player.setPlayWhenReady(true);
        hasVideo = true;
        panel.setVisibility(View.GONE);
    }

    void showPanel() {
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
        return super.onKeyDown(key, e);
    }

    @Override protected void onStop() { super.onStop(); player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
