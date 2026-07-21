package com.xiagu.bp;

import android.app.Application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Starts the network metadata sync and portrait descriptor build with the app process. */
public final class XiaGuBpApplication extends Application {
    enum Phase { SYNCING, BUILDING, READY, ERROR }

    record BootstrapState(Phase phase, int heroCount, String message) {}

    interface Listener {
        void onBootstrapState(BootstrapState state);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<BpModels.Hero> originalAvatarRoster = Collections.emptyList();
    private volatile BootstrapState bootstrapState = new BootstrapState(
        Phase.SYNCING,
        0,
        "正在直连天元之弈原始头像库"
    );

    @Override
    public void onCreate() {
        super.onCreate();
        startBootstrap();
    }

    BootstrapState bootstrapState() {
        return bootstrapState;
    }

    void addBootstrapListener(Listener listener) {
        listeners.add(listener);
        listener.onBootstrapState(bootstrapState);
    }

    void removeBootstrapListener(Listener listener) {
        listeners.remove(listener);
    }

    List<BpModels.Hero> originalAvatarRoster() {
        return originalAvatarRoster;
    }

    private void startBootstrap() {
        publish(new BootstrapState(Phase.SYNCING, 0, "正在直连天元之弈原始头像库"));
        BpApiClient.loadOriginalAvatarRoster(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                originalAvatarRoster = Collections.unmodifiableList(new ArrayList<>(heroes));
                publish(new BootstrapState(Phase.BUILDING, heroes.size(), "正在建立天元原始头像描述库"));
                AvatarRecognitionEngine.prewarm(
                    XiaGuBpApplication.this,
                    heroes,
                    new AvatarRecognitionEngine.PrewarmCallback() {
                        @Override
                        public void onReady(int count) {
                            publish(new BootstrapState(Phase.READY, count, "天元原始头像库冷启动完成"));
                        }

                        @Override
                        public void onError(String message) {
                            publish(new BootstrapState(Phase.ERROR, heroes.size(), message));
                        }
                    }
                );
            }

            @Override
            public void onError(String message) {
                publish(new BootstrapState(Phase.ERROR, 0, message));
            }
        });
    }

    private void publish(BootstrapState state) {
        bootstrapState = state;
        for (Listener listener : listeners) listener.onBootstrapState(state);
    }
}
