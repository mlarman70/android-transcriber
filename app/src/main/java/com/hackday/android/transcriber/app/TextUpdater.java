package com.hackday.android.transcriber.app;

import android.app.Activity;
import android.widget.TextView;

import java.util.List;

public class TextUpdater {

    private final Activity activity;
    private final TextView textView;
    private final TextSource textSource;

    private boolean stopping;

    public TextUpdater(Activity activity, TextView textView, TextSource textSource) {
        this.activity = activity;
        this.textView = textView;
        this.textSource = textSource;
        stopping = false;
    }

    public void start() {
        Thread th = new Thread(new Runnable() {
            public void run() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("");
                    }
                });

                while (!stopping) {
                    final List<String> strings = textSource.readText();
                    if (!strings.isEmpty()) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                for (String string : strings) {
                                    textView.append(string);
                                    textView.append(" ");
                                }
                            }
                        });
                    }
                    try {
                        Thread.sleep(100);
                    }
                    catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        th.start();
    }

    public void stop() {
        stopping = true;
    }

}
