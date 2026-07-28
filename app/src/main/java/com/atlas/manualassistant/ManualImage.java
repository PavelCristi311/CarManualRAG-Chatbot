package com.atlas.manualassistant;

final class ManualImage {
    final long id;
    final int page;
    final String assetPath;
    final String thumbnailPath;
    final String caption;

    ManualImage(long id, int page, String assetPath, String thumbnailPath, String caption) {
        this.id = id;
        this.page = page;
        this.assetPath = assetPath;
        this.thumbnailPath = thumbnailPath;
        this.caption = caption == null ? "" : caption;
    }
}
