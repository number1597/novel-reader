package com.novelreader.model;

import java.util.Objects;

/** 一个章节：标题 + 绝对 URL。 */
public class Chapter {

    private final String title;
    private final String url;

    public Chapter(String title, String url) {
        this.title = title == null ? "" : title.trim();
        this.url = url == null ? "" : url.trim();
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return title;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Chapter)) {
            return false;
        }
        Chapter other = (Chapter) o;
        return title.equals(other.title) && url.equals(other.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, url);
    }
}
