package com.qinqing.bangbang;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class WebRtcClient {
    interface Listener {
        void onState(String text);
        void onRemoteVideo(VideoTrack track);
    }

    private final Context context;
    private final String baseUrl;
    private final String pairCode;
    private final String authToken;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final EglBase eglBase = EglBase.create();

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoCapturer capturer;
    private VideoSource videoSource;
    private boolean running;
    private String role;
    private int remoteIceIndex;

    WebRtcClient(Context context, String baseUrl, String pairCode, String authToken, Listener listener) {
        this.context = context.getApplicationContext();
        this.baseUrl = baseUrl;
        this.pairCode = pairCode;
        this.authToken = authToken;
        this.listener = listener;
    }

    EglBase.Context eglContext() {
        return eglBase.getEglBaseContext();
    }

    void startElder(Intent projectionData) {
        role = "elder";
        running = true;
        io.execute(() -> {
            try {
                initPeer();
                addScreenTrack(projectionData);
                createOffer();
                pollAnswerLoop();
                pollIceLoop();
                notifyState("实时屏幕已启动，正在等待家属连接。");
            } catch (Throwable e) {
                notifyState("WebRTC 启动失败：" + safeMessage(e));
            }
        });
    }

    void startFamily() {
        role = "family";
        running = true;
        io.execute(() -> {
            try {
                initPeer();
                pollOfferLoop();
                pollIceLoop();
                notifyState("正在建立实时连接...");
            } catch (Throwable e) {
                notifyState("WebRTC 启动失败：" + safeMessage(e));
            }
        });
    }

    void stop() {
        running = false;
        io.shutdownNow();
        try {
            if (capturer != null) {
                capturer.stopCapture();
                capturer.dispose();
            }
        } catch (Exception ignored) {
        }
        if (videoSource != null) {
            videoSource.dispose();
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection.dispose();
        }
        if (factory != null) {
            factory.dispose();
        }
        eglBase.release();
    }

    private void initPeer() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
        );
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), false, false);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        peerConnection = factory.createPeerConnection(config, observer);
    }

    private void addScreenTrack(Intent projectionData) {
        capturer = new ScreenCapturerAndroid(projectionData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                notifyState("屏幕共享已停止。");
            }
        });
        SurfaceTextureHelper textureHelper = SurfaceTextureHelper.create("screen-capture", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(capturer.isScreencast());
        capturer.initialize(textureHelper, context, videoSource.getCapturerObserver());
        capturer.startCapture(540, 960, 18);
        VideoTrack videoTrack = factory.createVideoTrack("elder-screen", videoSource);
        peerConnection.addTrack(videoTrack, Collections.singletonList("assist"));
    }

    private void createOffer() {
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), description);
                postSdp("/api/webrtc/offer", description);
            }
        }, new MediaConstraints());
    }

    private void pollOfferLoop() throws Exception {
        while (running && peerConnection != null && peerConnection.getRemoteDescription() == null) {
            JSONObject result = NetworkClient.getJson(baseUrl, "/api/webrtc/offer?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
            JSONObject offer = result.optJSONObject("offer");
            if (offer != null && !offer.optString("sdp", "").isEmpty()) {
                SessionDescription remote = new SessionDescription(SessionDescription.Type.OFFER, offer.optString("sdp"));
                peerConnection.setRemoteDescription(new SimpleSdpObserver(), remote);
                createAnswer();
                return;
            }
            Thread.sleep(500);
        }
    }

    private void createAnswer() {
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription description) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), description);
                postSdp("/api/webrtc/answer", description);
            }
        }, new MediaConstraints());
    }

    private void pollAnswerLoop() throws Exception {
        while (running && peerConnection != null && peerConnection.getRemoteDescription() == null) {
            JSONObject result = NetworkClient.getJson(baseUrl, "/api/webrtc/answer?pairCode=" + encoded(pairCode) + "&authToken=" + encoded(authToken));
            JSONObject answer = result.optJSONObject("answer");
            if (answer != null && !answer.optString("sdp", "").isEmpty()) {
                SessionDescription remote = new SessionDescription(SessionDescription.Type.ANSWER, answer.optString("sdp"));
                peerConnection.setRemoteDescription(new SimpleSdpObserver(), remote);
                notifyState("实时连接已建立。");
                return;
            }
            Thread.sleep(500);
        }
    }

    private void pollIceLoop() {
        io.execute(() -> {
            String from = "elder".equals(role) ? "family" : "elder";
            while (running) {
                try {
                    String path = "/api/webrtc/ice?pairCode=" + encoded(pairCode)
                            + "&authToken=" + encoded(authToken)
                            + "&role=" + role
                            + "&from=" + from
                            + "&since=" + remoteIceIndex;
                    JSONObject result = NetworkClient.getJson(baseUrl, path);
                    JSONArray candidates = result.optJSONArray("candidates");
                    if (candidates != null) {
                        for (int i = 0; i < candidates.length(); i++) {
                            JSONObject item = candidates.getJSONObject(i);
                            peerConnection.addIceCandidate(new IceCandidate(
                                    item.optString("sdpMid"),
                                    item.optInt("sdpMLineIndex"),
                                    item.optString("candidate")
                            ));
                        }
                    }
                    remoteIceIndex = result.optInt("next", remoteIceIndex);
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void postSdp(String path, SessionDescription description) {
        io.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, path, new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("type", description.type.canonicalForm())
                        .put("sdp", description.description));
            } catch (Exception e) {
                notifyState("信令发送失败：" + e.getMessage());
            }
        });
    }

    private void postIce(IceCandidate candidate) {
        io.execute(() -> {
            try {
                NetworkClient.postJson(baseUrl, "/api/webrtc/ice", new JSONObject()
                        .put("pairCode", pairCode)
                        .put("authToken", authToken)
                        .put("from", role)
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp));
            } catch (Exception ignored) {
            }
        });
    }

    private String encoded(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private void notifyState(String text) {
        if (listener != null) {
            main.post(() -> listener.onState(text));
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            notifyState("实时连接状态：" + state.name());
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidate(IceCandidate candidate) { postIce(candidate); }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(org.webrtc.MediaStream stream) {
            if (!stream.videoTracks.isEmpty() && listener != null) {
                main.post(() -> listener.onRemoteVideo(stream.videoTracks.get(0)));
            }
        }
        @Override public void onRemoveStream(org.webrtc.MediaStream stream) {}
        @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, org.webrtc.MediaStream[] streams) {
            if (receiver.track() instanceof VideoTrack && listener != null) {
                VideoTrack track = (VideoTrack) receiver.track();
                main.post(() -> listener.onRemoteVideo(track));
            }
        }
        @Override public void onTrack(RtpTransceiver transceiver) {
            if (transceiver.getReceiver().track() instanceof VideoTrack && listener != null) {
                VideoTrack track = (VideoTrack) transceiver.getReceiver().track();
                main.post(() -> listener.onRemoteVideo(track));
            }
        }
    };

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
}
