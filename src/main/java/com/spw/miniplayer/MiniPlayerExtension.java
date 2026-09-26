package com.spw.miniplayer;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import org.pf4j.Extension;

/**
 * 播放拓展点实现。
 *
 * <p>宿主每次换曲、播放状态变化、进度更新时都会回调这里，
 * 插件据此驱动小窗界面。所有回调都做了兜底，避免异常影响宿主。
 */
@Extension
public class MiniPlayerExtension implements PlaybackExtensionPoint {

    private static MiniPlayerController controller() {
        return MiniPlayerPlugin.controller();
    }

    @Override
    public void onStateChanged(State state) {
        MiniPlayerController current = controller();
        if (current != null) {
            current.onStateChanged(state);
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        MiniPlayerController current = controller();
        if (current != null) {
            current.onPlayingChanged(isPlaying);
        }
    }

    /**
     * 宿主在加载歌词前回调，是获取当前曲目元数据（标题/歌手/专辑/路径）的时机。
     *
     * @return 始终返回 null，表示歌词继续使用宿主默认逻辑
     */
    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        MiniPlayerController current = controller();
        if (current != null && mediaItem != null) {
            current.onMediaItem(mediaItem);
        }
        return null;
    }

    @Override
    public void onPositionUpdated(long position) {
        MiniPlayerController current = controller();
        if (current != null) {
            current.onPositionUpdated(position);
        }
    }
}
