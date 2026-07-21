package com.xiagu.bp;

import android.app.Application;

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
    private volatile BootstrapState bootstrapState = new BootstrapState(
        Phase.SYNCING,
        0,
        "正在同步巅峰千强英雄库"
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

    private void startBootstrap() {
        publish(new BootstrapState(Phase.SYNCING, 0, "正在同步巅峰千强英雄库"));
        BpApiClient.loadMeta(new BpApiClient.Callback<>() {
            @Override
            public void onSuccess(List<BpModels.Hero> heroes) {
                publish(new BootstrapState(Phase.BUILDING, heroes.size(), "正在建立本地头像描述库"));
                AvatarRecognitionEngine.prewarm(
                    XiaGuBpApplication.this,
                    heroes,
                    new AvatarRecognitionEngine.PrewarmCallback() {
                        @Override
                        public void onReady(int count) {
                            publish(new BootstrapState(Phase.READY, count, "头像库冷启动完成"));
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
